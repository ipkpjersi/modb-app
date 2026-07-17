package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.httpclient.APPLICATION_JSON
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.core.json.Json
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.URL
import kotlin.time.Duration
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration

internal class FlaresolverrHttpClient(
    private val appConfig: Config = AppConfig.instance,
    private val flaresolverrConfig: FlaresolverrConfig = FlaresolverrConfig.instance,
    private val tunnelConfig: TunnelConfig = TunnelConfig.instance,
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

    private val flaresolverrUrl: URL = URI("http://localhost:${flaresolverrConfig.port}/v1").toURL()
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
    ): HttpResponse = sendToFlaresolverr(url) {
        """
            {
              "cmd": "request.post",
              "url": "$url",
              "maxTimeout": $maxTimeout,
              "postData": "${requestBody.body}"${proxyProperty(url)}
            }
        """.trimIndent()
    }

    override suspend fun get(
        url: URL,
        headers: Map<String, Collection<String>>,
    ): HttpResponse = sendToFlaresolverr(url) {
        """
            {
              "cmd": "request.get",
              "url": "$url",
              "maxTimeout": $maxTimeout${proxyProperty(url)}
            }
        """.trimIndent()
    }

    /**
     * FlareSolverr fetches [targetUrl] from inside its own container, so a provider is only routed through
     * the tunnel if FlareSolverr itself is told to use it. Setting a proxy on the [httpClient] below would
     * achieve nothing - that client only ever talks to FlareSolverr on localhost.
     *
     * Returns the `proxy` property to append to the request payload (leading comma included), or [EMPTY]
     * for provider(s) which keep the direct datacenter path.
     */
    private fun proxyProperty(targetUrl: URL): String = when {
        tunnelConfig.isTunneled(targetUrl.host) -> ""","proxy":{"url":"${tunnelConfig.socksUrl()}"}"""
        else -> EMPTY
    }

    /**
     * Sends a command to FlareSolverr for [targetUrl] and unwraps its solution.
     *
     * All FlareSolverr traffic must be sent as [APPLICATION_JSON] - any other content type makes FlareSolverr reject
     * the body with "Request parameter 'cmd' is mandatory" (HTTP 500), so both [post] and [get] funnel through here.
     *
     * Every failure is logged against [targetUrl] (and includes FlareSolverr's own status/message), so the crawl log
     * alone identifies which provider's request failed - the underlying [httpClient] only ever sees the opaque
     * `localhost:<port>/v1` endpoint and cannot attribute the failure to a provider.
     */
    private suspend fun sendToFlaresolverr(targetUrl: URL, buildBody: () -> String): HttpResponse = withOperationTimeout {
        flaresolverrSemaphore.withPermit {
            val content = try {
                val postResponse = httpClient.post(
                    url = flaresolverrUrl,
                    requestBody = RequestBody(mediaType = APPLICATION_JSON, body = buildBody()),
                )
                Json.parseJson<FlaresolverrResponse>(postResponse.bodyAsStream())!!
            } catch (throwable: Throwable) {
                log.warn { "FlareSolverr request for [$targetUrl] sent via [$flaresolverrUrl] failed: [${throwable.message}]." }
                throw throwable
            }

            if (!content.status.equals("ok", ignoreCase = true)) {
                log.warn {
                    "FlareSolverr request for [$targetUrl] sent via [$flaresolverrUrl] was not successful - FlareSolverr status [${content.status}], message [${content.message}]."
                }
            }

            HttpResponse(
                code = content.solution.status,
                body = content.solution.response,
            )
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
         * Shared across all instances so that access to the single FlareSolverr container is serialized
         * globally - every crawler creates its own [FlaresolverrHttpClient], but they must not hit FlareSolverr
         * concurrently. Permit count comes from [FlaresolverrConfig.maxConcurrency].
         */
        private val flaresolverrSemaphore = Semaphore(FlaresolverrConfig.instance.maxConcurrency)

        /**
         * Singleton of [FlaresolverrHttpClient]
         * @since 1.0.0
         */
        val instance: FlaresolverrHttpClient by lazy { FlaresolverrHttpClient() }
    }
}

private data class FlaresolverrResponse(
    val status: String = EMPTY,
    val message: String = EMPTY,
    val solution: FlaresolverrResponseSolution = FlaresolverrResponseSolution(),
)

private data class FlaresolverrResponseSolution(
    val status: Int = 100,
    val response: String = EMPTY,
)