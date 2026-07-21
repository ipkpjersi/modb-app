package io.github.manamiproject.modb.app.cli

import io.github.manamiproject.AnimenewsnetworkConfig
import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anilist.AnilistConfig
import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.kitsu.KitsuConfig
import io.github.manamiproject.modb.livechart.LivechartConfig
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import io.github.manamiproject.modb.test.exceptionExpected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.test.Test

internal class MetaDataProviderSelectionTest {

    @Nested
    inner class NoArgumentsTests {

        @Test
        fun `returns the config value unchanged when no arguments are passed`() {
            // given
            val selection = MetaDataProviderSelection(
                appConfig = testConfig(deactivated = setOf(SimklConfig.hostname())),
            )

            // when
            val result = selection.deactivatedProvidersForRun(emptyArray())

            // then
            assertThat(result).containsExactly(SimklConfig.hostname())
        }
    }

    @Nested
    inner class OnlyTests {

        @Test
        fun `only keeps the listed providers active and deactivates every other provider`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = selection.deactivatedProvidersForRun(arrayOf("--only", "anidb,anime-planet"))

            // then
            assertThat(result).containsExactlyInAnyOrder(
                AnilistConfig.hostname(),
                AnimenewsnetworkConfig.hostname(),
                AnisearchConfig.hostname(),
                KitsuConfig.hostname(),
                LivechartConfig.hostname(),
                MyanimelistConfig.hostname(),
                SimklConfig.hostname(),
            )
            assertThat(result).doesNotContain(AnidbConfig.hostname(), AnimePlanetConfig.hostname())
        }

        @Test
        fun `only re-enables a provider that config deactivated`() {
            // given
            val selection = MetaDataProviderSelection(
                appConfig = testConfig(deactivated = setOf(AnidbConfig.hostname())),
            )

            // when
            val result = selection.deactivatedProvidersForRun(arrayOf("--only", "anidb"))

            // then
            assertThat(result).doesNotContain(AnidbConfig.hostname())
        }

        @Test
        fun `accepts the equals form`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = selection.deactivatedProvidersForRun(arrayOf("--only=anidb"))

            // then
            assertThat(result).doesNotContain(AnidbConfig.hostname())
            assertThat(result).contains(MyanimelistConfig.hostname())
        }

        @Test
        fun `providers is an alias for only`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = selection.deactivatedProvidersForRun(arrayOf("--providers", "anidb,anime-planet"))

            // then
            assertThat(result).containsExactlyInAnyOrder(
                AnilistConfig.hostname(),
                AnimenewsnetworkConfig.hostname(),
                AnisearchConfig.hostname(),
                KitsuConfig.hostname(),
                LivechartConfig.hostname(),
                MyanimelistConfig.hostname(),
                SimklConfig.hostname(),
            )
            assertThat(result).doesNotContain(AnidbConfig.hostname(), AnimePlanetConfig.hostname())
        }

        @Test
        fun `providers and only are mutually exclusive as aliases`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = exceptionExpected<IllegalArgumentException> {
                selection.deactivatedProvidersForRun(arrayOf("--only", "anidb", "--providers", "kitsu"))
            }

            // then
            assertThat(result).hasMessage("[--providers] must not be passed more than once.")
        }

        @Test
        fun `accepts a full hostname`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = selection.deactivatedProvidersForRun(arrayOf("--only", "anidb.net"))

            // then
            assertThat(result).doesNotContain(AnidbConfig.hostname())
        }
    }

    @Nested
    inner class SkipTests {

        @Test
        fun `skip deactivates the listed providers in addition to config`() {
            // given
            val selection = MetaDataProviderSelection(
                appConfig = testConfig(deactivated = setOf(SimklConfig.hostname())),
            )

            // when
            val result = selection.deactivatedProvidersForRun(arrayOf("--skip", "kitsu"))

            // then
            assertThat(result).containsExactlyInAnyOrder(
                SimklConfig.hostname(),
                KitsuConfig.hostname(),
            )
        }
    }

    @Nested
    inner class InvalidArgumentsTests {

        @Test
        fun `throws an exception for an unknown provider and lists the valid values`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = exceptionExpected<IllegalArgumentException> {
                selection.deactivatedProvidersForRun(arrayOf("--only", "bogus"))
            }

            // then
            assertThat(result).hasMessage(
                "Unknown metadata provider: [bogus]. Valid values: [anidb, anilist, anime-planet, animenewsnetwork, anisearch, kitsu, livechart, myanimelist, simkl]."
            )
        }

        @Test
        fun `throws an exception when only and skip are combined`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = exceptionExpected<IllegalArgumentException> {
                selection.deactivatedProvidersForRun(arrayOf("--only", "anidb", "--skip", "kitsu"))
            }

            // then
            assertThat(result).hasMessage("[--only] and [--skip] are mutually exclusive.")
        }

        @Test
        fun `throws an exception when a flag has no value`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = exceptionExpected<IllegalArgumentException> {
                selection.deactivatedProvidersForRun(arrayOf("--only"))
            }

            // then
            assertThat(result).hasMessage("Missing value for [--only].")
        }

        @Test
        fun `throws an exception for an unknown argument`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = exceptionExpected<IllegalArgumentException> {
                selection.deactivatedProvidersForRun(arrayOf("--bogus"))
            }

            // then
            assertThat(result).hasMessage("Unknown argument: [--bogus].")
        }

        @Test
        fun `throws an exception when a flag is passed more than once`() {
            // given
            val selection = MetaDataProviderSelection(appConfig = testConfig())

            // when
            val result = exceptionExpected<IllegalArgumentException> {
                selection.deactivatedProvidersForRun(arrayOf("--only", "anidb", "--only", "kitsu"))
            }

            // then
            assertThat(result).hasMessage("[--only] must not be passed more than once.")
        }
    }

    private fun testConfig(deactivated: Set<Hostname> = emptySet()): Config = object: Config by TestAppConfig {
        override fun metaDataProviderConfigurations(): Set<MetaDataProviderConfig> = setOf(
            AnidbConfig,
            AnilistConfig,
            AnimePlanetConfig,
            AnimenewsnetworkConfig,
            AnisearchConfig,
            KitsuConfig,
            LivechartConfig,
            MyanimelistConfig,
            SimklConfig,
        )

        override fun deactivatedMetaDataProviders(): Set<Hostname> = deactivated
    }
}
