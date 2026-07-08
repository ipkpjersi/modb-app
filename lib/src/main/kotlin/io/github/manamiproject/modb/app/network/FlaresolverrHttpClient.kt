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
    private val httpClient: HttpClient = DefaultHttpClient(
        // FlareSolverr is allowed up to maxTimeout ms to solve a challenge, so our own socket read timeout must
        // exceed that - otherwise a legitimate slow solve trips a spurious SocketTimeoutException and gets retried
        // against the still-busy (single-browser) container.
        readTimeoutInSeconds = (flaresolverrConfig.maxTimeout / 1000L) + READ_TIMEOUT_BUFFER_SECONDS,
        isTestContext = appConfig.isTestContext(),
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
    ): HttpResponse = withOperationTimeout {
        flaresolverrSemaphore.withPermit {
            val postResponse = httpClient.post(
                url = flaresolverrUrl,
                requestBody = RequestBody(
                    mediaType = "application/x-www-form-urlencoded",
                    body = """
                        {
                          "cmd": "request.post",
                          "url": "$url",
                          "maxTimeout": $maxTimeout,
                          "postData": "${requestBody.body}"
                        }
                    """.trimIndent(),
                ),
            )
            val content = Json.parseJson<FlaresolverrResponse>(postResponse.bodyAsStream())!!
            HttpResponse(
                code = content.solution.status,
                body = content.solution.response,
            )
        }
    }

    override suspend fun get(
        url: URL,
        headers: Map<String, Collection<String>>,
    ): HttpResponse = withOperationTimeout {
        flaresolverrSemaphore.withPermit {
            val postResponse = httpClient.post(
                url = flaresolverrUrl,
                requestBody = RequestBody(
                    mediaType = APPLICATION_JSON,
                    body = """
                        {
                          "cmd": "request.get",
                          "url": "$url",
                          "maxTimeout": $maxTimeout
                        }
                    """.trimIndent(),
                ),
            )
            val content = Json.parseJson<FlaresolverrResponse>(postResponse.bodyAsStream())!!
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
    val solution: FlaresolverrResponseSolution = FlaresolverrResponseSolution(),
)

private data class FlaresolverrResponseSolution(
    val status: Int = 100,
    val response: String = EMPTY,
)