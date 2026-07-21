package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.ConfigRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.URI
import kotlin.test.Test

/**
 * Manual integration test. Drives the real [FlaresolverrHttpClient] against the live FlareSolverr container
 * and the live reverse SSH tunnel, hitting anidb and simkl for real.
 *
 * Disabled because it needs infrastructure no build machine has: a running FlareSolverr container, a working
 * tunnel to a residential connection, and network access to provider(s) that ban datacenter IPs. It also
 * takes ~40s.
 *
 * **To run it:** comment out the `@Disabled` below, make sure `scripts/tunnel/check-tunnel.sh` passes, then:
 *
 * ```
 * ./gradlew :lib:test --tests "*LiveSessionSmokeTest*" --rerun-tasks -i
 * ```
 *
 * Use it to answer "is the tunnel actually working end to end, and is the session actually being reused?"
 * without reasoning from first principles. A passing run proves, in one shot: the tunnel carries traffic to
 * an IP-banned provider, FlareSolverr solves the challenge through it, the session is reused afterwards (the
 * ~10x speedup), each provider gets its own session, and the sessions are reclaimed at the end.
 *
 * Reference numbers from 2026-07-17 (first full verification):
 * ```
 * anidb/1535 -> http=200 bytes=147874 in 19295ms   <- creates session, solves challenge
 * anidb/23   -> http=200 bytes=654781 in  3132ms   <- reused
 * anidb/17   -> http=200 bytes=400158 in  1940ms   <- reused
 * anidb/30   -> http=200 bytes=678090 in  1213ms   <- reused
 * anidb/44   -> http=200 bytes=246448 in   858ms   <- reused
 * simkl      -> http=200 bytes=671510 in 10830ms   <- its own session, its own challenge
 * ```
 */
@Disabled("Manual: needs the live FlareSolverr container and the reverse SSH tunnel. See the KDoc to run it.")
@ResourceLock("flaresolverr-sessions")
internal class LiveSessionSmokeTest {

    @Test
    fun `solves the challenge once through the tunnel and reuses the session afterwards`() = runBlocking {
        // given
        val appConfig = object : Config by TestAppConfig {
            // Real timeouts and retry behaviour rather than the test-context shortcuts. Safe because this
            // uses runBlocking (real clock); runTest's virtual clock would trip withOperationTimeout.
            override fun isTestContext(): Boolean = false
        }
        val client = FlaresolverrHttpClient(
            appConfig = appConfig,
            tunnelConfig = TunnelConfig(enabledTunnelRegistryForLiveTest()),
        )

        // when
        // anidb is banned on this host's datacenter IP, so a 200 here can only come via the tunnel.
        val timings = listOf(1535, 23, 17, 30, 44).map { id ->
            val start = System.currentTimeMillis()
            val response = client.get(URI("https://anidb.net/anime/$id").toURL())
            val elapsed = System.currentTimeMillis() - start
            val body = response.bodyAsString()

            println("  anidb/$id -> http=${response.code} bytes=${body.length} in ${elapsed}ms")

            assertThat(response.code).describedAs("anidb/$id is IP-banned without the tunnel").isEqualTo(200)
            assertThat(body).describedAs("anidb/$id returned something that is not anidb").containsIgnoringCase("anidb")

            elapsed
        }

        val secondProviderStart = System.currentTimeMillis()
        val simkl = client.get(URI("https://simkl.com/anime/46128").toURL())
        println("  simkl -> http=${simkl.code} in ${System.currentTimeMillis() - secondProviderStart}ms")

        client.destroySessions()

        // then
        val cold = timings.first()
        val warm = timings.drop(1)

        // The whole point: the challenge is solved once, not per entry. Without reuse every request costs
        // what the first one did, which is the difference between a ~30h crawl and a ~180h one.
        assertThat(warm).describedAs("every request after the first must reuse the session").allSatisfy {
            assertThat(it).isLessThan(cold / 2)
        }
        assertThat(warm.average()).describedAs("warm requests averaged more than 8s - is the session being reused?").isLessThan(8_000.0)
        assertThat(simkl.code).describedAs("a second provider must get its own session").isEqualTo(200)
    }
}

/**
 * Points [TunnelConfig] at the real tunnel with its defaults (172.17.0.1:1080, the four banned provider(s)).
 */
private fun enabledTunnelRegistryForLiveTest() = object : ConfigRegistry by TestConfigRegistry {
    override fun boolean(key: String): Boolean = true
    override fun string(key: String): String? = null
    override fun int(key: String): Int? = null
    override fun <T : Any> list(key: String): List<T>? = null
}
