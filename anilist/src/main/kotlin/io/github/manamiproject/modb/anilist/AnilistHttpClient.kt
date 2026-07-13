package io.github.manamiproject.modb.anilist

import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.extensions.neitherNullNorBlank
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URL

/**
 * Anilist specific implementation of a [HttpClient] which handles the renewal of the token under the hood.
 * A response code of 403 means that the token is outdated. In that case the token is renewed and the request is
 * retried. Requests are performed concurrently which means that multiple requests can run into a 403 for the very
 * same outdated token. Renewal is therefore synchronized and coalesced so that only a single new token is fetched
 * and shared instead of each request overwriting the token of the others.
 * @since 5.1.1
 * @property delegate HttpClient which is used under the hood.
 * @property anilistTokenRetriever Fetches a new token for the CSRF.
 * @property anilistTokenRepository Keeps the current CSRF token in-memory.
 */
public class AnilistHttpClient(
    private val delegate: HttpClient = DefaultHttpClient(),
    private val anilistTokenRetriever: AnilistTokenRetriever = AnilistDefaultTokenRetriever.instance,
    private val anilistTokenRepository: AnilistTokenRepository = AnilistDefaultTokenRepository,
): HttpClient by delegate {

    override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
        var currentHeaders = headers
        var response = delegate.post(url, requestBody, currentHeaders)

        var attempt = 1
        while (response.code == 403 && attempt <= MAX_TOKEN_RENEWAL_ATTEMPTS) {
            response.close()
            currentHeaders = renewTokenInHeaders(currentHeaders, attempt)
            response = delegate.post(url, requestBody, currentHeaders)
            attempt++
        }

        return response
    }

    override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
        var currentHeaders = headers
        var response = delegate.get(url, currentHeaders)

        var attempt = 1
        while (response.code == 403 && attempt <= MAX_TOKEN_RENEWAL_ATTEMPTS) {
            response.close()
            currentHeaders = renewTokenInHeaders(currentHeaders, attempt)
            response = delegate.get(url, currentHeaders)
            attempt++
        }

        return response
    }

    private suspend fun renewTokenInHeaders(headers: Map<String, Collection<String>>, attempt: Int): Map<String, Collection<String>> {
        log.warn { "Anilist responds with 403. Refreshing token (attempt $attempt of $MAX_TOKEN_RENEWAL_ATTEMPTS)." }

        // The token is still rejected although it has just been renewed. Anilist rate limits in that case, so back off
        // before hammering it with the next attempt.
        if (attempt > 1) {
            delay(random(MIN_WAIT_IN_MILLIS, MAX_WAIT_IN_MILLIS) * (attempt - 1))
        }

        val tokenUsedForRequest = tokenFromHeaders(headers)

        val token = tokenRenewalLock.withLock {
            val currentToken = anilistTokenRepository.token
            val hasBeenRenewedByOtherRequest = tokenUsedForRequest != null
                    && currentToken != tokenUsedForRequest
                    && currentToken.cookie.neitherNullNorBlank()

            when {
                hasBeenRenewedByOtherRequest -> {
                    log.info { "Anilist token has already been renewed by a concurrent request." }
                    currentToken
                }
                else -> {
                    anilistTokenRetriever.retrieveToken().also {
                        anilistTokenRepository.token = it
                        log.info { "Anilist token has been renewed." }
                    }
                }
            }
        }

        return HashMap(headers).apply {
            put("cookie", listOf(token.cookie))
            put("x-csrf-token", listOf(token.csrfToken))
        }
    }

    /**
     * The token which the request actually used. It is taken from the headers instead of the repository, because the
     * repository may already contain a newer token fetched by a concurrent request.
     */
    private fun tokenFromHeaders(headers: Map<String, Collection<String>>): AnilistToken? {
        val cookie = headers.entries.firstOrNull { it.key.equals("cookie", ignoreCase = true) }?.value?.firstOrNull() ?: EMPTY
        val csrfToken = headers.entries.firstOrNull { it.key.equals("x-csrf-token", ignoreCase = true) }?.value?.firstOrNull() ?: EMPTY

        return when {
            cookie.neitherNullNorBlank() && csrfToken.neitherNullNorBlank() -> AnilistToken(cookie, csrfToken)
            else -> null
        }
    }

    public companion object {
        private val log by LoggerDelegate()

        /**
         * Number of times a request is retried with a renewed token before the response is passed on as-is.
         */
        private const val MAX_TOKEN_RENEWAL_ATTEMPTS = 3

        private const val MIN_WAIT_IN_MILLIS = 5000
        private const val MAX_WAIT_IN_MILLIS = 10000

        /**
         * Renewal of the token is guarded process wide, because the token repository is shared by all instances.
         */
        private val tokenRenewalLock = Mutex()

        /**
         * Singleton of [AnilistHttpClient]
         * @since 1.0.0
         */
        public val instance: AnilistHttpClient by lazy { AnilistHttpClient() }
    }
}