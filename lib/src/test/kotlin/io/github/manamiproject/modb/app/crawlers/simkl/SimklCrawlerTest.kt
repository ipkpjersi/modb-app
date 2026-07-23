package io.github.manamiproject.modb.app.crawlers.simkl

import io.github.manamiproject.modb.app.*
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.convfiles.AlreadyDownloadedIdsFinder
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateScheduler
import io.github.manamiproject.modb.core.anime.AnimeRaw
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.core.extensions.regularFileExists
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import io.github.manamiproject.modb.test.tempDirectory
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import java.net.URI
import kotlin.test.Test

internal class SimklCrawlerTest {

    private val simklTestConfig = object: MetaDataProviderConfig by SimklConfig {
        override fun isTestContext(): Boolean = true
    }

    private fun malEntry(id: AnimeId) = AnimeRaw(
        _title = "myanimelist $id",
        _sources = hashSetOf(URI("https://myanimelist.net/anime/$id")),
    )

    private fun simklEntry(id: AnimeId) = AnimeRaw(
        _title = "simkl $id",
        _sources = hashSetOf(URI("https://simkl.com/anime/$id")),
    )

    private fun testAppConfig(dir: Directory) = object: Config by TestAppConfig {
        override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = dir
    }

    private fun emptyScheduler() = object: DownloadControlStateScheduler by TestDownloadControlStateScheduler {
        override suspend fun findEntriesScheduledForCurrentWeek(metaDataProviderConfig: MetaDataProviderConfig): Set<AnimeId> = emptySet()
        override suspend fun findEntriesNotScheduledForCurrentWeek(metaDataProviderConfig: MetaDataProviderConfig): Set<AnimeId> = emptySet()
    }

    private fun accessor(malAnime: List<AnimeRaw> = emptyList(), simklAnime: List<AnimeRaw> = emptyList()) =
        object: DownloadControlStateAccessor by TestDownloadControlStateAccessor {
            override suspend fun allAnime(metaDataProviderConfig: MetaDataProviderConfig): List<AnimeRaw> = when (metaDataProviderConfig.hostname()) {
                MyanimelistConfig.hostname() -> malAnime
                else -> simklAnime
            }
        }

    private fun noneDownloaded() = object: AlreadyDownloadedIdsFinder by TestAlreadyDownloadedIdsFinder {
        override suspend fun alreadyDownloadedIds(metaDataProviderConfig: MetaDataProviderConfig): Set<AnimeId> = emptySet()
    }

    private fun downloadingDownloader() = object: Downloader by TestDownloader {
        override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String = "entry $id"
    }

    @Test
    fun `does nothing when nothing is scheduled and there are no new myanimelist ids`() {
        tempDirectory {
            // given
            val testRepository = object: SimklResolvedMalIdsRepository by TestSimklResolvedMalIdsRepository {
                override suspend fun loadAll(): Map<AnimeId, AnimeId?> = emptyMap()
                override suspend fun saveAll(mapping: Map<AnimeId, AnimeId?>) {}
            }

            val crawler = SimklCrawler(
                appConfig = testAppConfig(tempDir),
                metaDataProviderConfig = simklTestConfig,
                malConfig = MyanimelistConfig,
                downloadControlStateScheduler = emptyScheduler(),
                downloadControlStateAccessor = accessor(),
                deadEntriesAccessor = TestDeadEntriesAccessor,
                alreadyDownloadedIdsFinder = noneDownloaded(),
                resolver = TestSimklMalIdRedirectResolver, // must not be invoked
                resolvedMalIdsRepository = testRepository,
                downloader = TestDownloader, // must not be invoked
            )

            // when
            assertThatNoException().isThrownBy {
                runTest { crawler.start() }
            }

            // then
            assertThat(tempDir).isEmptyDirectory()
        }
    }

    @Test
    fun `downloads entries scheduled for the current week`() {
        tempDirectory {
            // given
            val testScheduler = object: DownloadControlStateScheduler by TestDownloadControlStateScheduler {
                override suspend fun findEntriesScheduledForCurrentWeek(metaDataProviderConfig: MetaDataProviderConfig): Set<AnimeId> = setOf("37089", "41084")
                override suspend fun findEntriesNotScheduledForCurrentWeek(metaDataProviderConfig: MetaDataProviderConfig): Set<AnimeId> = emptySet()
            }
            val testRepository = object: SimklResolvedMalIdsRepository by TestSimklResolvedMalIdsRepository {
                override suspend fun loadAll(): Map<AnimeId, AnimeId?> = emptyMap()
                override suspend fun saveAll(mapping: Map<AnimeId, AnimeId?>) {}
            }

            val crawler = SimklCrawler(
                appConfig = testAppConfig(tempDir),
                metaDataProviderConfig = simklTestConfig,
                malConfig = MyanimelistConfig,
                downloadControlStateScheduler = testScheduler,
                downloadControlStateAccessor = accessor(),
                deadEntriesAccessor = TestDeadEntriesAccessor,
                alreadyDownloadedIdsFinder = noneDownloaded(),
                resolver = TestSimklMalIdRedirectResolver, // no new mal ids -> not invoked
                resolvedMalIdsRepository = testRepository,
                downloader = downloadingDownloader(),
            )

            // when
            assertThatNoException().isThrownBy {
                runTest { crawler.start() }
            }

            // then
            assertThat(tempDir.resolve("37089.${simklTestConfig.fileSuffix()}").regularFileExists()).isTrue()
            assertThat(tempDir.resolve("41084.${simklTestConfig.fileSuffix()}").regularFileExists()).isTrue()
        }
    }

