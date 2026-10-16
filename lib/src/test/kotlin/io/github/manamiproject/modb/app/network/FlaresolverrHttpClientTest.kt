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
                flaresolverrHttpClient.get(URI("https://anisearch.com/anime/1535").toURL())

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
        fun `creates the session with the commercial proxy for a commercially-routed provider`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val commercialProxyConfig = CommercialProxyConfig(enabledCommercialRegistry(url = "socks5://gw.example.com:7000"))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    tunnelConfig = TunnelConfig(enabledTunnelRegistry()),
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )
                // The commercial endpoint's session map is global, so drop anything an earlier test left in it
                // and only count the session this test creates.
                flaresolverrHttpClient.destroySessions()
                flaresolverr.reset()

                // when
                flaresolverrHttpClient.get(URI("https://anidb.net/anime/1535").toURL())

                // then
                // anidb is in the commercial set (and not the tunnel set), so it exits via the commercial proxy
                // even with the tunnel enabled.
                assertThat(flaresolverr.sessionCreations.single())
                    .isEqualTo("""{"cmd":"sessions.create","proxy":{"url":"socks5://gw.example.com:7000"}}""")

                // Clear the shared commercial-endpoint session map so a later test starts clean.
                flaresolverrHttpClient.destroySessions()
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
    inner class SessionRotationTests {

        @Test
        fun `recycles the session every rotateAfterRequests requests, with a fresh session id each time`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                    url = "http://gw.example.com:7000",
                    username = "user-{session}",
                    password = "pass",
                    rotateAfterRequests = 3,
                ))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )

                // when
                repeat(7) { flaresolverrHttpClient.get(URI("https://anidb.net/anime/$it").toURL()) }

                // then
                // Sessions 1 and 2 each serve exactly 3 requests, so the 4th and 7th trigger a recycle.
                assertThat(flaresolverr.crawlRequests).hasSize(7)
                assertThat(flaresolverr.sessionCreations).hasSize(3)
                assertThat(flaresolverr.sessionDestroys).hasSize(2)

                // One warm-up per session, so the Cloudflare challenge each new exit IP has to clear is never
                // charged to one of the 7 entries.
                assertThat(flaresolverr.warmUpRequests).hasSize(3)

                // The point of recycling: each new session presents credentials the provider has not seen, so
                // it hands out a different residential exit IP. Identical ids would defeat it entirely.
                val sessionIds = flaresolverr.sessionCreations.map {
                    Regex(""""username":"user-([a-z0-9]{8})"""").find(it)!!.groupValues[1]
                }
                assertThat(sessionIds).doesNotHaveDuplicates()

                flaresolverrHttpClient.destroySessions()
            }
        }

        @Test
        fun `never recycles when rotateAfterRequests is zero`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                    url = "http://gw.example.com:7000",
                    username = "user-{session}",
                    password = "pass",
                    rotateAfterRequests = 0,
                ))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )

                // when
                repeat(5) { flaresolverrHttpClient.get(URI("https://anidb.net/anime/$it").toURL()) }

                // then
                assertThat(flaresolverr.sessionCreations).hasSize(1)
                assertThat(flaresolverr.sessionDestroys).isEmpty()

                flaresolverrHttpClient.destroySessions()
            }
        }

        @Test
        fun `never recycles a tunneled provider, whose single residential IP cannot rotate`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    tunnelConfig = TunnelConfig(enabledTunnelRegistry()),
                    commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                        url = "http://gw.example.com:7000",
                        username = "user-{session}",
                        password = "pass",
                        rotateAfterRequests = 2,
                    )),
                    httpClient = flaresolverr,
                )

                // when
                repeat(6) { flaresolverrHttpClient.get(URI("https://anisearch.com/anime/$it").toURL()) }

                // then
                // Recycling here would discard a valid Cloudflare clearance and gain nothing, since the
                // tunnel always exits from the same home IP.
                assertThat(flaresolverr.sessionCreations).hasSize(1)
                assertThat(flaresolverr.sessionDestroys).isEmpty()

                flaresolverrHttpClient.destroySessions()
            }
        }
    }

    @Nested
    @ResourceLock(SESSIONS)
    inner class BlockedExitIpTests {

        @Test
        fun `recycles the session and retries once when the exit IP is blocked`() {
            runTest {
                // given
                // FlareSolverr reports a Cloudflare refusal of the exit IP in its message, with no distinct
                // status. Retrying the same session cannot help - only a different address can.
                val flaresolverr = object: FlaresolverrStub() {
                    var blockNextRequest = true
                    override fun onRequest(body: String): HttpResponse = when {
                        blockNextRequest -> {
                            blockNextRequest = false
                            ok("""{"status":"error","message":"Error solving the challenge. Cloudflare has blocked this request. Probably your IP is banned for this site, check in your web browser."}""")
                        }
                        else -> super.onRequest(body)
                    }
                }
                val commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                    url = "http://gw.example.com:7000",
                    username = "user-{session}",
                    password = "pass",
                    rotateAfterRequests = 50,
                ))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )

                try {
                    // when
                    flaresolverrHttpClient.get(URI("https://anidb.net/anime/1").toURL())

                    // then
                    // Two sessions: the blocked one, then a replacement with a different id and therefore a
                    // different exit IP. The blocked one is destroyed rather than left holding a browser.
                    assertThat(flaresolverr.sessionCreations).hasSize(2)
                    assertThat(flaresolverr.sessionDestroys).hasSize(1)

                    val sessionIds = flaresolverr.sessionCreations.map {
                        Regex(""""username":"user-([a-z0-9]{8})"""").find(it)!!.groupValues[1]
                    }
                    assertThat(sessionIds).doesNotHaveDuplicates()
                } finally {
                    flaresolverrHttpClient.destroySessions()
                }
            }
        }

        @Test
        fun `does not recycle on a block when a new session cannot change the exit IP`() {
            runTest {
                // given
                // No session placeholder in the credentials, so a replacement session would present the same
                // credentials and most likely the same IP. Recycling would pay a ~19s solve for nothing.
                val flaresolverr = object: FlaresolverrStub() {
                    override fun onRequest(body: String): HttpResponse =
                        ok("""{"status":"error","message":"Cloudflare has blocked this request. Probably your IP is banned for this site."}""")
                }
                val commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                    url = "http://gw.example.com:7000",
                    username = "user",
                    password = "pass",
                    rotateAfterRequests = 50,
                ))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )

                try {
                    // when
                    val result = exceptionExpected<IllegalStateException> {
                        flaresolverrHttpClient.get(URI("https://anidb.net/anime/1").toURL())
                    }

                    // then
                    // Fails rather than returning the blocked response: FlaresolverrResponseSolution defaults
                    // every field, so returning it would hand the crawler an empty body under HTTP status 100
                    // as though the page had genuinely been fetched that way.
                    assertThat(result).hasMessageContaining("FlareSolverr could not serve [anidb.net]")
                    assertThat(flaresolverr.sessionCreations).hasSize(1)
                    assertThat(flaresolverr.sessionDestroys).isEmpty()
                } finally {
                    flaresolverrHttpClient.destroySessions()
                }
            }
        }

        @Test
        fun `recycles the session and retries once when a request fails outright`() {
            runTest {
                // given
                // By the time an exception surfaces, the underlying client has already spent its own retries
                // on the same session and therefore the same exit IP. A different address is the only lever
                // left, and without this the run fail-fast aborts.
                // Only the crawl's own request fails. A warm-up failure would never get this far: the
                // warm-up loop absorbs it and draws another exit IP before the crawl sees the session.
                var crawlAttempts = 0
                val flaresolverr = object: FlaresolverrStub() {
                    override fun onRequest(body: String): HttpResponse {
                        if (isWarmUp(body)) {
                            return super.onRequest(body)
                        }

                        crawlAttempts++
                        if (crawlAttempts == 1) {
                            throw IllegalStateException("connection reset")
                        }
                        return super.onRequest(body)
                    }
                }
                val commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                    url = "http://gw.example.com:7000",
                    username = "user-{session}",
                    password = "pass",
                    rotateAfterRequests = 50,
                ))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )
                flaresolverrHttpClient.destroySessions()
                flaresolverr.reset()

                try {
                    // when
                    flaresolverrHttpClient.get(URI("https://anidb.net/anime/1").toURL())

                    // then
                    assertThat(crawlAttempts).isEqualTo(2)
                    assertThat(flaresolverr.sessionCreations).hasSize(2)
                } finally {
                    flaresolverrHttpClient.destroySessions()
                }
            }
        }
    }

    @Nested
    @ResourceLock(SESSIONS)
    inner class SessionWarmUpTests {

        @Test
        fun `clears Cloudflare on a new session before the crawl's first request uses it`() {
            runTest {
                // given
                val flaresolverr = FlaresolverrStub()
                val commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                    url = "http://gw.example.com:7000",
                    username = "user-{session}",
                    password = "pass",
                    rotateAfterRequests = 50,
                ))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )
                flaresolverrHttpClient.destroySessions()
                flaresolverr.reset()

                try {
                    // when
                    flaresolverrHttpClient.get(URI("https://anidb.net/anime/1").toURL())

                    // then
                    // The challenge is paid once per session and whichever request comes first pays it, so it
                    // is spent on the site root here instead of on an arbitrary entry.
                    assertThat(flaresolverr.warmUpRequests).hasSize(1)
                    assertThat(flaresolverr.warmUpRequests.single()).contains(""""url":"https://anidb.net/"""")
                    assertThat(flaresolverr.requests.first()).isEqualTo(flaresolverr.warmUpRequests.single())
                    assertThat(flaresolverr.crawlRequests).hasSize(1)
                } finally {
                    flaresolverrHttpClient.destroySessions()
                }
            }
        }

        @Test
        fun `draws another exit IP when the warm-up cannot clear Cloudflare`() {
            runTest {
                // given
                // The first exit IP cannot solve the challenge, exactly as anidb saw on 2026-10-16. The crawl
                // must never meet that address.
                var warmUps = 0
                val flaresolverr = object: FlaresolverrStub() {
                    override fun onRequest(body: String): HttpResponse = when {
                        !isWarmUp(body) -> super.onRequest(body)
                        ++warmUps == 1 -> ok("""{"status":"error","message":"Error solving the challenge. Timeout after 240.0 seconds."}""")
                        else -> super.onRequest(body)
                    }
                }
                val commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                    url = "http://gw.example.com:7000",
                    username = "user-{session}",
                    password = "pass",
                    rotateAfterRequests = 50,
                ))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )
                flaresolverrHttpClient.destroySessions()
                flaresolverr.reset()

                try {
                    // when
                    val result = flaresolverrHttpClient.get(URI("https://anidb.net/anime/1").toURL())

                    // then
                    // Two sessions created and the unusable one destroyed, so the failure cost an exit IP
                    // rather than the entry - which still comes back successfully.
                    assertThat(warmUps).isEqualTo(2)
                    assertThat(flaresolverr.sessionCreations).hasSize(2)
                    assertThat(flaresolverr.sessionDestroys).hasSize(1)
                    assertThat(flaresolverr.crawlRequests).hasSize(1)
                    assertThat(result.code).isEqualTo(200)
                } finally {
                    flaresolverrHttpClient.destroySessions()
                }
            }
        }

        @Test
        fun `gives up warming up after a bounded number of exit IPs and lets the crawl continue`() {
            runTest {
                // given
                // Every address fails the warm-up. Retrying for ever would stall the run, so the crawl goes
                // ahead on the last session: at worst that costs the one entry rather than the whole crawl.
                val flaresolverr = object: FlaresolverrStub() {
                    override fun onRequest(body: String): HttpResponse = when {
                        isWarmUp(body) -> ok("""{"status":"error","message":"Error solving the challenge. Timeout after 240.0 seconds."}""")
                        else -> super.onRequest(body)
                    }
                }
                val commercialProxyConfig = CommercialProxyConfig(rotatingCommercialRegistry(
                    url = "http://gw.example.com:7000",
                    username = "user-{session}",
                    password = "pass",
                    rotateAfterRequests = 50,
                ))
                val flaresolverrHttpClient = FlaresolverrHttpClient(
                    appConfig = TestAppConfig,
                    commercialProxyConfig = commercialProxyConfig,
                    port = commercialProxyConfig.flaresolverrPort,
                    httpClient = flaresolverr,
                )
                flaresolverrHttpClient.destroySessions()
                flaresolverr.reset()

                try {
                    // when
                    val result = flaresolverrHttpClient.get(URI("https://anidb.net/anime/1").toURL())

                    // then
                    assertThat(flaresolverr.sessionCreations).hasSize(3)
                    assertThat(flaresolverr.crawlRequests).hasSize(1)
                    assertThat(result.code).isEqualTo(200)
                } finally {
                    flaresolverrHttpClient.destroySessions()
                }
            }
        }

        @Test
        fun `does not warm up a session for a provider which exits directly`() {
            runTest {
                // given
                // No proxy, so no Cloudflare challenge to pre-solve and nothing to gain from an extra request.
                val flaresolverr = FlaresolverrStub()
                val flaresolverrHttpClient = FlaresolverrHttpClient(appConfig = TestAppConfig, httpClient = flaresolverr)
                flaresolverrHttpClient.destroySessions()
                flaresolverr.reset()

                try {
                    // when
                    flaresolverrHttpClient.get(URI("https://direct-no-warmup.example/anime/1").toURL())

                    // then
                    assertThat(flaresolverr.warmUpRequests).isEmpty()
                    assertThat(flaresolverr.crawlRequests).hasSize(1)
                } finally {
                    flaresolverrHttpClient.destroySessions()
                }
            }
        }
    }

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

    /**
     * The requests a crawler made, i.e. [requests] without the session warm-up.
     *
     * A new session fetches the site root once to clear Cloudflare before the crawl uses it, so assertions
     * about what the crawl asked for have to exclude it.
     */
    val crawlRequests: List<String>
        get() = requests.filterNot { SITE_ROOT_URL.containsMatchIn(it) }

    /** The warm-up requests, one per session which cleared Cloudflare before being handed to the crawl. */
    val warmUpRequests: List<String>
        get() = requests.filter { SITE_ROOT_URL.containsMatchIn(it) }

    /** Forgets everything recorded so far, so a test can ignore setup traffic. */
    fun reset() {
        sessionCreations.clear()
        sessionDestroys.clear()
        requests.clear()
    }

    protected fun ok(json: String) = HttpResponse(code = 200, body = json.toByteArray())

    /** Matches a URL whose path is exactly `/`, which is what the warm-up requests and nothing else does. */
    protected fun isWarmUp(body: String): Boolean = SITE_ROOT_URL.containsMatchIn(body)

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

