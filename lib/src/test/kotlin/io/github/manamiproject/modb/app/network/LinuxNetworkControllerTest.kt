package io.github.manamiproject.modb.app.network

import io.github.manamiproject.kommand.CommandExecutor
import io.github.manamiproject.kommand.CommandLineConfig
import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.test.exceptionExpected
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.time.temporal.ChronoUnit
import kotlin.test.Test

internal class LinuxNetworkControllerTest {

    @Nested
    inner class RestartAsyncTests {

        @Test
        fun `first rotation adds a new address and deprecates the pre-existing host address`() {
            runTest {
                // given
                val clock = Clock.fixed(Instant.parse("2021-01-31T16:02:42.00Z"), UTC)

                val testAppConfig = object: Config by TestAppConfig {
                    override fun isTestContext(): Boolean = true
                    override fun clock(): Clock = clock
                    override fun networkInterface(): String = "eth0"
                    override fun ipv6Prefix(): String = "2001:db8:1234:5678::/64"
                }

                val invocations = mutableListOf<List<String>>()
                val testCommandExecutor = object : CommandExecutor {
                    override var config: CommandLineConfig = CommandLineConfig()
                    override fun executeCmd(command: List<String>): String {
                        invocations.add(command)
                        return if (command.contains("show")) {
                            "2: eth0    inet6 2001:db8:1234:5678::2/64 scope global \\    valid_lft forever preferred_lft forever"
                        } else {
                            EMPTY
                        }
                    }
                }

                val linuxNetworkController = LinuxNetworkController(
                    appConfig = testAppConfig,
                    commandExecutor = testCommandExecutor,
                    ipv6AddressGenerator = { "2001:db8:1234:5678:aaaa:bbbb:cccc:dddd" },
                )

                // when
                val result = linuxNetworkController.restartAsync().await()

                // then
                assertThat(result).isTrue()
                assertThat(linuxNetworkController.isNetworkActive()).isTrue()
                assertThat(invocations).containsExactly(
                    listOf("ip", "-o", "-6", "addr", "show", "dev", "eth0", "scope", "global"),
                    listOf("sudo", "ip", "-6", "addr", "add", "2001:db8:1234:5678:aaaa:bbbb:cccc:dddd/64", "dev", "eth0", "nodad"),
                    listOf("sudo", "ip", "-6", "addr", "change", "2001:db8:1234:5678::2/64", "dev", "eth0", "valid_lft", "forever", "preferred_lft", "0"),
                )
            }
        }

        @Test
        fun `second rotation adds a new address and removes the previous one without reading or deprecating again`() {
            runTest {
                // given
                var clock = Clock.fixed(Instant.parse("2021-01-31T16:02:42.00Z"), UTC)

                val testAppConfig = object: Config by TestAppConfig {
                    override fun isTestContext(): Boolean = true
                    override fun clock(): Clock = clock
                    override fun networkInterface(): String = "eth0"
                    override fun ipv6Prefix(): String = "2001:db8:1234:5678::/64"
                }

                val invocations = mutableListOf<List<String>>()
                val testCommandExecutor = object : CommandExecutor {
                    override var config: CommandLineConfig = CommandLineConfig()
                    override fun executeCmd(command: List<String>): String {
                        invocations.add(command)
                        clock = Clock.fixed(clock.instant().plus(1, ChronoUnit.MINUTES), UTC)
                        return if (command.contains("show")) {
                            "2: eth0    inet6 2001:db8:1234:5678::2/64 scope global \\    valid_lft forever preferred_lft forever"
                        } else {
                            EMPTY
                        }
                    }
                }

                val addresses = mutableListOf(
                    "2001:db8:1234:5678:1111:1111:1111:1111",
                    "2001:db8:1234:5678:2222:2222:2222:2222",
                )
                val linuxNetworkController = LinuxNetworkController(
                    appConfig = testAppConfig,
                    commandExecutor = testCommandExecutor,
                    ipv6AddressGenerator = { addresses.removeFirst() },
                )

                // when
                linuxNetworkController.restartAsync().await()
                linuxNetworkController.restartAsync().await()

                // then
                assertThat(invocations).containsExactly(
                    listOf("ip", "-o", "-6", "addr", "show", "dev", "eth0", "scope", "global"),
                    listOf("sudo", "ip", "-6", "addr", "add", "2001:db8:1234:5678:1111:1111:1111:1111/64", "dev", "eth0", "nodad"),
                    listOf("sudo", "ip", "-6", "addr", "change", "2001:db8:1234:5678::2/64", "dev", "eth0", "valid_lft", "forever", "preferred_lft", "0"),
                    listOf("sudo", "ip", "-6", "addr", "add", "2001:db8:1234:5678:2222:2222:2222:2222/64", "dev", "eth0", "nodad"),
                    listOf("sudo", "ip", "-6", "addr", "del", "2001:db8:1234:5678:1111:1111:1111:1111/64", "dev", "eth0"),
                )
            }
        }

        @Test
        fun `ignores a rotation requested within 30 seconds of the previous one`() {
            runTest {
                // given
                val clock = Clock.fixed(Instant.parse("2021-01-31T16:02:42.00Z"), UTC)

                val testAppConfig = object: Config by TestAppConfig {
                    override fun isTestContext(): Boolean = true
                    override fun clock(): Clock = clock
                    override fun networkInterface(): String = "eth0"
                    override fun ipv6Prefix(): String = "2001:db8:1234:5678::/64"
                }

                val invocations = mutableListOf<List<String>>()
                val testCommandExecutor = object : CommandExecutor {
                    override var config: CommandLineConfig = CommandLineConfig()
                    override fun executeCmd(command: List<String>): String {
                        invocations.add(command)
                        return if (command.contains("show")) {
                            "2: eth0    inet6 2001:db8:1234:5678::2/64 scope global \\    valid_lft forever preferred_lft forever"
                        } else {
                            EMPTY
                        }
                    }
                }

                val addresses = mutableListOf(
                    "2001:db8:1234:5678:1111:1111:1111:1111",
                    "2001:db8:1234:5678:2222:2222:2222:2222",
                )
                val linuxNetworkController = LinuxNetworkController(
                    appConfig = testAppConfig,
                    commandExecutor = testCommandExecutor,
                    ipv6AddressGenerator = { addresses.removeFirst() },
                )

                // when
                val first = linuxNetworkController.restartAsync().await()
                val invocationsAfterFirst = invocations.toList()
                val second = linuxNetworkController.restartAsync().await()

                // then
                assertThat(first).isTrue()
                assertThat(second).isFalse()
                assertThat(invocations).isEqualTo(invocationsAfterFirst)
            }
        }

        @Test
        fun `throws exception when exceeding the allowed number of rotations`() {
            runTest {
                // given
                var clock = Clock.fixed(Instant.parse("2021-01-31T16:02:42.00Z"), UTC)

                val testAppConfig = object: Config by TestAppConfig {
                    override fun isTestContext(): Boolean = true
                    override fun clock(): Clock = clock
                    override fun networkInterface(): String = "eth0"
                    override fun ipv6Prefix(): String = "2001:db8:1234:5678::/64"
                }

                val testCommandExecutor = object : CommandExecutor {
                    override var config: CommandLineConfig = CommandLineConfig()
                    override fun executeCmd(command: List<String>): String {
                        clock = Clock.fixed(clock.instant().plus(1, ChronoUnit.MINUTES), UTC)
                        return if (command.contains("show")) {
                            "2: eth0    inet6 2001:db8:1234:5678::2/64 scope global \\    valid_lft forever preferred_lft forever"
                        } else {
                            EMPTY
                        }
                    }
                }

                var address = 0
                val linuxNetworkController = LinuxNetworkController(
                    appConfig = testAppConfig,
                    commandExecutor = testCommandExecutor,
                    timeRangeForMaxRestarts = 6000,
                    maxNumberOfRestarts = 2,
                    ipv6AddressGenerator = { "2001:db8:1234:5678::${address++}" },
                )

                // when
                linuxNetworkController.restartAsync().await()
                linuxNetworkController.restartAsync().await()
                val result = exceptionExpected<TooManyRestartsException> {
                    linuxNetworkController.restartAsync().await()
                }

                // then
                assertThat(result).hasMessage("Triggered more than [2] restarts within [6000] seconds.")
            }
        }
    }

