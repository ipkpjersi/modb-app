package io.github.manamiproject.modb.app.crawlers.simkl

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.test.tempDirectory
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import kotlin.io.path.readLines
import kotlin.test.Test

internal class DefaultSimklResolvedMalIdsRepositoryTest {

    @Test
    fun `loadAll returns an empty map when nothing has been stored yet`() {
        tempDirectory {
            // given
            val testAppConfig = object: Config by TestAppConfig {
                override fun downloadControlStateDirectory(): Directory = tempDir
            }
            val repository = DefaultSimklResolvedMalIdsRepository(testAppConfig)

            runTest {
                // when
                val result = repository.loadAll()

                // then
                assertThat(result).isEmpty()
            }
        }
    }

    @Test
    fun `saveAll then loadAll round-trips hits and misses`() {
        tempDirectory {
            // given
            val testAppConfig = object: Config by TestAppConfig {
                override fun downloadControlStateDirectory(): Directory = tempDir
            }
            val repository = DefaultSimklResolvedMalIdsRepository(testAppConfig)

            runTest {
                // when
                repository.saveAll(mapOf("1" to "37089", "2" to null, "20" to "39508"))
                val result = repository.loadAll()

                // then
                assertThat(result).hasSize(3)
                assertThat(result["1"]).isEqualTo("37089")
                assertThat(result["2"]).isNull()
                assertThat(result["20"]).isEqualTo("39508")
            }
        }
    }

    @Test
    fun `saveAll stores hits as mal=simkl and misses as mal alone, sorted one per line`() {
        tempDirectory {
            // given
            val testAppConfig = object: Config by TestAppConfig {
                override fun downloadControlStateDirectory(): Directory = tempDir
            }
            val repository = DefaultSimklResolvedMalIdsRepository(testAppConfig)

            runTest {
                // when
                repository.saveAll(mapOf("30" to "5", "2" to null, "100" to "7"))

                // then
                val lines = tempDir.resolve("simkl-mal-simkl-mapping.txt").readLines()
                assertThat(lines).containsExactly("100=7", "2", "30=5")
            }
        }
    }

    @Test
    fun `saveAll replaces the previously stored mapping`() {
        tempDirectory {
            // given
            val testAppConfig = object: Config by TestAppConfig {
                override fun downloadControlStateDirectory(): Directory = tempDir
            }
            val repository = DefaultSimklResolvedMalIdsRepository(testAppConfig)

            runTest {
                repository.saveAll(mapOf("1" to "10"))

                // when
                repository.saveAll(mapOf("2" to "20"))
                val result = repository.loadAll()

                // then
                assertThat(result).hasSize(1)
                assertThat(result["2"]).isEqualTo("20")
            }
        }
    }
}
