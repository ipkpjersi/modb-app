package io.github.manamiproject.modb.app.config

import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.test.tempDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Reads `maxEntriesPerRun` through a real TOML file and a real [DefaultConfigRegistry], rather than a mocked
 * [io.github.manamiproject.modb.core.config.ConfigRegistry].
 *
 * Exists because a mocked registry cannot catch a mismatch between the declared property type and what the
 * real TOML path actually produces. The first attempt at this setting used a TOML inline table read by
 * `MapPropertyDelegate`, which passed every mock-based test and returned nothing at all in practice:
 * [DefaultConfigRegistry] flattens an inline table into individual dotted properties, so the map lookup on
 * the parent key found no map. The list form below is read back intact. These tests pin that real path.
 */
internal class MaxEntriesPerRunTomlTest {

    @Nested
    inner class RealTomlTests {

        @Test
        fun `reads the list form from a real config file`() {
            tempDirectory {
                // given
                val configFile = tempDir.resolve("config.toml")
                configFile.writeText(
                    """
                        [modb.app]
                        maxEntriesPerRun = ["anidb.net=100"]
                    """.trimIndent()
                )

                val appConfig = AppConfig(
                    configRegistry = DefaultConfigRegistry(
                        classPathFile = "does-not-exist.toml",
                        localFile = configFile,
                    ),
                )

                // when
                val result = appConfig.maxEntriesPerRun(AnidbConfig)

                // then
                assertThat(result).isEqualTo(100)
            }
        }

        @Test
        fun `reads several providers from a real config file`() {
            tempDirectory {
                // given
                val configFile = tempDir.resolve("config.toml")
                configFile.writeText(
                    """
                        [modb.app]
                        maxEntriesPerRun = ["anidb.net=250", "kitsu.app=10"]
                    """.trimIndent()
                )

                val appConfig = AppConfig(
                    configRegistry = DefaultConfigRegistry(
                        classPathFile = "does-not-exist.toml",
                        localFile = configFile,
                    ),
                )

                // when
                val result = appConfig.maxEntriesPerRun(AnidbConfig)

                // then
                assertThat(result).isEqualTo(250)
            }
        }

        @Test
        fun `is uncapped when the property is absent from a real config file`() {
            tempDirectory {
                // given
                val configFile = tempDir.resolve("config.toml")
                configFile.writeText(
                    """
                        [modb.app]
                        networkInterface = "eth0"
                    """.trimIndent()
                )

                val appConfig = AppConfig(
                    configRegistry = DefaultConfigRegistry(
                        classPathFile = "does-not-exist.toml",
                        localFile = configFile,
                    ),
                )

                // when
                val result = appConfig.maxEntriesPerRun(AnidbConfig)

                // then
                assertThat(result).isZero()
            }
        }
    }
}
