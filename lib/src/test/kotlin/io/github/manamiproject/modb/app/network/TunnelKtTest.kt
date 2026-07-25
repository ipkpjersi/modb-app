package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.test.exceptionExpected
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.ServerSocket
import kotlin.test.Test

internal class TunnelKtTest {

    @Nested
    inner class CheckTunnelTests {

        @Test
        fun `does nothing if the tunnel is disabled`() {
            runTest {
                // given
                val appConfig = testConfig()
                val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = false, port = closedPort()))

                // when
                checkTunnel(tunnelConfig, appConfig)

                // then
                // no exception - a host without a tunnel is unaffected even though the port is closed
            }
        }

        @Test
        fun `does nothing if no tunneled provider is active in this run`() {
            runTest {
                // given
                // every tunneled provider is deactivated, so the run has no use for the tunnel
                val appConfig = testConfig(
                    deactivated = setOf(
                        "anime-planet.com",
                        "anisearch.com",
                        "simkl.com",
                    ),
                )
                val tunnelConfig = TunnelConfig(tunnelRegistry(enabled = true, port = closedPort()))

                // when
                checkTunnel(tunnelConfig, appConfig)

                // then
                // no exception although the tunnel is enabled and the port is closed
            }
        }

        @Test
        fun `throws if the tunnel is enabled and a tunneled provider is active but the tunnel is down`() {
            runTest {
                // given
                val appConfig = testConfig()
                val tunnelConfig = TunnelConfig(
                    tunnelRegistry(
                        enabled = true,
                        host = "127.0.0.1",
                        port = closedPort(),
                    ),
                )

                // when
                val result = exceptionExpected<IllegalStateException> {
                    checkTunnel(tunnelConfig, appConfig)
                }

                // then
                assertThat(result).hasMessageContaining("Reverse SSH tunnel is not reachable")
                assertThat(result).hasMessageContaining("anime-planet.com, anisearch.com, simkl.com")
            }
        }

        @Test
        fun `passes if the tunnel is up`() {
            runTest {
                // given
                val appConfig = testConfig()
                ServerSocket(0).use { serverSocket ->
                    val tunnelConfig = TunnelConfig(
                        tunnelRegistry(
                            enabled = true,
                            host = "127.0.0.1",
                            port = serverSocket.localPort,
                        ),
                    )

                    // when
                    checkTunnel(tunnelConfig, appConfig)

                    // then
                    // no exception - something is listening on the tunnel port
                }
            }
        }

        @Test
        fun `only requires the tunnel for the tunneled provider which are actually active`() {
            runTest {
                // given
                // anisearch is still the only active tunneled provider, so the tunnel is still required
                val appConfig = testConfig(
                    deactivated = setOf(
                        "anidb.net",
                        "anime-planet.com",
                        "simkl.com",
                    ),
                )
                val tunnelConfig = TunnelConfig(
                    tunnelRegistry(
                        enabled = true,
                        host = "127.0.0.1",
                        port = closedPort(),
                    ),
                )

                // when
                val result = exceptionExpected<IllegalStateException> {
                    checkTunnel(tunnelConfig, appConfig)
                }

                // then
                assertThat(result).hasMessageContaining("[anisearch.com]")
            }
        }
    }

    @Nested
    inner class CheckCommercialProxyTests {

        @Test
        fun `does nothing under test context`() {
            // given
            // TestAppConfig reports a test context; an enabled-but-blank-url config would otherwise throw.
            val commercialProxyConfig = CommercialProxyConfig(commercialRegistry(enabled = true, url = null))
            val tunnelConfig = TunnelConfig(tunnelRegistry())

            // when
            checkCommercialProxy(commercialProxyConfig, tunnelConfig, TestAppConfig)

            // then
            // no exception - skipped entirely under test
        }

        @Test
        fun `throws when a provider is in both the tunnel and the commercial set`() {
            // given
            val appConfig = testConfig()
            val commercialProxyConfig = CommercialProxyConfig(commercialRegistry(
                enabled = true,
                url = "socks5://gw.example.com:7000",
                providers = listOf("anidb.net"),
            ))
            val tunnelConfig = TunnelConfig(tunnelRegistry(providers = listOf("anidb.net", "anisearch.com")))

            // when
            val result = exceptionExpected<IllegalStateException> {
                checkCommercialProxy(commercialProxyConfig, tunnelConfig, appConfig)
            }

            // then
            assertThat(result).hasMessageContaining("[anidb.net]")
            assertThat(result).hasMessageContaining("both the tunnel and the commercial proxy set")
        }

        @Test
        fun `throws when a commercial provider is active but the proxy is disabled`() {
            // given
            val appConfig = testConfig()
            val commercialProxyConfig = CommercialProxyConfig(commercialRegistry(
                enabled = false,
                providers = listOf("anidb.net"),
            ))
            val tunnelConfig = TunnelConfig(tunnelRegistry())

            // when
            val result = exceptionExpected<IllegalStateException> {
                checkCommercialProxy(commercialProxyConfig, tunnelConfig, appConfig)
            }

            // then
            assertThat(result).hasMessageContaining("[anidb.net]")
            assertThat(result).hasMessageContaining("commercial proxy is disabled")
        }

        @Test
        fun `passes when a commercial provider is deactivated and the proxy is disabled`() {
            // given
            val appConfig = testConfig(deactivated = setOf("anidb.net"))
            val commercialProxyConfig = CommercialProxyConfig(commercialRegistry(
                enabled = false,
                providers = listOf("anidb.net"),
            ))
            val tunnelConfig = TunnelConfig(tunnelRegistry())

            // when
            checkCommercialProxy(commercialProxyConfig, tunnelConfig, appConfig)

            // then
            // no exception - anidb is turned off, so a disabled proxy is fine
        }

        @Test
        fun `passes when the proxy is enabled and configured`() {
            // given
            val appConfig = testConfig()
            val commercialProxyConfig = CommercialProxyConfig(commercialRegistry(
                enabled = true,
                url = "socks5://gw.example.com:7000",
                providers = listOf("anidb.net"),
            ))
            val tunnelConfig = TunnelConfig(tunnelRegistry(providers = listOf("anisearch.com")))

            // when
            checkCommercialProxy(commercialProxyConfig, tunnelConfig, appConfig)

            // then
            // no exception
        }
    }
}

private fun testConfig(deactivated: Set<Hostname> = emptySet()) = object : Config by TestAppConfig {
    override fun isTestContext(): Boolean = false
    override fun deactivatedMetaDataProviders(): Set<Hostname> = deactivated
}

/**
 * Binds a port and releases it again, so the number is known to be free. Good enough to simulate a tunnel
 * which is not there.
 */
private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

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

private fun commercialRegistry(
    enabled: Boolean? = null,
    providers: List<String>? = null,
    url: String? = null,
) = object : ConfigRegistry by TestConfigRegistry {
    override fun boolean(key: String): Boolean? = enabled
    override fun int(key: String): Int? = null
    override fun string(key: String): String? = if (key.endsWith(".url")) url else null

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> list(key: String): List<T>? = providers as List<T>?
}
