package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.core.config.ConfigRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Proxy.NO_PROXY
import java.net.Proxy.Type.SOCKS
import kotlin.test.Test

internal class TunnelConfigTest {

    @Nested
    inner class DefaultTests {

        @Test
        fun `tunnel is disabled by default so a host without a tunnel is unaffected`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry())

            // when
            val result = tunnelConfig.enabled

            // then
            assertThat(result).isFalse()
        }

        @Test
        fun `default host is the docker bridge gateway so the FlareSolverr container can reach it`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry())

            // when
            val result = tunnelConfig.host

            // then
            assertThat(result).isEqualTo("172.17.0.1")
        }

        @Test
        fun `default port is 1080`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry())

            // when
            val result = tunnelConfig.port

            // then
            assertThat(result).isEqualTo(1080)
        }

        @Test
        fun `default providers are the four which are banned on datacenter IP ranges`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry())

            // when
            val result = tunnelConfig.providers

            // then
            assertThat(result).containsExactlyInAnyOrder(
                "anidb.net",
                "anime-planet.com",
                "anisearch.com",
                "simkl.com",
            )
        }
    }

    @Nested
    inner class IsTunneledTests {

        @Test
        fun `returns false for a listed provider if the tunnel is disabled`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = false))

            // when
            val result = tunnelConfig.isTunneled("anidb.net")

            // then
            assertThat(result).isFalse()
        }

        @Test
        fun `returns true for a listed provider if the tunnel is enabled`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = true))

            // when
            val result = tunnelConfig.isTunneled("anidb.net")

            // then
            assertThat(result).isTrue()
        }

        @Test
        fun `returns false for a provider which is not routed through the tunnel`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = true))

            // when
            val result = tunnelConfig.isTunneled("myanimelist.net")

            // then
            assertThat(result).isFalse()
        }

        @Test
        fun `strips a leading www so a host cannot silently miss the tunnel and hit the ban`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = true))

            // when
            val result = tunnelConfig.isTunneled("www.anime-planet.com")

            // then
            assertThat(result).isTrue()
        }

        @Test
        fun `matching is case insensitive`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = true))

            // when
            val result = tunnelConfig.isTunneled("AniDB.net")

            // then
            assertThat(result).isTrue()
        }
    }

    @Nested
    inner class ProxyForTests {

        @Test
        fun `returns a SOCKS proxy pointing at the tunnel for a tunneled provider`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = true))

            // when
            val result = tunnelConfig.proxyFor("anisearch.com")

            // then
            assertThat(result).isEqualTo(Proxy(SOCKS, InetSocketAddress("172.17.0.1", 1080)))
        }

        @Test
        fun `returns NO_PROXY for a provider which keeps the direct path`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = true))

            // when
            val result = tunnelConfig.proxyFor("myanimelist.net")

            // then
            assertThat(result).isEqualTo(NO_PROXY)
        }

        @Test
        fun `returns NO_PROXY for everything while the tunnel is disabled`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = false))

            // when
            val result = tunnelConfig.proxyFor("anisearch.com")

            // then
            assertThat(result).isEqualTo(NO_PROXY)
        }
    }

    @Nested
    inner class SocksUrlTests {

        @Test
        fun `creates the SOCKS5 URL which FlareSolverr expects`() {
            // given
            val tunnelConfig = TunnelConfig(tunnelRegistry(host = "10.1.2.3", port = 1234))

            // when
            val result = tunnelConfig.socksUrl()

            // then
            assertThat(result).isEqualTo("socks5://10.1.2.3:1234")
        }
    }
}

/**
 * [TunnelConfig] reads at most one property per type, so returning a single value per type is enough to
 * drive it. `null` means "not configured", which makes the delegate fall back to its default.
 */
private fun tunnelRegistry(
    enabled: Boolean? = null,
    host: String? = null,
    port: Int? = null,
    providers: List<String>? = null,
) = object : ConfigRegistry by TestConfigRegistry {
    override fun boolean(key: String): Boolean? = enabled
    override fun string(key: String): String? = host
    override fun int(key: String): Int? = port

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> list(key: String): List<T>? = providers as List<T>?
}
