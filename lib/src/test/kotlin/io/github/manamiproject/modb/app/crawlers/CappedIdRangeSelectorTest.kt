package io.github.manamiproject.modb.app.crawlers

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.routableTestMetaDataProviderConfig
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.test.Test

internal class CappedIdRangeSelectorTest {

    @Nested
    inner class IdDownloadListTests {

        @Test
        fun `returns the full list when no limit is configured`() {
            runTest {
                // given
                val selector = CappedIdRangeSelector(
                    metaDataProviderConfig = routableTestMetaDataProviderConfig(),
                    delegate = selectorOf(1, 2, 3, 4, 5),
                    appConfig = configWithLimit(0),
                )

                // when
                val result = selector.idDownloadList()

                // then
                assertThat(result).containsExactly(1, 2, 3, 4, 5)
            }
        }

        @Test
        fun `truncates to the limit, keeping the delegate's order so progress stays stable`() {
            runTest {
                // given
                val selector = CappedIdRangeSelector(
                    metaDataProviderConfig = routableTestMetaDataProviderConfig(),
                    delegate = selectorOf(10, 20, 30, 40, 50),
                    appConfig = configWithLimit(2),
                )

                // when
                val result = selector.idDownloadList()

                // then
                assertThat(result).containsExactly(10, 20)
            }
        }

        @Test
        fun `returns the full list when it is shorter than the limit`() {
            runTest {
                // given
                val selector = CappedIdRangeSelector(
                    metaDataProviderConfig = routableTestMetaDataProviderConfig(),
                    delegate = selectorOf(1, 2),
                    appConfig = configWithLimit(50),
                )

                // when
                val result = selector.idDownloadList()

                // then
                assertThat(result).containsExactly(1, 2)
            }
        }

        @Test
        fun `returns an empty list without touching the limit`() {
            runTest {
                // given
                val selector = CappedIdRangeSelector(
                    metaDataProviderConfig = routableTestMetaDataProviderConfig(),
                    delegate = selectorOf<Int>(),
                    appConfig = configWithLimit(10),
                )

                // when
                val result = selector.idDownloadList()

                // then
                assertThat(result).isEmpty()
            }
        }

        @Test
        fun `a negative limit is treated as no limit rather than truncating everything`() {
            runTest {
                // given
                // AppConfig rejects a negative value, but the decorator must not silently drop every entry
                // if some other Config implementation lets one through.
                val selector = CappedIdRangeSelector(
                    metaDataProviderConfig = routableTestMetaDataProviderConfig(),
                    delegate = selectorOf(1, 2, 3),
                    appConfig = configWithLimit(-1),
                )

                // when
                val result = selector.idDownloadList()

                // then
                assertThat(result).containsExactly(1, 2, 3)
            }
        }

        @Test
        fun `works for a non-integer id type`() {
            runTest {
                // given
                val selector = CappedIdRangeSelector(
                    metaDataProviderConfig = routableTestMetaDataProviderConfig(),
                    delegate = selectorOf("a", "b", "c"),
                    appConfig = configWithLimit(1),
                )

                // when
                val result = selector.idDownloadList()

                // then
                assertThat(result).containsExactly("a")
            }
        }
    }
}

private fun <T> selectorOf(vararg ids: T) = object : IdRangeSelector<T> {
    override suspend fun idDownloadList(): List<T> = ids.toList()
}

private fun configWithLimit(limit: Int) = object : Config by TestAppConfig {
    override fun maxEntriesPerRun(metaDataProviderConfig: MetaDataProviderConfig): Int = limit
}
