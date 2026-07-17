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
                        "anidb.net",
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
                assertThat(result).hasMessageContaining("anidb.net, anime-planet.com, anisearch.com, simkl.com")
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
