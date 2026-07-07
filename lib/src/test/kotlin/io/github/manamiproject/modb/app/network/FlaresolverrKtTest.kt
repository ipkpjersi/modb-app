package io.github.manamiproject.modb.app.network

import io.github.manamiproject.kommand.CommandExecutor
import io.github.manamiproject.kommand.CommandLineConfig
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.test.exceptionExpected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.Test

internal class FlaresolverrKtTest {

    @Nested
    inner class StartFlaresolverrTests {

        @Test
        fun `reuses an already running container and returns a blank id`() {
            // given
            val fixedClock = Clock.fixed(Instant.parse("2019-11-17T15:00:00.00Z"), UTC)
            val invocation = mutableListOf<List<String>>()
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String {
                    invocation.add(command)
                    return "abc123"
                }
            }

            // when
            val result = startFlaresolverr {
                commandExecutor = testCommandExecutor
                clock = fixedClock
            }

            // then
            assertThat(result).isEqualTo(EMPTY)
            assertThat(invocation).containsExactly(
                listOf(
                    "docker",
                    "ps",
                    "--filter",
                    "ancestor=ghcr.io/flaresolverr/flaresolverr:v3.5.0",
                    "--format",
                    "{{.ID}}",
                )
            )
        }

        @Test
        fun `creates a new container with default values when none is running`() {
            // given
            val fixedClock = Clock.fixed(Instant.parse("2019-11-17T15:00:00.00Z"), UTC)
            val invocation = mutableListOf<List<String>>()
            var invocationCounter = 0
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String {
                    invocation.add(command)
                    val ret = if (invocationCounter == 0) EMPTY else "abc123"
                    invocationCounter++
                    return ret
                }
            }

            // when
            val result = startFlaresolverr {
                commandExecutor = testCommandExecutor
                clock = fixedClock
            }

            // then
            assertThat(result).isEqualTo("abc123")
            assertThat(invocation).containsExactly(
                listOf(
                    "docker",
                    "ps",
                    "--filter",
                    "ancestor=ghcr.io/flaresolverr/flaresolverr:v3.5.0",
                    "--format",
                    "{{.ID}}",
                ),
                listOf(
                    "docker",
                    "run",
                    "-d",
                    "--rm",
                    "--name=flaresolverr-20191117150000",
                    "-p",
                    "8191:8191",
                    "-e",
                    "LOG_LEVEL=info",
                    "ghcr.io/flaresolverr/flaresolverr:v3.5.0",
                ),
            )
        }

        @Test
        fun `creates a new container with a custom port`() {
            // given
            val fixedClock = Clock.fixed(Instant.parse("2019-11-17T15:00:00.00Z"), UTC)
            val invocation = mutableListOf<List<String>>()
            var invocationCounter = 0
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String {
                    invocation.add(command)
                    val ret = if (invocationCounter == 0) EMPTY else "abc123"
                    invocationCounter++
                    return ret
                }
            }

            // when
            startFlaresolverr {
                commandExecutor = testCommandExecutor
                clock = fixedClock
                port = 9009
            }

            // then
            assertThat(invocation.last()).containsExactly(
                "docker",
                "run",
                "-d",
                "--rm",
                "--name=flaresolverr-20191117150000",
                "-p",
                "9009:8191",
                "-e",
                "LOG_LEVEL=info",
                "ghcr.io/flaresolverr/flaresolverr:v3.5.0",
            )
        }

        @Test
        fun `creates a new container with a custom log level`() {
            // given
            val fixedClock = Clock.fixed(Instant.parse("2019-11-17T15:00:00.00Z"), UTC)
            val invocation = mutableListOf<List<String>>()
            var invocationCounter = 0
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String {
                    invocation.add(command)
                    val ret = if (invocationCounter == 0) EMPTY else "abc123"
                    invocationCounter++
                    return ret
                }
            }

            // when
            startFlaresolverr {
                commandExecutor = testCommandExecutor
                clock = fixedClock
                logLevel = "error"
            }

            // then
            assertThat(invocation.last()).containsExactly(
                "docker",
                "run",
                "-d",
                "--rm",
                "--name=flaresolverr-20191117150000",
                "-p",
                "8191:8191",
                "-e",
                "LOG_LEVEL=error",
                "ghcr.io/flaresolverr/flaresolverr:v3.5.0",
            )
        }

        @ParameterizedTest
        @ValueSource(strings = [
            "there was an error",
            "docker: Failed to do",
        ])
        fun `throws exception for any error indicator during container start`(value: String) {
            // given
            val fixedClock = Clock.fixed(Instant.parse("2019-11-17T15:00:00.00Z"), UTC)
            var invocationCounter = 0
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String {
                    val ret = if (invocationCounter == 0) EMPTY else value
                    invocationCounter++
                    return ret
                }
            }

            // when
            val result = exceptionExpected<IllegalStateException> {
                startFlaresolverr {
                    commandExecutor = testCommandExecutor
                    clock = fixedClock
                }
            }

            // then
            assertThat(result).hasMessage("Error during container start:\n$value")
        }
    }

    @Nested
    inner class StopFlaresolverrTests {

        @Test
        fun `blank container id is a no-op`() {
            // given
            val invocation = mutableListOf<List<String>>()
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String {
                    invocation.add(command)
                    return EMPTY
                }
            }

            // when
            stopFlaresolverr(EMPTY) {
                commandExecutor = testCommandExecutor
            }

            // then
            assertThat(invocation).isEmpty()
        }

        @Test
        fun `correctly builds stop command`() {
            // given
            val invocation = mutableListOf<String>()
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String {
                    invocation.addAll(command)
                    return "abc123"
                }
            }

            // when
            stopFlaresolverr("abc123") {
                commandExecutor = testCommandExecutor
            }

            // then
            assertThat(invocation).containsExactly(
                "docker",
                "stop",
                "abc123",
            )
        }

        @Test
        fun `throws exception when the stopped container id does not match`() {
            // given
            val testCommandExecutor = object : CommandExecutor {
                override var config: CommandLineConfig = CommandLineConfig()
                override fun executeCmd(command: List<String>): String = "anything else"
            }

            // when
            val result = exceptionExpected<IllegalStateException> {
                stopFlaresolverr("abc123") {
                    commandExecutor = testCommandExecutor
                }
            }

            // then
            assertThat(result).hasMessage("Error during container stop:\nanything else")
        }
    }
}
