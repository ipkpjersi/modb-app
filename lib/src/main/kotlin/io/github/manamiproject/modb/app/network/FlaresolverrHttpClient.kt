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
import java.net.URI
import java.net.URL

internal class FlaresolverrHttpClient(
    private val appConfig: Config = AppConfig.instance,
    private val httpClient: HttpClient = DefaultHttpClient(isTestContext = appConfig.isTestContext()),
    private val flaresolverrConfig: FlaresolverrConfig = FlaresolverrConfig.instance,
): HttpClient {

    private val flaresolverrUrl: URL = URI("http://localhost:${flaresolverrConfig.port}/v1").toURL()
    private val maxTimeout: Int = flaresolverrConfig.maxTimeout

    override suspend fun post(
        url: URL,
        requestBody: RequestBody,
        headers: Map<String, Collection<String>>,
    ): HttpResponse = flaresolverrSemaphore.withPermit {
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

    override suspend fun get(
        url: URL,
        headers: Map<String, Collection<String>>,
    ): HttpResponse = flaresolverrSemaphore.withPermit {
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

    override fun addRetryCases(vararg retryCases: RetryCase): HttpClient {
        httpClient.addRetryCases(*retryCases)
        return this
    }

    companion object {
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