/**
 * A [ConfigRegistry] with the commercial proxy switched on, credentials set and an explicit
 * `rotateAfterRequests`, for exercising session rotation.
 */
private fun rotatingCommercialRegistry(
    url: String,
    username: String,
    password: String,
    rotateAfterRequests: Int,
) = object : ConfigRegistry by TestConfigRegistry {
    override fun boolean(key: String): Boolean = true
    override fun string(key: String): String? = when {
        key.endsWith(".url") -> url
        key.endsWith(".username") -> username
        key.endsWith(".password") -> password
        else -> null
    }
    override fun int(key: String): Int? = null

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> list(key: String): List<T>? = when {
        // Per-provider now, so the threshold arrives as a 'hostname=limit' list rather than a bare int.
        key.endsWith(".rotateAfterRequests") -> listOf("anidb.net=$rotateAfterRequests") as List<T>?
        else -> null
    }
}

/**
 * A [ConfigRegistry] with the commercial proxy switched on and [url] set (no credentials), everything else at
 * its default - so it routes the default provider (anidb) with just a URL.
 */
private fun enabledCommercialRegistry(url: String) = object : ConfigRegistry by TestConfigRegistry {
    override fun boolean(key: String): Boolean = true
    override fun string(key: String): String? = if (key.endsWith(".url")) url else null
    override fun int(key: String): Int? = null
    override fun <T : Any> list(key: String): List<T>? = null
}

private val SITE_ROOT_URL = Regex("\"url\"\\s*:\\s*\"https?://[^/\"]+/\"")