    @Test
    fun `resolves new myanimelist ids once, records the mapping, and downloads only the hits`() {
        tempDirectory {
            // given
            val testResolver = object: SimklMalIdRedirectResolver by TestSimklMalIdRedirectResolver {
                override suspend fun resolve(malId: AnimeId): AnimeId? = when (malId) {
                    "1" -> "37089"
                    "3" -> "41084"
                    else -> null // myanimelist id 2 has no simkl entry
                }
            }
            var savedMapping: Map<AnimeId, AnimeId?> = emptyMap()
            val testRepository = object: SimklResolvedMalIdsRepository by TestSimklResolvedMalIdsRepository {
                override suspend fun loadAll(): Map<AnimeId, AnimeId?> = emptyMap()
                override suspend fun saveAll(mapping: Map<AnimeId, AnimeId?>) { savedMapping = mapping }
            }

            val crawler = SimklCrawler(
                appConfig = testAppConfig(tempDir),
                metaDataProviderConfig = simklTestConfig,
                malConfig = MyanimelistConfig,
                downloadControlStateScheduler = emptyScheduler(),
                downloadControlStateAccessor = accessor(malAnime = listOf(malEntry("1"), malEntry("2"), malEntry("3"))),
                deadEntriesAccessor = TestDeadEntriesAccessor,
                alreadyDownloadedIdsFinder = noneDownloaded(),
                resolver = testResolver,
                resolvedMalIdsRepository = testRepository,
                downloader = downloadingDownloader(),
            )

            // when
            assertThatNoException().isThrownBy {
                runTest { crawler.start() }
            }

            // then
            assertThat(tempDir.resolve("37089.${simklTestConfig.fileSuffix()}").regularFileExists()).isTrue()
            assertThat(tempDir.resolve("41084.${simklTestConfig.fileSuffix()}").regularFileExists()).isTrue()
            // every processed id is recorded permanently - hits mapped to their simkl id, the miss to null
            assertThat(savedMapping).hasSize(3)
            assertThat(savedMapping["1"]).isEqualTo("37089")
            assertThat(savedMapping["2"]).isNull()
            assertThat(savedMapping["3"]).isEqualTo("41084")
        }
    }

    @Test
    fun `never re-resolves a myanimelist id already in the mapping`() {
        tempDirectory {
            // given
            val resolveCalls = mutableListOf<AnimeId>()
            val testResolver = object: SimklMalIdRedirectResolver by TestSimklMalIdRedirectResolver {
                override suspend fun resolve(malId: AnimeId): AnimeId? {
                    resolveCalls.add(malId)
                    return "55555"
                }
            }
            val testRepository = object: SimklResolvedMalIdsRepository by TestSimklResolvedMalIdsRepository {
                override suspend fun loadAll(): Map<AnimeId, AnimeId?> = mapOf("1" to "37089", "2" to null) // already resolved for good
                override suspend fun saveAll(mapping: Map<AnimeId, AnimeId?>) {}
            }

            val crawler = SimklCrawler(
                appConfig = testAppConfig(tempDir),
                metaDataProviderConfig = simklTestConfig,
                malConfig = MyanimelistConfig,
                downloadControlStateScheduler = emptyScheduler(),
                downloadControlStateAccessor = accessor(
                    malAnime = listOf(malEntry("1"), malEntry("2"), malEntry("3")),
                    simklAnime = listOf(simklEntry("37089")), // 37089 already seeded -> not re-downloaded
                ),
                deadEntriesAccessor = TestDeadEntriesAccessor,
                alreadyDownloadedIdsFinder = noneDownloaded(),
                resolver = testResolver,
                resolvedMalIdsRepository = testRepository,
                downloader = downloadingDownloader(),
            )

            // when
            assertThatNoException().isThrownBy {
                runTest { crawler.start() }
            }

            // then
            assertThat(resolveCalls).containsExactly("3") // 1 and 2 are already in the mapping
            assertThat(tempDir.resolve("55555.${simklTestConfig.fileSuffix()}").regularFileExists()).isTrue()
            // 37089 is already seeded in the DCS, so discovery does not re-download it
            assertThat(tempDir.resolve("37089.${simklTestConfig.fileSuffix()}").regularFileExists()).isFalse()
        }
    }

    @Test
    fun `re-queues a resolved-but-not-yet-seeded hit for download on a later run`() {
        tempDirectory {
            // given: mapping has a hit whose simkl entry is NOT yet in the DCS (an interrupted earlier seed)
            val testRepository = object: SimklResolvedMalIdsRepository by TestSimklResolvedMalIdsRepository {
                override suspend fun loadAll(): Map<AnimeId, AnimeId?> = mapOf("1" to "37089")
                override suspend fun saveAll(mapping: Map<AnimeId, AnimeId?>) {}
            }

            val crawler = SimklCrawler(
                appConfig = testAppConfig(tempDir),
                metaDataProviderConfig = simklTestConfig,
                malConfig = MyanimelistConfig,
                downloadControlStateScheduler = emptyScheduler(),
                downloadControlStateAccessor = accessor(
                    malAnime = listOf(malEntry("1")),
                    simklAnime = emptyList(), // 37089 was never seeded
                ),
                deadEntriesAccessor = TestDeadEntriesAccessor,
                alreadyDownloadedIdsFinder = noneDownloaded(),
                resolver = TestSimklMalIdRedirectResolver, // mal id 1 already mapped -> not invoked
                resolvedMalIdsRepository = testRepository,
                downloader = downloadingDownloader(),
            )

            // when
            assertThatNoException().isThrownBy {
                runTest { crawler.start() }
            }

            // then
            assertThat(tempDir.resolve("37089.${simklTestConfig.fileSuffix()}").regularFileExists()).isTrue()
        }
    }
}
