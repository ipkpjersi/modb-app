package io.github.manamiproject.modb.app.config

import io.github.manamiproject.AnimenewsnetworkConfig
import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anilist.AnilistConfig
import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.anisearch.AnisearchRelationsConfig
import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.app.TestMetaDataProviderConfig
import io.github.manamiproject.modb.app.crawlers.animeplanet.AnimePlanetPaginationIdRangeSelectorConfig
import io.github.manamiproject.modb.app.crawlers.livechart.LivechartPaginationIdRangeSelectorConfig
import io.github.manamiproject.modb.core.date.WeekOfYear
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.kitsu.KitsuConfig
import io.github.manamiproject.modb.livechart.LivechartConfig
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.tempDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.test.Test

internal class AppConfigTest {

    @Nested
    inner class DownloadsDirectoryTests {

        @Test
        fun `throws an exception if config property directs to a path which doesn't exist`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun string(key: String): String = "non-existent"
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.downloadsDirectory()
            }

            // then
            assertThat(result).hasMessage("Download directory set by 'downloadsDirectory' to [non-existent] doesn't exist or is not a directory.")
        }

        @Test
        fun `throws an exception if config property directs to a file`() {
            tempDirectory {
                // given
                val testFile = tempDir.resolve("test.txt").createFile()

                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = testFile.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = exceptionExpected<IllegalStateException> {
                    appConfig.downloadsDirectory()
                }

                // then
                assertThat(result).hasMessageStartingWith("Download directory set by 'downloadsDirectory' to [")
                assertThat(result).hasMessageEndingWith("test.txt] doesn't exist or is not a directory.")
            }
        }

        @Test
        fun `correctly returns the download directory`() {
            tempDirectory {
                // given
                val testDir = tempDir.resolve("test").createDirectory()

                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = testDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.downloadsDirectory()

                // then
                assertThat(result).exists()
                assertThat(result).isDirectory()
            }
        }
    }

    @Nested
    inner class CurrentWeekWorkingDirTests {

        @Test
        fun `correctly generates directory name`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                val now = WeekOfYear.currentWeek()

                // when
                val result = appConfig.currentWeekWorkingDir()

                // then
                assertThat(result.fileName.toString()).startsWith("${now.year}-")
                assertThat(result.fileName.toString()).endsWith(now.week.toString())
            }
        }
    }

    @Nested
    inner class WorkingDirTests {

        @Test
        fun `throws exception if workingdir is an existing regular file`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                val testCurrentWeekWorkingDir = appConfig.currentWeekWorkingDir().createDirectory()
                testCurrentWeekWorkingDir.resolve(AnidbConfig.hostname()).createFile()

                // when
                val result = exceptionExpected<IllegalStateException> {
                    appConfig.workingDir(AnidbConfig)
                }

                // then
                assertThat(result).hasMessage("Working directory must not be a regular file.")
            }
        }

        @Test
        fun `works correctly if directory already exists`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                val testCurrentWeekWorkingDir = appConfig.currentWeekWorkingDir().createDirectory()
                testCurrentWeekWorkingDir.resolve(AnidbConfig.hostname()).createDirectory()

                // when
                val result = appConfig.workingDir(AnidbConfig)

                // then
                assertThat(result).exists()
                assertThat(result).isDirectory()
            }
        }

        @Test
        fun `throws exception if MetaDataProviderConfig is not mapped`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by TestMetaDataProviderConfig {
                    override fun hostname(): Hostname = "example.com"
                }

                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = exceptionExpected<IllegalStateException> {
                    appConfig.workingDir(testMetaDataProviderConfig)
                }

                // then
                assertThat(result).hasMessage("No working directory mapping for [${testMetaDataProviderConfig::class.simpleName}]")
            }
        }

        @Test
        fun `check that name of working directory for AnidbConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(AnidbConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("anidb.net")
            }
        }

        @Test
        fun `check that name of working directory for AnilistConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(AnilistConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("anilist.co")
            }
        }

        @Test
        fun `check that name of working directory for AnimePlanetConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(AnimePlanetConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("anime-planet.com")
            }
        }

        @Test
        fun `check that name of working directory for AnimePlanetPaginationIdRangeSelectorConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(AnimePlanetPaginationIdRangeSelectorConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("anime-planet.com")
            }
        }

        @Test
        fun `check that name of working directory for AnimenewsnetworkConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(AnimenewsnetworkConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("animenewsnetwork.com")
            }
        }

        @Test
        fun `check that name of working directory for AnisearchConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(AnisearchConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("anisearch.com")
            }
        }

        @Test
        fun `check that name of working directory for AnisearchRelationsConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(AnisearchRelationsConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("anisearch.com-relations")
            }
        }

        @Test
        fun `check that name of working directory for KitsuConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(KitsuConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("kitsu.app")
            }
        }

        @Test
        fun `check that name of working directory for LivechartConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(LivechartConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("livechart.me")
            }
        }

        @Test
        fun `check that name of working directory for LivechartPaginationIdRangeSelectorConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(LivechartPaginationIdRangeSelectorConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("livechart.me")
            }
        }

        @Test
        fun `check that name of working directory for MyanimelistConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(MyanimelistConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("myanimelist.net")
            }
        }

        @Test
        fun `check that name of working directory for SimklConfig is correct`() {
            tempDirectory {
                // given
                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = tempDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.workingDir(SimklConfig)

                // then
                assertThat(result).exists()
                assertThat(result.fileName.toString()).isEqualTo("simkl.com")
            }
        }

    }

    @Nested
    inner class OutputDirectoryTests {

        @Test
        fun `throws an exception if config property directs to a path which doesn't exist`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun string(key: String): String = "non-existent"
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.outputDirectory()
            }

            // then
            assertThat(result).hasMessage("Output directory set by 'outputDirectory' to [non-existent] doesn't exist or is not a directory.")
        }

        @Test
        fun `throws an exception if config property directs to a file`() {
            tempDirectory {
                // given
                val testFile = tempDir.resolve("test.txt").createFile()

                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = testFile.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = exceptionExpected<IllegalStateException> {
                    appConfig.outputDirectory()
                }

                // then
                assertThat(result).hasMessageStartingWith("Output directory set by 'outputDirectory' to [")
                assertThat(result).hasMessageEndingWith("test.txt] doesn't exist or is not a directory.")
            }
        }

        @Test
        fun `correctly returns the download directory`() {
            tempDirectory {
                // given
                val testDir = tempDir.resolve("test").createDirectory()

                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = testDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.outputDirectory()

                // then
                assertThat(result).exists()
                assertThat(result).isDirectory()
            }
        }
    }

    @Nested
    inner class DownloadControlStateDirectoryTests {

        @Test
        fun `throws an exception if config property directs to a path which doesn't exist`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun string(key: String): String = "non-existent"
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.downloadControlStateDirectory()
            }

            // then
            assertThat(result).hasMessage("Output directory set by 'downloadControlStateDirectory' to [non-existent] doesn't exist or is not a directory.")
        }

        @Test
        fun `throws an exception if config property directs to a file`() {
            tempDirectory {
                // given
                val testFile = tempDir.resolve("test.txt").createFile()

                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = testFile.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = exceptionExpected<IllegalStateException> {
                    appConfig.downloadControlStateDirectory()
                }

                // then
                assertThat(result).hasMessageStartingWith("Output directory set by 'downloadControlStateDirectory' to [")
                assertThat(result).hasMessageEndingWith("test.txt] doesn't exist or is not a directory.")
            }
        }

        @Test
        fun `correctly returns the download directory`() {
            tempDirectory {
                // given
                val testDir = tempDir.resolve("test").createDirectory()

                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun string(key: String): String = testDir.toAbsolutePath().toString()
                }

                val appConfig = AppConfig(
                    configRegistry = testConfigRegistry,
                )

                // when
                val result = appConfig.downloadControlStateDirectory()

                // then
                assertThat(result).exists()
                assertThat(result).isDirectory()
            }
        }
    }

    @Nested
    inner class NetworkInterfaceTests {

        @Test
        fun `correctly returns the network interface`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun string(key: String): String = "eth0"
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = appConfig.networkInterface()

            // then
            assertThat(result).isEqualTo("eth0")
        }

        @Test
        fun `throws an exception if config property is blank`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun string(key: String): String = "   "
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.networkInterface()
            }

            // then
            assertThat(result).hasMessage("Network interface set by 'networkInterface' must not be blank.")
        }
    }

    @Nested
    inner class Ipv6PrefixTests {

        @Test
        fun `correctly returns the ipv6 prefix`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun string(key: String): String = "2001:db8:1234:5678::/64"
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = appConfig.ipv6Prefix()

            // then
            assertThat(result).isEqualTo("2001:db8:1234:5678::/64")
        }

        @Test
        fun `throws an exception if config property is blank`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun string(key: String): String = "   "
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.ipv6Prefix()
            }

            // then
            assertThat(result).hasMessage("IPv6 prefix set by 'ipv6Prefix' must not be blank.")
        }
    }

    @Nested
    inner class DeactivatedMetaDataProvidersTests {

        @Test
        fun `returns an empty set if the config property is not set`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun <T : Any> list(key: String): List<T>? = null
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = appConfig.deactivatedMetaDataProviders()

            // then
            assertThat(result).isEmpty()
        }

        @Test
        fun `correctly returns the deactivated metadata provider`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> list(key: String): List<T> = listOf(
                    AnisearchConfig.hostname(),
                    SimklConfig.hostname(),
                ) as List<T>
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = appConfig.deactivatedMetaDataProviders()

            // then
            assertThat(result).containsExactlyInAnyOrder(
                AnisearchConfig.hostname(),
                SimklConfig.hostname(),
            )
        }

        @Test
        fun `throws an exception if a hostname doesn't belong to a known metadata provider`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> list(key: String): List<T> = listOf(
                    AnisearchConfig.hostname(),
                    "example.org",
                ) as List<T>
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.deactivatedMetaDataProviders()
            }

            // then
            assertThat(result).hasMessage("Unknown hostnames set by 'deactivatedMetaDataProviders': [example.org].")
        }
    }

    @Nested
    inner class DeactivatedCommercialMetaDataProvidersTests {

        @Test
        fun `returns an empty set if the config property is not set`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun <T : Any> list(key: String): List<T>? = null
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = appConfig.deactivatedCommercialMetaDataProviders()

            // then
            assertThat(result).isEmpty()
        }

        @Test
        fun `correctly returns the deactivated commercial metadata provider`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> list(key: String): List<T> = listOf(
                    AnidbConfig.hostname(),
                ) as List<T>
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = appConfig.deactivatedCommercialMetaDataProviders()

            // then
            assertThat(result).containsExactly(
                AnidbConfig.hostname(),
            )
        }

        @Test
        fun `throws an exception if a hostname doesn't belong to a known metadata provider`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> list(key: String): List<T> = listOf(
                    AnidbConfig.hostname(),
                    "example.org",
                ) as List<T>
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.deactivatedCommercialMetaDataProviders()
            }

            // then
            assertThat(result).hasMessage("Unknown hostnames set by 'deactivatedCommercialMetaDataProviders': [example.org].")
        }
    }

    @Nested
    inner class IsDeactivatedTests {

        @Test
        fun `returns true for a deactivated metadata provider and false for an active one`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> list(key: String): List<T> = listOf(AnisearchConfig.hostname()) as List<T>
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val deactivated = appConfig.isDeactivated(AnisearchConfig)
            val active = appConfig.isDeactivated(MyanimelistConfig)

            // then
            assertThat(deactivated).isTrue()
            assertThat(active).isFalse()
        }

        @Test
        fun `returns true for the anisearch relations config, because it shares the hostname with anisearch`() {
            // given
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> list(key: String): List<T> = listOf(AnisearchConfig.hostname()) as List<T>
            }

            val appConfig = AppConfig(
                configRegistry = testConfigRegistry,
            )

            // when
            val result = appConfig.isDeactivated(AnisearchRelationsConfig)

            // then
            assertThat(result).isTrue()
        }
    }

    @Nested
    inner class CompanionObjectTests {

        @Test
        fun `instance property always returns same instance`() {
            tempDirectory {
                // given
                val previous = AppConfig.instance

                // when
                val result = AppConfig.instance

                // then
                assertThat(result).isExactlyInstanceOf(AppConfig::class.java)
                assertThat(result === previous).isTrue()
            }
        }
    }

    @Nested
    inner class MaxEntriesPerRunTests {

        @Test
        fun `returns 0 if the config property is not set`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(null))

            // when
            val result = appConfig.maxEntriesPerRun(AnidbConfig)

            // then
            assertThat(result).isZero()
        }

        @Test
        fun `returns the configured limit for the requested provider`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("anidb.net=100")))

            // when
            val result = appConfig.maxEntriesPerRun(AnidbConfig)

            // then
            assertThat(result).isEqualTo(100)
        }

        @Test
        fun `returns 0 for a provider which is not listed`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("anidb.net=100")))

            // when
            val result = appConfig.maxEntriesPerRun(KitsuConfig)

            // then
            assertThat(result).isZero()
        }

        @Test
        fun `tolerates surrounding whitespace and uppercase in the hostname`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf(" AniDB.net = 100 ")))

            // when
            val result = appConfig.maxEntriesPerRun(AnidbConfig)

            // then
            assertThat(result).isEqualTo(100)
        }

        @Test
        fun `accepts a limit of zero as explicitly uncapped`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("anidb.net=0")))

            // when
            val result = appConfig.maxEntriesPerRun(AnidbConfig)

            // then
            assertThat(result).isZero()
        }

        @Test
        fun `throws for an unknown hostname`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("example.org=100")))

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.maxEntriesPerRun(AnidbConfig)
            }

            // then
            assertThat(result).hasMessage("Unknown hostnames set by 'maxEntriesPerRun': [example.org].")
        }

        @Test
        fun `throws for a provider whose crawler cannot honor a cap`() {
            // given
            // anisearch paginates, so it never resolves a full id list up front. Accepting the value and
            // quietly ignoring it would defeat the purpose of configuring a spend limit at all.
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("anisearch.com=100")))

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.maxEntriesPerRun(AnidbConfig)
            }

            // then
            assertThat(result).hasMessageStartingWith("'maxEntriesPerRun' is not supported for [anisearch.com]")
        }

        @Test
        fun `throws for a malformed entry`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("anidb.net")))

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.maxEntriesPerRun(AnidbConfig)
            }

            // then
            assertThat(result).hasMessage("Malformed entry in 'maxEntriesPerRun': [anidb.net]. Expected 'hostname=limit'.")
        }

        @Test
        fun `throws for a non-numeric limit`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("anidb.net=lots")))

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.maxEntriesPerRun(AnidbConfig)
            }

            // then
            assertThat(result).hasMessage("Non-numeric limit in 'maxEntriesPerRun' entry: [anidb.net=lots].")
        }

        @Test
        fun `throws for a negative limit`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("anidb.net=-5")))

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.maxEntriesPerRun(AnidbConfig)
            }

            // then
            assertThat(result).hasMessage("Negative limit in 'maxEntriesPerRun' entry: [anidb.net=-5].")
        }

        @Test
        fun `throws for a duplicate hostname`() {
            // given
            val appConfig = AppConfig(configRegistry = capRegistry(listOf("anidb.net=100", "anidb.net=200")))

            // when
            val result = exceptionExpected<IllegalStateException> {
                appConfig.maxEntriesPerRun(AnidbConfig)
            }

            // then
            assertThat(result).hasMessage("Duplicate hostname in 'maxEntriesPerRun': [anidb.net].")
        }
    }
}

/**
 * A [ConfigRegistry] supplying only the `maxEntriesPerRun` list, which is all these tests read.
 */
private fun capRegistry(entries: List<String>?) = object : ConfigRegistry by TestConfigRegistry {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> list(key: String): List<T>? = entries as List<T>?
}
