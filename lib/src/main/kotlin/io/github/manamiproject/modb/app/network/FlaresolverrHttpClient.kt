package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.httpclient.APPLICATION_JSON
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.core.json.Json
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Proxy.Type.HTTP
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration

internal class FlaresolverrHttpClient(
    private val appConfig: Config = AppConfig.instance,
    private val flaresolverrConfig: FlaresolverrConfig = FlaresolverrConfig.instance,
    private val tunnelConfig: TunnelConfig = TunnelConfig.instance,
    private val commercialProxyConfig: CommercialProxyConfig = CommercialProxyConfig.instance,
    // Which FlareSolverr container this client talks to. Default is the tunnel container; a client for a
    // commercially-proxied provider is created with the commercial container's port (see flaresolverrHttpClientFor).
    private val port: Int = flaresolverrConfig.port,
    private val httpClient: HttpClient = DefaultHttpClient(
        // FlareSolverr is allowed up to maxTimeout ms to solve a challenge, so our own socket read timeout must
        // exceed that - otherwise a legitimate slow solve trips a spurious SocketTimeoutException and gets retried
        // against the still-busy (single-browser) container.
        readTimeoutInSeconds = (flaresolverrConfig.maxTimeout / 1000L) + READ_TIMEOUT_BUFFER_SECONDS,
        isTestContext = appConfig.isTestContext(),
        // Keep the FlareSolverr response streaming (it is consumed via bodyAsStream) rather than buffered.
        bufferResponseBody = false,
        // FlareSolverr answers a failed solve with HTTP 500 and puts the reason in the body, so the body has to
        // survive for withSessionRetry to tell a refused exit IP from an unsolved challenge. The default retry
        // path closes it before the caller sees it, and then retries every attempt against the same session and
        // therefore the same exit IP - which is the one thing that cannot help. On 2026-10-16 that spent four of
        // a five minute budget re-asking an address which had answered "your IP is banned" after 2.4 seconds,
        // and charged the resulting timeout to an unrelated entry. Recovery belongs in withSessionRetry, which
        // can draw a new address.
        retryServerErrors = false,
    ),
    // Only set by tests, so the direct path can be exercised without a real proxy. Production leaves this
    // null and builds one client per session instead, because the sticky credentials change with every
    // session and the clearance cookie is only valid for the address those credentials select.
    private val directHttpClient: HttpClient? = null,
): HttpClient {

    private val flaresolverrUrl: URL = URI("http://localhost:$port/v1").toURL()

    // The session map and browser-serializing semaphore for this client's container. Selected by port so that
    // every client for the same container shares one session-per-domain and one serialized browser, while the
    // two containers stay fully independent and can run in parallel.
    private val endpoint: FlaresolverrEndpoint = when (port) {
        commercialProxyConfig.flaresolverrPort -> commercialEndpoint
        else -> defaultEndpoint
    }
    private val maxTimeout: Int = flaresolverrConfig.maxTimeout

    // Hard wall-clock cap on a single FlareSolverr operation, covering both the wait for the semaphore permit and
    // the round-trip itself. A wedged container or a permit that is never released would otherwise suspend the
    // calling crawler forever with no exception - silently defeating the fail-fast design. Exceeding this throws
    // TimeoutCancellationException, which propagates up and aborts the run instead of hanging. Sized above
    // maxTimeout so it never pre-empts a solve that FlareSolverr is still legitimately working on.
    private val operationTimeout: Duration =
        (maxTimeout.toLong() + OPERATION_TIMEOUT_BUFFER_MS).toDuration(MILLISECONDS)

    // Skipped under test: kotlinx runTest uses a virtual clock that fast-forwards to the timeout deadline the moment
    // work hops off the test scheduler, which would spuriously trip withTimeout. Mirrors how waits are excluded from
    // the test context elsewhere in this codebase.
    private suspend fun <T> withOperationTimeout(targetUrl: String, block: suspend () -> T): T = when {
        appConfig.isTestContext() -> block()
        else -> try {
            withTimeout(operationTimeout) { block() }
        } catch (e: TimeoutCancellationException) {
            // Rethrown as a plain exception because TimeoutCancellationException extends
            // CancellationException, which structured concurrency reads as "this child was cancelled" rather
            // than "this child failed". App.kt starts each crawler with launch{}, so a cancelled crawler ends
            // quietly, its scope completes as if every crawler had finished, and the run goes on to build and
            // publish a dataset from a truncated crawl. That is precisely the partial-data outcome fail-fast
            // exists to prevent, and only the DCS week validation caught it (2026-10-16: anidb stopped at
            // 2,057 of 15,454 entries this way).
            throw IllegalStateException("FlareSolverr operation for [$targetUrl] exceeded [$operationTimeout] ms. Aborting the run rather than continuing with a truncated crawl.", e)
        }
    }

    override suspend fun post(
        url: URL,
        requestBody: RequestBody,
        headers: Map<String, Collection<String>>,
    ): HttpResponse = sendToFlaresolverr(url) { session ->
        """
            {
              "cmd": "request.post",
              "url": "$url",
              "maxTimeout": $maxTimeout,
              "postData": "${requestBody.body}",
              "session": "$session"
            }
        """.trimIndent()
    }

    override suspend fun get(
        url: URL,
        headers: Map<String, Collection<String>>,
    ): HttpResponse = sendToFlaresolverr(url, allowDirectFetch = true) { session ->
        """
            {
              "cmd": "request.get",
              "url": "$url",
              "maxTimeout": $maxTimeout,
              "session": "$session"
            }
        """.trimIndent()
    }

    /**
     * Returns the FlareSolverr session to use for [hostname], creating it on first use.
     *
     * One session per metadata provider, because a session is a browser whose cookie jar - including the
     * Cloudflare clearance cookie - is per-domain. Sharing one across provider(s) would gain nothing, and
     * each provider re-solving its own challenge once is unavoidable.
     *
     * Must not be called while holding the endpoint's semaphore: creating a session is itself a FlareSolverr
     * call which takes the permit, and the semaphore is not reentrant.
     */
    private suspend fun sessionFor(hostname: Hostname): String {
        rotateSessionIfDue(hostname)

        endpoint.sessions[hostname]?.let { return it }

        // Double-checked under the lock: several crawlers hit the same provider concurrently (e.g. the anidb
        // crawler and its highest-id detector), and without this they would each create a session, leaking
        // every one but the last.
        return endpoint.sessionCreationMutex.withLock {
            endpoint.sessions[hostname] ?: createSession(hostname).also { endpoint.sessions[hostname] = it }
        }
    }

    /**
     * Destroys the session for [hostname] once it has served [CommercialProxyConfig.rotateAfterRequests]
     * requests, so the next request creates a new one. Combined with a session id in the credentials (see
     * [CommercialProxyConfig.SESSION_PLACEHOLDER]) that is what actually draws a fresh residential exit IP:
     * the provider cannot tell sessions apart otherwise, because they all present the same credentials.
     *
     * Only commercially-routed provider(s) rotate. The tunnel has exactly one residential IP, so recycling
     * its session would throw away a valid Cloudflare clearance and buy nothing.
     *
     * Called from [sessionFor], which runs before the semaphore permit is taken - [destroySession] needs the
     * permit itself, and the semaphore is not reentrant.
     */
    private suspend fun rotateSessionIfDue(hostname: Hostname) {
        val rotateAfter = commercialProxyConfig.rotateAfterRequests(hostname)

        if (rotateAfter <= 0 || !commercialProxyConfig.isRouted(hostname)) {
            return
        }

        val served = endpoint.requestCounts.computeIfAbsent(hostname) { AtomicInteger() }

        if (served.incrementAndGet() <= rotateAfter) {
            return
        }

        log.info { "FlareSolverr session for [$hostname] served [$rotateAfter] request(s). Recycling it for a fresh exit IP." }

        // Counts the request which triggered the rotation, so each session serves exactly rotateAfter of
        // them. A concurrent caller that also crossed the threshold finds the session already gone.
        recycleSession(hostname, servedAfterRecycle = 1)
    }

    /**
     * Drops the current session for [hostname] so the next request creates a new one, with a new session id
     * and therefore a new exit IP.
     */
    private suspend fun recycleSession(hostname: Hostname, servedAfterRecycle: Int) {
        endpoint.sessionCreationMutex.withLock {
            endpoint.requestCounts.computeIfAbsent(hostname) { AtomicInteger() }.set(servedAfterRecycle)

            endpoint.clearances.remove(hostname)

            val session = endpoint.sessions.remove(hostname) ?: return@withLock

            destroySession(hostname, session)
        }
    }

    /**
     * Whether recycling the session for [hostname] would actually change the exit IP.
     *
     * Only true for a commercially-routed provider whose credentials carry a session id. Recycling a
     * tunneled provider's session re-solves the Cloudflare challenge against the same single home IP, so it
     * costs ~19s and gains nothing.
     */
    private fun canDrawNewExitIp(hostname: Hostname): Boolean =
        commercialProxyConfig.isRouted(hostname) && commercialProxyConfig.rotatesSessionId()

    /**
     * Resolves the exit IP of a freshly created session and logs it, as a change from the previous one where
     * there is one.
     *
     * Without this the crawl log cannot show that rotation worked: the app only sees FlareSolverr's own
     * session id, which proves a new browser rather than a new address. Costs one small request per session.
     *
     * Entirely best effort. A failure here says nothing about the crawl - it may just be that the IP service
     * is unreachable - so it is logged and swallowed rather than aborting a multi-day run.
     */
    private suspend fun logExitIp(hostname: Hostname, session: String) {
        if (!flaresolverrConfig.logExitIp || appConfig.isTestContext()) {
            return
        }

        runCatching {
            val content = execute(
                targetUrl = EXIT_IP_SERVICE,
                body = """{"cmd":"request.get","url":"$EXIT_IP_SERVICE","session":"$session"}""",
            )
            EXIT_IP_PATTERN.find(content.solution.response)?.value
        }.onSuccess { exitIp ->
            when {
                exitIp == null -> log.warn { "Could not determine the exit IP for [$hostname] - no address in the response from [$EXIT_IP_SERVICE]." }
                else -> {
                    val previous = endpoint.exitIps.put(hostname, exitIp)
                    when (previous) {
                        null -> log.info { "Exit IP for [$hostname] is [$exitIp]." }
                        exitIp -> log.warn { "Exit IP for [$hostname] is still [$exitIp] after recycling the session - the provider did not rotate it." }
                        else -> log.info { "Exit IP for [$hostname] rotated [$previous] -> [$exitIp]." }
                    }
                }
            }
        }.onFailure {
            log.warn { "Could not determine the exit IP for [$hostname]: [${it.message}]." }
        }
    }

    /**
     * Destroys one FlareSolverr session, best effort. A session which cannot be destroyed dies with the
     * container, so a failure here is logged rather than propagated: it must not abort a multi-day crawl.
     */
    private suspend fun destroySession(hostname: Hostname, session: String) {
        runCatching {
            execute(hostname, """{"cmd":"sessions.destroy","session":"$session"}""")
        }.onSuccess {
            log.info { "Destroyed FlareSolverr session for [$hostname]." }
        }.onFailure {
            log.warn { "Unable to destroy FlareSolverr session for [$hostname]: [${it.message}]." }
        }
    }

    /**
     * Returns a session for [hostname] which has already cleared Cloudflare, drawing up to
     * [MAX_SESSION_WARM_UP_ATTEMPTS] exit IPs to get one. See [warmUp] for why that is worth doing before the
     * crawl's first real request rather than leaving it to chance.
     */
    private suspend fun createSession(hostname: Hostname): String {
        var attempt = 1

        while (true) {
            val (session, proxySessionId) = createOneSession(hostname)

            if (!needsWarmUp(hostname) || warmUp(hostname, session, proxySessionId)) {
                return session
            }

            // Out of attempts, or a single fixed address where another draw is not a thing. Returning the
            // session anyway rather than failing: the crawl's own request may still clear the challenge, and
            // if it does not, one entry is skipped instead of the run ending here.
            if (attempt >= MAX_SESSION_WARM_UP_ATTEMPTS || !canDrawNewExitIp(hostname)) {
                log.warn { "Could not clear Cloudflare for [$hostname] during warm-up after [$attempt] exit IP(s). Continuing on the current one." }
                return session
            }

            log.warn { "Warm-up for [$hostname] could not clear Cloudflare on this exit IP. Drawing another one (attempt [${attempt + 1}] of [$MAX_SESSION_WARM_UP_ATTEMPTS])." }
            destroySession(hostname, session)
            attempt++
        }
    }

    /**
     * Whether a freshly created session for [hostname] should clear Cloudflare before the crawl uses it.
     *
     * Only sessions which exit through the tunnel or the commercial proxy, because only those sit behind a
     * challenge worth pre-solving - a direct session has no proxy and is not used for a protected provider.
     */
    private fun needsWarmUp(hostname: Hostname): Boolean =
        commercialProxyConfig.isRouted(hostname) || tunnelConfig.isTunneled(hostname)

    /**
     * Solves [hostname]'s Cloudflare challenge on a new session before the crawl's first real request uses it,
     * and reports whether that worked.
     *
     * The challenge is paid exactly once per session, because the clearance cookie lives in that session's
     * browser and covers every later request to the same domain - 60 challenges across 63 sessions in the
     * 2026-10-16 run, with the following ~50 requests each answering in under a second. Whichever request
     * comes first therefore pays it, and roughly one cold solve in eight fails outright. Without this that
     * cost and that risk land on an arbitrary anime id, which is how anidb ids 423, 4119, 8848 and 16948 came
     * to be skipped despite being perfectly ordinary pages. Spending it here instead means a failure costs an
     * exit IP rather than an entry.
     *
     * Deliberately requests the site root: it carries the same challenge as any other page while being the
     * cheapest thing to fetch, and its content is discarded either way.
     */
    private suspend fun warmUp(hostname: Hostname, session: String, proxySessionId: String): Boolean {
        val targetUrl = "https://$hostname/"

        val content = try {
            execute(
                targetUrl = targetUrl,
                body = """{"cmd":"request.get","url":"$targetUrl","maxTimeout":$maxTimeout,"session":"$session"}""",
            )
        } catch (throwable: Throwable) {
            // A warm-up failure is a reason to draw another address, not to end the run - but a cancellation
            // still has to propagate, or structured concurrency silently loses this crawler.
            if (throwable is CancellationException) {
                throw throwable
            }

            log.warn { "Warm-up request for [$hostname] failed: [${throwable.message}]." }
            return false
        }

        if (content.isOk()) {
            log.info { "Warmed up the FlareSolverr session for [$hostname] - Cloudflare cleared on this exit IP." }
            rememberClearance(hostname, session, proxySessionId, content)
        }

        return content.isOk()
    }

    /**
     * Keeps the Cloudflare clearance this warm-up just earned, so the rest of the session's requests can be
     * fetched without the browser.
     *
     * What Cloudflare checks on a cleared request is the clearance cookie, the exit IP it was issued to and
     * the user agent that solved it - not that a browser is making the request. Holding those three lets a
     * plain HTTP client fetch the remaining pages of this session, which is worth doing because the browser
     * also loads every stylesheet, font and thumbnail on the page and this crawl keeps none of them: 534 KB
     * of metered residential traffic per anidb page against 32 KB fetched directly (measured 2026-10-16).
     *
     * Dropped again whenever the session is recycled or destroyed, because the next session draws a different
     * exit IP and the cookie is bound to the old one.
     */
    private fun rememberClearance(
        hostname: Hostname,
        session: String,
        proxySessionId: String,
        content: FlaresolverrResponse,
    ) {
        if (!commercialProxyConfig.allowsDirectFetch(hostname)) {
            return
        }

        val cookieHeader = content.solution.cookies
            .filter { it.name.isNotBlank() }
            .joinToString("; ") { "${it.name}=${it.value}" }

        if (cookieHeader.isBlank() || content.solution.userAgent.isBlank()) {
            log.warn { "FlareSolverr returned no usable clearance for [$hostname], so its requests keep going through the browser." }
            return
        }

        // A proxy URL this client cannot use on its own is a reason to keep using the browser, not to end
        // the run, so a failure to build the direct client is logged and the clearance simply not kept.
        // Under test only an injected client is used, because a real one would reach for the network.
        val client = directHttpClient
            ?: when {
                appConfig.isTestContext() -> return
                else -> runCatching { directHttpClientFor(proxySessionId) }
                    .onFailure { log.warn { "Cannot fetch [$hostname] without the browser: [${it.message}]" } }
                    .getOrNull() ?: return
            }

        endpoint.clearances[hostname] = SessionClearance(
            session = session,
            cookieHeader = cookieHeader,
            userAgent = content.solution.userAgent,
            httpClient = client,
        )
    }

    /**
     * A plain HTTP client which exits through the same sticky proxy session as [proxySessionId], so a request
     * it sends arrives from the address the clearance cookie was issued to.
     */
    private fun directHttpClientFor(proxySessionId: String): HttpClient {
        val (host, port) = commercialProxyConfig.proxyHostAndPort()
        val (user, pass) = commercialProxyConfig.credentials(proxySessionId)

        return DefaultHttpClient(
            proxy = Proxy(HTTP, InetSocketAddress(host, port)),
            proxyUsername = user,
            proxyPassword = pass,
            isTestContext = appConfig.isTestContext(),
        )
    }

    /**
     * Fetches [targetUrl] with the session's stored clearance instead of the browser, or returns `null` to
     * leave the request to FlareSolverr.
     *
     * Returns `null` rather than throwing on every doubt - no clearance, a clearance belonging to a session
     * which has since been recycled, an unusable status, an empty body, or any transport fault. The browser
     * path then runs exactly as it did before, so the worst case of this whole mechanism is the bandwidth it
     * was meant to save. Only a cancellation propagates, because swallowing one breaks structured concurrency.
     *
     * HTTP 404 counts as usable: anidb answers a removed entry that way and
     * [io.github.manamiproject.modb.anidb.AnidbDownloader] reads the body to record it as a dead entry.
     */
    private suspend fun directGet(hostname: Hostname, targetUrl: URL, session: String): FlaresolverrResponse? {
        val clearance = endpoint.clearances[hostname]?.takeIf { it.session == session } ?: return null

        val response = try {
            clearance.httpClient.get(
                url = targetUrl,
                headers = mapOf(
                    "user-agent" to listOf(clearance.userAgent),
                    "cookie" to listOf(clearance.cookieHeader),
                ),
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }

            log.warn { "Direct request for [$targetUrl] failed - [${throwable.message}]. Falling back to the browser." }
            return null
        }

        val body = response.bodyAsString()

        if (!USABLE_DIRECT_STATUS_CODES.contains(response.code) || body.isBlank()) {
            log.warn { "Direct request for [$targetUrl] returned status [${response.code}] and [${body.length}] character(s). Falling back to the browser." }
            return null
        }

        return FlaresolverrResponse(
            status = FLARESOLVERR_STATUS_OK,
            solution = FlaresolverrResponseSolution(status = response.code, response = body),
        )
    }

    /**
     * Creates one FlareSolverr session, without warming it up.
     *
     * The `proxy` is set here rather than on each request: a session keeps the proxy it was created with, so
     * a request carrying only `session` still exits through its proxy (verified). Setting a proxy on the
     * [httpClient] would achieve nothing - that client only ever talks to FlareSolverr on localhost.
     *
     * The tunnel and commercial sets are mutually exclusive (enforced by checkCommercialProxy), so at most one
     * branch matches.
     */
    private suspend fun createOneSession(hostname: Hostname): Pair<String, String> {
        // The session id is generated here rather than inside proxyJson so the same value can be logged. The
        // log is the only place it is ever visible: it is embedded in the proxy credentials, and a provider
        // hands out a new exit IP precisely because it has not seen that id before.
        val sessionId = commercialProxyConfig.newSessionId()

        val (proxy, via) = when {
            commercialProxyConfig.isRouted(hostname) -> {
                val suffix = when {
                    commercialProxyConfig.rotatesSessionId() -> " via the commercial proxy, proxy session id [$sessionId]"
                    else -> " via the commercial proxy (no session id in the credentials, so the exit IP is the provider's choice)"
                }
                commercialProxyConfig.proxyJson(sessionId) to suffix
            }
            tunnelConfig.isTunneled(hostname) -> ""","proxy":{"url":"${tunnelConfig.socksUrl()}"}""" to " via the tunnel"
            else -> EMPTY to EMPTY
        }

        val content = execute(
            targetUrl = hostname,
            body = """{"cmd":"sessions.create"$proxy}""",
        )

        check(content.isOk() && content.session.isNotBlank()) {
            "Unable to create FlareSolverr session for [$hostname] - status [${content.status}], message [${content.message}]."
        }

        log.info { "Created FlareSolverr session [${content.session}] for [$hostname]$via." }

        if (proxy.isNotEmpty()) {
            logExitIp(hostname, content.session)
        }

        return content.session to sessionId
    }

    /**
     * Sends a command to FlareSolverr for [targetUrl] and unwraps its solution.
     *
     * Every failure is logged against [targetUrl] (and includes FlareSolverr's own status/message), so the crawl log
     * alone identifies which provider's request failed - the underlying [httpClient] only ever sees the opaque
     * `localhost:<port>/v1` endpoint and cannot attribute the failure to a provider.
     */
    private suspend fun sendToFlaresolverr(
        targetUrl: URL,
        allowDirectFetch: Boolean = false,
        buildBody: (String) -> String,
    ): HttpResponse {
        val hostname = targetUrl.host.lowercase().removePrefix("www.")

        // Either a successful solution or an exception - withSessionRetry never hands back a failed response,
        // so there is no partially-filled solution to guard against here.
        val content = withSessionRetry(hostname) { session ->
            val direct = when {
                allowDirectFetch -> directGet(hostname, targetUrl, session)
                else -> null
            }

            direct ?: execute(targetUrl.toString(), buildBody(session))
        }

        return HttpResponse(
            code = content.solution.status,
            body = content.solution.response,
        )
    }

    /**
     * Runs [block] with the session for [hostname], retrying it once when the first attempt failed in a way a
     * different session or a different exit IP can fix.
     *
     * A session can be lost mid-run (a crashed browser, or a container restarted underneath us). That is a
     * recoverable, transient fault rather than a provider blocking us, so it must not abort a multi-day crawl
     * - unlike a genuine block, which still fails fast.
     *
     * Every other failure FlareSolverr reports - Cloudflare refusing the exit IP outright, or a challenge it
     * could not solve within `maxTimeout` - is a property of the address rather than of the request, so the
     * one useful response is a new address. Both are indistinguishable from the request's point of view and
     * are therefore handled together.
     */
    private suspend fun withSessionRetry(
        hostname: Hostname,
        block: suspend (String) -> FlaresolverrResponse,
    ): FlaresolverrResponse {
        val result = try {
            block(sessionFor(hostname))
        } catch (throwable: Throwable) {
            // Catching Throwable must not extend to cancellation: swallowing a CancellationException breaks
            // structured concurrency, and would turn a genuine shutdown into a retry.
            if (throwable is CancellationException) {
                throw throwable
            }

            // Reached when the request did not even produce a FlareSolverr response - a transport fault, or
            // the wall-clock cap in withOperationTimeout. On a rotating pool the most likely cause is still a
            // bad draw, so a different IP is worth one attempt. Without this the run fail-fast aborts, which
            // on a multi-day seed is expensive.
            if (!canDrawNewExitIp(hostname)) {
                throw throwable
            }

            log.warn { "FlareSolverr request for [$hostname] failed - [${throwable.message}]. Recycling the session to draw a different exit IP and retrying once." }
            recycleSession(hostname, servedAfterRecycle = 0)

            return block(sessionFor(hostname)).orThrow(hostname)
        }

        if (result.isOk()) {
            return result
        }

        if (result.indicatesMissingSession()) {
            log.warn { "FlareSolverr session for [$hostname] is gone - [${result.message}]. Creating a new one and retrying once." }
            endpoint.sessionCreationMutex.withLock {
                endpoint.clearances.remove(hostname)
                endpoint.sessions.remove(hostname)
            }

            return block(sessionFor(hostname)).orThrow(hostname)
        }

        // Retrying on the same session cannot help, because the exit IP is what the failure is about - only a
        // new address can. Distinct from CrawlerDetectedException, which the crawler handles: this is
        // Cloudflare refusing or stalling the IP rather than the site flagging the crawl.
        if (canDrawNewExitIp(hostname)) {
            val reason = when {
                result.indicatesBlockedExitIp() -> "Cloudflare refused the current exit IP"
                else -> "Cloudflare could not be cleared on the current exit IP"
            }

            log.warn { "$reason for [$hostname] - [${result.message}]. Recycling the session to draw a different one and retrying once." }
            recycleSession(hostname, servedAfterRecycle = 0)

            return block(sessionFor(hostname)).orThrow(hostname)
        }

        return result.orThrow(hostname)
    }

    /**
     * Performs one FlareSolverr round-trip. Holds this endpoint's semaphore for exactly the duration of the
     * call, since the container has a single browser and returns HTTP 500 when hit concurrently. Each
     * container has its own semaphore, so the two containers run in parallel.
     *
     * All FlareSolverr traffic must be sent as [APPLICATION_JSON] - any other content type makes FlareSolverr reject
     * the body with "Request parameter 'cmd' is mandatory" (HTTP 500).
     */
    private suspend fun execute(targetUrl: String, body: String): FlaresolverrResponse = withOperationTimeout(targetUrl) {
        endpoint.semaphore.withPermit {
            try {
                val postResponse = httpClient.post(
                    url = flaresolverrUrl,
                    requestBody = RequestBody(mediaType = APPLICATION_JSON, body = body),
                )
                Json.parseJson<FlaresolverrResponse>(postResponse.bodyAsStream())!!
            } catch (throwable: Throwable) {
                log.warn { "FlareSolverr request for [$targetUrl] sent via [$flaresolverrUrl] failed: [${throwable.message}]." }
                throw throwable
            }
        }
    }

    /**
     * Destroys every session created by this run.
     *
     * Not optional housekeeping: each session holds a browser open, and [startFlaresolverr] deliberately
     * reuses an already-running container, so leaked sessions would accumulate across runs in a container
     * nothing ever restarts.
     */
    internal suspend fun destroySessions() {
        endpoint.sessionCreationMutex.withLock {
            endpoint.sessions.forEach { (hostname, session) -> destroySession(hostname, session) }
            endpoint.sessions.clear()
            endpoint.requestCounts.clear()
            endpoint.clearances.clear()
        }
    }

    override fun addRetryCases(vararg retryCases: RetryCase): HttpClient {
        httpClient.addRetryCases(*retryCases)
        return this
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Seconds added on top of FlareSolverr's own `maxTimeout` (converted to seconds) to derive the socket read
         * timeout of the underlying HTTP client, so a legitimate slow solve is never cut off by a socket read timeout.
         */
        private const val READ_TIMEOUT_BUFFER_SECONDS = 30L

        /**
         * Milliseconds added on top of FlareSolverr's own `maxTimeout` to derive the hard wall-clock cap on a single
         * FlareSolverr operation. Larger than [READ_TIMEOUT_BUFFER_SECONDS] so the underlying client's own read
         * timeout surfaces first when possible, leaving this purely as the backstop against a silent hang.
         */
        private const val OPERATION_TIMEOUT_BUFFER_MS = 60_000L

        /**
         * How many exit IPs a new session may try before the crawl proceeds on whichever one it last drew.
         * Each failed attempt costs up to `maxTimeout`, so this trades a bounded amount of time for not
         * losing entries - see [warmUp].
         */
        private const val MAX_SESSION_WARM_UP_ATTEMPTS = 3

        /** Service used to resolve a session's exit IP. Returns the bare address as plain text. */
        private const val EXIT_IP_SERVICE = "https://api.ipify.org"

        /** FlareSolverr's own value for a served request, reused when a direct fetch stands in for one. */
        private const val FLARESOLVERR_STATUS_OK = "ok"

        /**
         * Status codes a direct fetch may return without falling back to the browser. 404 is included because
         * anidb answers an entry it has removed that way, and the body still says so.
         */
        private val USABLE_DIRECT_STATUS_CODES = setOf(200, 404)

        private val EXIT_IP_PATTERN = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

        /**
         * Per-container shared state, one holder per FlareSolverr container. Static (companion) because every
         * crawler constructs its own [FlaresolverrHttpClient], yet all clients for the same container must
         * converge on one session per provider and one serialized browser - otherwise the Cloudflare challenge
         * is re-solved per crawler, or concurrent requests make the single-browser container return HTTP 500.
         * Permit count comes from [FlaresolverrConfig.maxConcurrency].
         *
         * There are exactly two containers (the tunnel one and the commercial one), so two named holders are
         * clearer than a map keyed by port. [FlaresolverrHttpClient] selects its holder from its own port.
         */
        private val defaultEndpoint = FlaresolverrEndpoint(FlaresolverrConfig.instance.maxConcurrency)
        private val commercialEndpoint = FlaresolverrEndpoint(FlaresolverrConfig.instance.maxConcurrency)

        /**
         * Singleton of [FlaresolverrHttpClient] for the default (tunnel) container.
         * @since 1.0.0
         */
        val instance: FlaresolverrHttpClient by lazy { FlaresolverrHttpClient() }

        /**
         * Singleton of [FlaresolverrHttpClient] bound to the commercial-proxy container. Used to tear down
         * that container's sessions at the end of a run; the crawlers reach it via [flaresolverrHttpClientFor].
         * @since 1.15.0
         */
        val commercialInstance: FlaresolverrHttpClient by lazy {
            FlaresolverrHttpClient(port = CommercialProxyConfig.instance.flaresolverrPort)
        }
    }
}

