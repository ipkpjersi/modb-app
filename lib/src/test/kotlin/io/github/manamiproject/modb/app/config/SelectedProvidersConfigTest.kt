package io.github.manamiproject.modb.app.config

import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.anisearch.AnisearchRelationsConfig
import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.test.Test

internal class SelectedProvidersConfigTest {

    @Nested
    inner class DeactivatedMetaDataProvidersTests {

        @Test
        fun `returns the overriding set instead of the delegate value`() {
            // given
            val delegate = object: Config by TestAppConfig {
                override fun deactivatedMetaDataProviders() = setOf(SimklConfig.hostname())
            }
            val config = SelectedProvidersConfig(
                delegate = delegate,
                deactivatedProviders = setOf(AnisearchConfig.hostname()),
            )

            // when
            val result = config.deactivatedMetaDataProviders()

            // then
            assertThat(result).containsExactly(AnisearchConfig.hostname())
        }
    }

    @Nested
    inner class IsDeactivatedTests {

        @Test
        fun `returns true for a provider in the overriding set and false otherwise`() {
            // given
            val config = SelectedProvidersConfig(
                delegate = TestAppConfig,
                deactivatedProviders = setOf(AnisearchConfig.hostname()),
            )

            // when
            val deactivated = config.isDeactivated(AnisearchConfig)
            val active = config.isDeactivated(MyanimelistConfig)

            // then
            assertThat(deactivated).isTrue()
            assertThat(active).isFalse()
        }

        @Test
        fun `returns true for the anisearch relations config, because it shares the hostname with anisearch`() {
            // given
            val config = SelectedProvidersConfig(
                delegate = TestAppConfig,
                deactivatedProviders = setOf(AnisearchConfig.hostname()),
            )

            // when
            val result = config.isDeactivated(AnisearchRelationsConfig)

            // then
            assertThat(result).isTrue()
        }
    }

    @Nested
    inner class DelegationTests {

        @Test
        fun `delegates every other property to the delegate`() {
            // given
            val delegate = object: Config by TestAppConfig {
                override fun networkInterface(): String = "eth-test"
            }
            val config = SelectedProvidersConfig(
                delegate = delegate,
                deactivatedProviders = emptySet(),
            )

            // when
            val result = config.networkInterface()

            // then
            assertThat(result).isEqualTo("eth-test")
        }
    }
}
