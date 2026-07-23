package io.github.manamiproject.modb.app.crawlers.simkl

import io.github.manamiproject.modb.app.TestHttpClient
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.test.exceptionExpected
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import java.net.URL
import kotlin.test.Test

internal class DefaultSimklMalIdRedirectResolverTest {

    @Test
    fun `returns the simkl id when the redirect points to an anime page`() = runTest {
        // given
        val testHttpClient = object: HttpClient by TestHttpClient {
            override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse =
                HttpResponse(301, EMPTY, mutableMapOf("location" to listOf("https://simkl.com/anime/37089/cowboy-bebop")))
        }
        val resolver = DefaultSimklMalIdRedirectResolver(httpClient = testHttpClient)

        // when
        val result = resolver.resolve("1")

        // then
        assertThat(result).isEqualTo("37089")
    }

    @Test
    fun `returns null when the redirect points to the homepage`() = runTest {
        // given
        val testHttpClient = object: HttpClient by TestHttpClient {
            override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse =
                HttpResponse(301, EMPTY, mutableMapOf("location" to listOf("https://simkl.com/")))
        }
        val resolver = DefaultSimklMalIdRedirectResolver(httpClient = testHttpClient)

        // when
        val result = resolver.resolve("2")

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `returns null when there is no location header`() = runTest {
        // given
        val testHttpClient = object: HttpClient by TestHttpClient {
            override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse =
                HttpResponse(301, EMPTY, mutableMapOf())
        }
        val resolver = DefaultSimklMalIdRedirectResolver(httpClient = testHttpClient)

        // when
        val result = resolver.resolve("3")

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `requests the correct keyless redirect url`() = runTest {
        // given
        var capturedUrl: URL? = null
        val testHttpClient = object: HttpClient by TestHttpClient {
            override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
                capturedUrl = url
                return HttpResponse(301, EMPTY, mutableMapOf("location" to listOf("https://simkl.com/anime/100/x")))
            }
        }
        val resolver = DefaultSimklMalIdRedirectResolver(httpClient = testHttpClient)

        // when
        resolver.resolve("1535")

        // then
        assertThat(capturedUrl.toString()).isEqualTo("https://api.simkl.com/redirect?to=simkl&mal=1535")
    }

    @Test
    fun `throws if the response is not a redirect`() {
        // given
        val testHttpClient = object: HttpClient by TestHttpClient {
            override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse =
                HttpResponse(403, EMPTY, mutableMapOf())
        }
        val resolver = DefaultSimklMalIdRedirectResolver(httpClient = testHttpClient)

        // when
        val result = exceptionExpected<IllegalStateException> {
            resolver.resolve("1")
        }

        // then
        assertThat(result).hasMessageContaining("possible block or throttle")
    }
}