    @Nested
    inner class RestoreTests {

        @Test
        fun `re-promotes the deprecated host address and removes the current rotation address`() {
            runTest {
                // given
                val clock = Clock.fixed(Instant.parse("2021-01-31T16:02:42.00Z"), UTC)

                val testAppConfig = object: Config by TestAppConfig {
                    override fun isTestContext(): Boolean = true
                    override fun clock(): Clock = clock
                    override fun networkInterface(): String = "eth0"
                    override fun ipv6Prefix(): String = "2001:db8:1234:5678::/64"
                }

                val invocations = mutableListOf<List<String>>()
                val testCommandExecutor = object : CommandExecutor {
                    override var config: CommandLineConfig = CommandLineConfig()
                    override fun executeCmd(command: List<String>): String {
                        invocations.add(command)
                        return if (command.contains("show")) {
                            "2: eth0    inet6 2001:db8:1234:5678::2/64 scope global \\    valid_lft forever preferred_lft forever"
                        } else {
                            EMPTY
                        }
                    }
                }

                val linuxNetworkController = LinuxNetworkController(
                    appConfig = testAppConfig,
                    commandExecutor = testCommandExecutor,
                    ipv6AddressGenerator = { "2001:db8:1234:5678:aaaa:bbbb:cccc:dddd" },
                )
                linuxNetworkController.restartAsync().await()
                invocations.clear()

                // when
                linuxNetworkController.restore()

                // then
                assertThat(invocations).containsExactly(
                    listOf("sudo", "ip", "-6", "addr", "change", "2001:db8:1234:5678::2/64", "dev", "eth0", "valid_lft", "forever", "preferred_lft", "forever"),
                    listOf("sudo", "ip", "-6", "addr", "del", "2001:db8:1234:5678:aaaa:bbbb:cccc:dddd/64", "dev", "eth0"),
                )
            }
        }

        @Test
        fun `does nothing if no rotation has taken place`() {
            // given
            val invocations = mutableListOf<List<String>>()
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String {
                    invocations.add(command)
                    return EMPTY
                }
            }

            val linuxNetworkController = LinuxNetworkController(
                appConfig = object: Config by TestAppConfig {},
                commandExecutor = testCommandExecutor,
            )

            // when
            linuxNetworkController.restore()

            // then
            assertThat(invocations).isEmpty()
        }
    }

    @Nested
    inner class IsNetworkActiveTests {

        @Test
        fun `is active by default`() {
            // given
            val linuxNetworkController = LinuxNetworkController(
                appConfig = object: Config by TestAppConfig {},
                commandExecutor = object : CommandExecutor {
                    override var config: CommandLineConfig = CommandLineConfig()
                    override fun executeCmd(command: List<String>): String = EMPTY
                },
            )

            // when
            val result = linuxNetworkController.isNetworkActive()

            // then
            assertThat(result).isTrue()
        }
    }

    @Nested
    inner class RandomIpv6AddressTests {

        @Test
        fun `generates an address within the given prefix`() {
            // when
            val result = LinuxNetworkController.randomIpv6Address("2001:db8:1234:5678::/64")

            // then
            assertThat(result).startsWith("2001:db8:1234:5678:")
            assertThat(result.split(":")).hasSize(8)
        }
    }
}