/**
 * Per-container shared state for [FlaresolverrHttpClient]: a browser-serializing semaphore and a
 * session-per-provider map, plus the mutex guarding session creation and removal.
 */
private class FlaresolverrEndpoint(maxConcurrency: Int) {
    val semaphore = Semaphore(maxConcurrency)
    val sessions = ConcurrentHashMap<Hostname, String>()
    val sessionCreationMutex = Mutex()

    /** Requests served by the current session of each provider, driving commercial-proxy session rotation. */
    val requestCounts = ConcurrentHashMap<Hostname, AtomicInteger>()

    /** Last resolved exit IP per provider, so a rotation can be logged as a change rather than a bare value. */
    val exitIps = ConcurrentHashMap<Hostname, String>()

    /** Cloudflare clearance of the current session per provider, where the direct path is available. */
    val clearances = ConcurrentHashMap<Hostname, SessionClearance>()
}

/**
 * Everything needed to fetch a page without the browser: the clearance cookie, the user agent it was issued
 * to, and a client pinned to the exit IP that solved the challenge. Tied to the FlareSolverr session it came
 * from, so a clearance left over from a recycled session is never reused against a different address.
 */
private data class SessionClearance(
    val session: String,
    val cookieHeader: String,
    val userAgent: String,
    val httpClient: HttpClient,
)

