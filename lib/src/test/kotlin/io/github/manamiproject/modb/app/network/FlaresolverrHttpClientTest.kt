package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.app.TestHttpClient
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.HttpResponseRetryCase
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.core.httpclient.ThrowableRetryCase
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.tempDirectory
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.URI
import java.net.URL
import kotlin.test.Test


private const val SESSIONS = "flaresolverr-sessions"

internal class FlaresolverrHttpClientTest {

    /**
     * Sessions live in a companion-object map shared by every instance (one session per provider, however
     * many crawlers it has). This suite runs tests concurrently, so the classes touching that map must not
     * run at the same time - destroySessions() in particular clears it wholesale.
     */
    @Nested
    @ResourceLock(SESSIONS)
    inner class GetTests {

        @Test
        fun `correctly creates get request`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub(sessionId = "session-get")
                val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = flaresolverr)

                // when
                flaresolverrHttpClient.get(URI("http://get-request.example").toURL())

                // then
                assertThat(flaresolverr.invokedUrl).isEqualTo("http://localhost:8191/v1")
                assertThat(flaresolverr.lastRequest).isEqualTo("""
                    RequestBody(mediaType=application/json, body={
                      "cmd": "request.get",
                      "url": "http://get-request.example",
                      "maxTimeout": 120000,
                      "session": "session-get"
                    })
                """.trimIndent())
            }
        }

        @Test
        fun `creates a session once per provider and reuses it for every subsequent request`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = flaresolverr)

                // when
                flaresolverrHttpClient.get(URI("http://reuse.example/1").toURL())
                flaresolverrHttpClient.get(URI("http://reuse.example/2").toURL())
                flaresolverrHttpClient.get(URI("http://reuse.example/3").toURL())

                // then
                // Reusing the session is the entire point: it is what makes FlareSolverr solve the Cloudflare
                // challenge once instead of on every single entry.
                assertThat(flaresolverr.sessionCreations).hasSize(1)
                assertThat(flaresolverr.requests).hasSize(3)
            }
        }

        @Test
        fun `creates the session with the tunnel proxy for a provider which needs a residential exit IP`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    tunnelConfig = TunnelConfig(enabledTunnelRegistry()),
                    httpClient = flaresolverr,
                )

                // when
                flaresolverrHttpClient.get(URI("https://anidb.net/anime/1535").toURL())

                // then
                // The proxy belongs on the session, not the request: a session keeps the proxy it was created
                // with, so every request on it exits through the tunnel.
                assertThat(flaresolverr.sessionCreations.single())
                    .isEqualTo("""{"cmd":"sessions.create","proxy":{"url":"socks5://172.17.0.1:1080"}}""")
            }
        }

        @Test
        fun `creates the session without a proxy for a provider which keeps the direct datacenter path`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    tunnelConfig = TunnelConfig(enabledTunnelRegistry()),
                    httpClient = flaresolverr,
                )

                // when
                flaresolverrHttpClient.get(URI("https://myanimelist.net/anime/1535").toURL())

                // then
                assertThat(flaresolverr.sessionCreations.single()).isEqualTo("""{"cmd":"sessions.create"}""")
                assertThat(flaresolverr.requests.single()).doesNotContain("proxy")
            }
        }

        @Test
        fun `creates a new session and retries once if FlareSolverr reports the session is gone`() {
            runTest {
                // given
                // Fails the first request with FlareSolverr's "session does not exist" error, then behaves.
                val flaresolverr = object : FlaresolverrStub() {
                    private var failed = false
                    override fun onRequest(body: String): HttpResponse = when {
                        !failed -> {
                            failed = true
                            ok("""{"status":"error","message":"Error: This session does not exist."}""")
                        }
                        else -> super.onRequest(body)
                    }
                }
                val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = flaresolverr)

                // when
                val result = flaresolverrHttpClient.get(URI("http://expired-session.example").toURL())

                // then
                // A lost session is transient, not a provider blocking us, so it must not abort a multi-day run.
                assertThat(flaresolverr.sessionCreations).hasSize(2)
                assertThat(flaresolverr.requests).hasSize(2)
                assertThat(result.code).isEqualTo(200)
            }
        }

        @Test
        fun `throws if the session cannot be created`() {
            runTest {
                // given
                val flaresolverr = object : FlaresolverrStub() {
                    override fun onSessionCreate(body: String): HttpResponse =
                        ok("""{"status":"error","message":"Error: unable to start browser."}""")
                }
                val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = flaresolverr)

                // when
                val result = exceptionExpected<IllegalStateException> {
                    flaresolverrHttpClient.get(URI("http://broken-session.example").toURL())
                }

                // then
                assertThat(result).hasMessageContaining("Unable to create FlareSolverr session for [broken-session.example]")
            }
        }
    }

    /**
     * Sessions live in a companion-object map shared by every instance (one session per provider, however
     * many crawlers it has). This suite runs tests concurrently, so the classes touching that map must not
     * run at the same time - destroySessions() in particular clears it wholesale.
     */
    @Nested
    @ResourceLock(SESSIONS)
    inner class PostTests {

        @Test
        fun `correctly creates post request`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub(sessionId = "session-post")
                val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = flaresolverr)

                // when
                flaresolverrHttpClient.post(
                    url = URI("http://post-request.example").toURL(),
                    requestBody = RequestBody(
                        mediaType = "application/x-www-form-urlencoded",
                        body = "a=b&c=d"
                    ),
                )

                // then
                assertThat(flaresolverr.invokedUrl).isEqualTo("http://localhost:8191/v1")
                assertThat(flaresolverr.lastRequest).isEqualTo("""
                    RequestBody(mediaType=application/json, body={
                      "cmd": "request.post",
                      "url": "http://post-request.example",
                      "maxTimeout": 120000,
                      "postData": "a=b&c=d",
                      "session": "session-post"
                    })
                """.trimIndent())
            }
        }
    }

    /**
     * Sessions live in a companion-object map shared by every instance (one session per provider, however
     * many crawlers it has). This suite runs tests concurrently, so the classes touching that map must not
     * run at the same time - destroySessions() in particular clears it wholesale.
     */
    @Nested
    @ResourceLock(SESSIONS)
    inner class DestroySessionsTests {

        @Test
        fun `destroys every session created by this run`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = flaresolverr)
                // The session map is global, so drop anything an earlier test left in it and only count ours.
                flaresolverrHttpClient.destroySessions()
                flaresolverr.reset()
                flaresolverrHttpClient.get(URI("http://destroy-a.example").toURL())
                flaresolverrHttpClient.get(URI("http://destroy-b.example").toURL())

                // when
                flaresolverrHttpClient.destroySessions()

                // then
                // Each session holds a browser open and startFlaresolverr reuses an existing container, so a
                // leaked session would outlive the run.
                assertThat(flaresolverr.sessionDestroys).hasSize(2)
            }
        }

        @Test
        fun `creates a fresh session after the previous ones were destroyed`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = flaresolverr)
                flaresolverrHttpClient.get(URI("http://recreate-after-destroy.example").toURL())
                flaresolverrHttpClient.destroySessions()

                // when
                flaresolverrHttpClient.get(URI("http://recreate-after-destroy.example").toURL())

                // then
                assertThat(flaresolverr.sessionCreations).hasSize(2)
            }
        }
    }

    @Nested
    inner class AddRetryCasesTests {

        @Test
        fun `adding a retry cases just delegates it to the internal HttpClient`() {
            // given
            val invocations = mutableListOf<RetryCase>()
            val testHttpClient = object : HttpClient by TestHttpClient {
                override fun addRetryCases(vararg retryCases: RetryCase): HttpClient {
                    invocations.addAll(retryCases)
                    return this
                }
            }
            val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = testHttpClient)

            val r1 = HttpResponseRetryCase { true }
            val r2 = ThrowableRetryCase { true }

            // when
            flaresolverrHttpClient.addRetryCases(r1, r2)

            // then
            assertThat(invocations).containsExactlyInAnyOrder(r1, r2)
        }
    }

    @Nested
    inner class CompanionObjectTests {

        @Test
        fun `instance property always returns same instance`() {
            tempDirectory {
                // given
                val previous = FlaresolverrHttpClient.instance

                // when
                val result = FlaresolverrHttpClient.instance

                // then
                assertThat(result).isExactlyInstanceOf(FlaresolverrHttpClient::class.java)
                assertThat(result === previous).isTrue()
            }
        }
    }
}

