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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
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
    ),
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
    private suspend fun <T> withOperationTimeout(block: suspend () -> T): T = when {
        appConfig.isTestContext() -> block()
        else -> withTimeout(operationTimeout) { block() }
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
    ): HttpResponse = sendToFlaresolverr(url) { session ->
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
        endpoint.sessions[hostname]?.let { return it }

        // Double-checked under the lock: several crawlers hit the same provider concurrently (e.g. the anidb
        // crawler and its highest-id detector), and without this they would each create a session, leaking
        // every one but the last.
        return endpoint.sessionCreationMutex.withLock {
            endpoint.sessions[hostname] ?: createSession(hostname).also { endpoint.sessions[hostname] = it }
        }
    }

    /**
     * The `proxy` is set here rather than on each request: a session keeps the proxy it was created with, so
     * a request carrying only `session` still exits through its proxy (verified). Setting a proxy on the
     * [httpClient] below would achieve nothing - that client only ever talks to FlareSolverr on localhost.
     *
     * The tunnel and commercial sets are mutually exclusive (enforced by checkCommercialProxy), so at most one
     * branch matches.
     */
    private suspend fun createSession(hostname: Hostname): String {
        val (proxy, via) = when {
            commercialProxyConfig.isRouted(hostname) -> commercialProxyConfig.proxyJson() to " via the commercial proxy"
            tunnelConfig.isTunneled(hostname) -> ""","proxy":{"url":"${tunnelConfig.socksUrl()}"}""" to " via the tunnel"
            else -> EMPTY to EMPTY
        }

        val content = execute(
            targetUrl = hostname,
            body = """{"cmd":"sessions.create"$proxy}""",
        )

        check(content.status.equals("ok", ignoreCase = true) && content.session.isNotBlank()) {
            "Unable to create FlareSolverr session for [$hostname] - status [${content.status}], message [${content.message}]."
        }

        log.info { "Created FlareSolverr session [${content.session}] for [$hostname]$via." }

        return content.session
    }

    /**
     * Sends a command to FlareSolverr for [targetUrl] and unwraps its solution.
     *
     * Every failure is logged against [targetUrl] (and includes FlareSolverr's own status/message), so the crawl log
     * alone identifies which provider's request failed - the underlying [httpClient] only ever sees the opaque
     * `localhost:<port>/v1` endpoint and cannot attribute the failure to a provider.
     */
    private suspend fun sendToFlaresolverr(targetUrl: URL, buildBody: (String) -> String): HttpResponse {
        val hostname = targetUrl.host.lowercase().removePrefix("www.")
        val content = withSessionRetry(hostname) { session ->
            execute(targetUrl.toString(), buildBody(session))
        }

        if (!content.status.equals("ok", ignoreCase = true)) {
            log.warn {
                "FlareSolverr request for [$targetUrl] sent via [$flaresolverrUrl] was not successful - FlareSolverr status [${content.status}], message [${content.message}]."
            }
        }

        return HttpResponse(
            code = content.solution.status,
            body = content.solution.response,
        )
    }

    /**
     * Runs [block] with the session for [hostname], recreating it once if FlareSolverr reports the session is
     * gone.
     *
     * A session can be lost mid-run (a crashed browser, or a container restarted underneath us). That is a
     * recoverable, transient fault rather than a provider blocking us, so it must not abort a multi-day crawl
     * - unlike a genuine block, which still fails fast.
     */
    private suspend fun withSessionRetry(
        hostname: Hostname,
        block: suspend (String) -> FlaresolverrResponse,
    ): FlaresolverrResponse {
        val result = block(sessionFor(hostname))

        if (!result.indicatesMissingSession()) {
            return result
        }

        log.warn { "FlareSolverr session for [$hostname] is gone - [${result.message}]. Creating a new one and retrying once." }
        endpoint.sessionCreationMutex.withLock { endpoint.sessions.remove(hostname) }

        return block(sessionFor(hostname))
    }

    /**
     * Performs one FlareSolverr round-trip. Holds this endpoint's semaphore for exactly the duration of the
     * call, since the container has a single browser and returns HTTP 500 when hit concurrently. Each
     * container has its own semaphore, so the two containers run in parallel.
     *
     * All FlareSolverr traffic must be sent as [APPLICATION_JSON] - any other content type makes FlareSolverr reject
     * the body with "Request parameter 'cmd' is mandatory" (HTTP 500).
     */
    private suspend fun execute(targetUrl: String, body: String): FlaresolverrResponse = withOperationTimeout {
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
            endpoint.sessions.forEach { (hostname, session) ->
                runCatching {
                    execute(hostname, """{"cmd":"sessions.destroy","session":"$session"}""")
                }.onSuccess {
                    log.info { "Destroyed FlareSolverr session for [$hostname]." }
                }.onFailure {
                    // Best effort: the run is over, and a session we cannot destroy dies with the container.
                    log.warn { "Unable to destroy FlareSolverr session for [$hostname]: [${it.message}]." }
                }
            }
            endpoint.sessions.clear()
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
}

/**
 * FlareSolverr reports a session it does not know about as an error naming the session. Matched on the
 * message because the API has no distinct status for it.
 */
private fun FlaresolverrResponse.indicatesMissingSession(): Boolean =
    !status.equals("ok", ignoreCase = true) && message.contains("session", ignoreCase = true)

private data class FlaresolverrResponse(
    val status: String = EMPTY,
    val message: String = EMPTY,
    val session: String = EMPTY,
    val solution: FlaresolverrResponseSolution = FlaresolverrResponseSolution(),
)

private data class FlaresolverrResponseSolution(
    val status: Int = 100,
    val response: String = EMPTY,
)