/**
 * Whether FlareSolverr served the request. Note that this is its own status and says nothing about the HTTP
 * status of the request to FlareSolverr itself: a failed solve arrives as an HTTP 500 whose body carries the
 * reason, which is why [FlaresolverrHttpClient] has to keep that body (see `retryServerErrors`).
 */
private fun FlaresolverrResponse.isOk(): Boolean = status.equals("ok", ignoreCase = true)

/**
 * Returns this response if FlareSolverr served it, and otherwise throws.
 *
 * Needed because a failed solve is a successfully parsed response rather than an exception, and
 * [FlaresolverrResponseSolution] defaults every field - so returning one to a crawler would hand it an empty
 * body under HTTP status 100 as if the page had genuinely been fetched that way. The message is the one
 * [io.github.manamiproject.modb.app.crawlers.anidb.AnidbCrawler] matches on to skip a single entry instead of
 * losing the whole run.
 */
private fun FlaresolverrResponse.orThrow(hostname: Hostname): FlaresolverrResponse = when {
    isOk() -> this
    else -> throw IllegalStateException("FlareSolverr could not serve [$hostname] - status [$status], message [$message].")
}

/**
 * FlareSolverr reports a Cloudflare refusal of the exit IP in its message rather than with a distinct
 * status, so this is matched on the text. The signature is stable across versions:
 * "Cloudflare has blocked this request. Probably your IP is banned for this site".
 */
private fun FlaresolverrResponse.indicatesBlockedExitIp(): Boolean =
    !isOk()
            && (message.contains("has blocked this request", ignoreCase = true)
            || message.contains("IP is banned", ignoreCase = true))

/**
 * FlareSolverr reports a session it does not know about as an error naming the session. Matched on the
 * message because the API has no distinct status for it.
 */
private fun FlaresolverrResponse.indicatesMissingSession(): Boolean =
    !isOk() && message.contains("session", ignoreCase = true)

private data class FlaresolverrResponse(
    val status: String = EMPTY,
    val message: String = EMPTY,
    val session: String = EMPTY,
    val solution: FlaresolverrResponseSolution = FlaresolverrResponseSolution(),
)

private data class FlaresolverrResponseSolution(
    val status: Int = 100,
    val response: String = EMPTY,
    val userAgent: String = EMPTY,
    val cookies: List<FlaresolverrCookie> = emptyList(),
)

private data class FlaresolverrCookie(
    val name: String = EMPTY,
    val value: String = EMPTY,
)