/**
 * Stands in for FlareSolverr: answers `sessions.create` with [sessionId], `sessions.destroy` with an ack,
 * and anything else with a successful solution. Records every body so a test can assert what was sent.
 *
 * Each test must use its OWN hostname. Sessions are cached in a companion-object map shared by every
 * instance (so that one provider ends up with one session no matter how many crawlers it has), which means a
 * hostname reused across tests would silently pick up the earlier test's session and never send
 * `sessions.create`.
 */
private open class FlaresolverrStub(private val sessionId: String = "test-session"): HttpClient by TestHttpClient {

    var invokedUrl = EMPTY

    /** Raw bodies, split by command. */
    val sessionCreations = mutableListOf<String>()
    val sessionDestroys = mutableListOf<String>()
    val requests = mutableListOf<String>()

    /** The last `request.*` as [RequestBody.toString], which is what the payload assertions compare against. */
    var lastRequest = EMPTY
        private set

    /** Forgets everything recorded so far, so a test can ignore setup traffic. */
    fun reset() {
        sessionCreations.clear()
        sessionDestroys.clear()
        requests.clear()
    }

    protected fun ok(json: String) = HttpResponse(code = 200, body = json.toByteArray())

    protected open fun onSessionCreate(body: String): HttpResponse = ok("""{"status":"ok","session":"$sessionId"}""")

    protected open fun onRequest(body: String): HttpResponse =
        ok("""{"status":"ok","message":"Challenge not detected!","solution":{"status":200,"response":"<html></html>"}}""")

    final override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
        invokedUrl = url.toString()
        val body = requestBody.body

        return when {
            body.contains("sessions.create") -> {
                sessionCreations.add(body)
                onSessionCreate(body)
            }
            body.contains("sessions.destroy") -> {
                sessionDestroys.add(body)
                ok("""{"status":"ok","message":"The session has been removed."}""")
            }
            else -> {
                requests.add(body)
                lastRequest = requestBody.toString()
                onRequest(body)
            }
        }
    }
}

/**
 * A [ConfigRegistry] with the tunnel switched on and everything else left at its default.
 */
private fun enabledTunnelRegistry() = object : ConfigRegistry by TestConfigRegistry {
    override fun boolean(key: String): Boolean = true
    override fun string(key: String): String? = null
    override fun int(key: String): Int? = null
    override fun <T : Any> list(key: String): List<T>? = null
}
