package io.github.manamiproject.modb.app.crawlers.simkl

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.convfiles.AlreadyDownloadedIdsFinder
import io.github.manamiproject.modb.app.convfiles.DefaultAlreadyDownloadedIdsFinder
import io.github.manamiproject.modb.app.crawlers.Crawler
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.app.dataset.DefaultDeadEntriesAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateScheduler
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateScheduler
import io.github.manamiproject.modb.app.network.FlaresolverrHttpClient
import io.github.manamiproject.modb.app.network.SuspendableHttpClient
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.excludeFromTestContext
import io.github.manamiproject.modb.core.extensions.*
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.random
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import io.github.manamiproject.modb.simkl.SimklDownloader
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration

/**
 * Crawler for simkl.com.
 *
 * simkl cannot be enumerated by any path (the pagination POST is edge-WAF blocked even for a real browser, the
 * year pages will not paginate, and the public API has no bulk endpoint). Instead the crawler inverts the
 * problem: modb already holds every myanimelist id, so each one is resolved to its simkl id via
 * [SimklMalIdRedirectResolver] (a keyless redirect, no API key), and only the hits are downloaded. Resolving
 * every myanimelist id is a large one-time cost, so [SimklResolvedMalIdsRepository] persists the mal->simkl
 * mapping for good; a myanimelist id is therefore only ever looked up once and later weeks resolve only the ids
 * added since (their cost is download time only). Resolution and download run concurrently - the resolver races
 * ahead against api.simkl.com while the downloader drains hits at the conservative simkl.com rate - so the seed
 * is download-bound. The page download goes through FlareSolverr (the simkl anime page is Cloudflare-protected)
 * and the residential tunnel (simkl bans the datacenter IP).
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property metaDataProviderConfig Configuration for simkl. Uses [SimklConfig] by default.
 * @property malConfig Configuration for myanimelist, whose ids drive the resolution. Uses [MyanimelistConfig].
 * @property downloadControlStateScheduler Determines which entries are scheduled for the current week.
 * @property downloadControlStateAccessor Provides access to the myanimelist ids modb already holds.
 * @property deadEntriesAccessor Access to dead entries.
 * @property alreadyDownloadedIdsFinder Finds ids already downloaded in the current run.
 * @property resolver Resolves a myanimelist id to its simkl id.
 * @property resolvedMalIdsRepository Persists the permanent mal->simkl mapping (hits and misses).
 * @property downloader Downloads the simkl anime page (via FlareSolverr).
 */
class SimklCrawler(
    private val appConfig: Config = AppConfig.instance,
    private val metaDataProviderConfig: MetaDataProviderConfig = SimklConfig,
    private val malConfig: MetaDataProviderConfig = MyanimelistConfig,
    private val downloadControlStateScheduler: DownloadControlStateScheduler = DefaultDownloadControlStateScheduler.instance,
    private val downloadControlStateAccessor: DownloadControlStateAccessor = DefaultDownloadControlStateAccessor.instance,
    private val deadEntriesAccessor: DeadEntriesAccessor = DefaultDeadEntriesAccessor.instance,
    private val alreadyDownloadedIdsFinder: AlreadyDownloadedIdsFinder = DefaultAlreadyDownloadedIdsFinder.instance,
    private val resolver: SimklMalIdRedirectResolver = DefaultSimklMalIdRedirectResolver.instance,
    private val resolvedMalIdsRepository: SimklResolvedMalIdsRepository = DefaultSimklResolvedMalIdsRepository.instance,
    private val downloader: Downloader = SimklDownloader(httpClient = SuspendableHttpClient(httpClient = FlaresolverrHttpClient())),
): Crawler {

    private val entriesNotScheduledForCurrentWeek = hashSetOf<AnimeId>()

    override suspend fun start() {
        log.info { "Starting crawler for [${metaDataProviderConfig.hostname()}]." }

        if (entriesNotScheduledForCurrentWeek.isEmpty()) {
            entriesNotScheduledForCurrentWeek.addAll(downloadControlStateScheduler.findEntriesNotScheduledForCurrentWeek(metaDataProviderConfig))
        }

        downloadEntriesScheduledForCurrentWeek()
        wait()
        discoverAndDownloadNewEntries()

        log.info { "Finished crawling data for [${metaDataProviderConfig.hostname()}]." }
    }

    private suspend fun downloadEntriesScheduledForCurrentWeek() {
        log.info { "Downloading [${metaDataProviderConfig.hostname()}] entries scheduled for the current week." }

        val ids = downloadControlStateScheduler.findEntriesScheduledForCurrentWeek(metaDataProviderConfig) - alreadyDownloadedIdsFinder.alreadyDownloadedIds(metaDataProviderConfig)
        startDownload(ids.toList().createShuffledList())

        log.info { "Finished downloading [${metaDataProviderConfig.hostname()}] entries scheduled for the current week." }
    }

    private suspend fun discoverAndDownloadNewEntries() {
        log.info { "Discovering new [${metaDataProviderConfig.hostname()}] entries by resolving myanimelist ids." }

        val mapping = resolvedMalIdsRepository.loadAll().toMutableMap()
        val malIds = downloadControlStateAccessor.allAnime(malConfig)
            .flatMap { it.sources }
            .filter { it.toString().contains(malConfig.hostname()) }
            .map { malConfig.extractAnimeId(it) }
            .toHashSet()
        // Resolution is a lifetime one-time cost per myanimelist id: only ids not already in the mapping are
        // looked up, so after the initial seed each week resolves just the newly added ids.
        val newMalIds = (malIds - mapping.keys).toList().createShuffledList()

        log.info { "[${newMalIds.size}] new myanimelist ids to resolve against [${metaDataProviderConfig.hostname()}] (of [${malIds.size}] total, [${mapping.size}] already resolved for good)." }

        // simkl ids already seeded into the DCS in prior weeks. Those are refreshed by the scheduled path, not
        // re-downloaded here, so discovery only ever downloads not-yet-seeded hits.
        val existingSimklIds = downloadControlStateAccessor.allAnime(metaDataProviderConfig)
            .flatMap { it.sources }
            .filter { it.toString().contains(metaDataProviderConfig.hostname()) }
            .map { metaDataProviderConfig.extractAnimeId(it) }
            .toHashSet()
        val alreadyDownloaded = alreadyDownloadedIdsFinder.alreadyDownloadedIds(metaDataProviderConfig)
        val enqueued = hashSetOf<AnimeId>()
        var downloadedCount = 0

        coroutineScope {
            // Producer/consumer: the resolver races ahead against api.simkl.com (the faster resolution delay)
            // filling a bounded queue and the permanent mapping, while the downloader drains hits at the
            // conservative simkl.com page rate. Resolution therefore overlaps the download waits, so the seed is
            // download-bound instead of paying both delays in series.
            val toDownload = Channel<AnimeId>(capacity = DOWNLOAD_QUEUE_CAPACITY)

            val downloader = launch {
                for (simklId in toDownload) {
                    downloadEntry(simklId)
                    downloadedCount++
                }
            }

            suspend fun queueForDownload(simklId: AnimeId) {
                val shouldDownload = !existingSimklIds.contains(simklId)
                    && !alreadyDownloaded.contains(simklId)
                    && !entriesNotScheduledForCurrentWeek.contains(simklId)
                    && enqueued.add(simklId)

                if (shouldDownload) {
                    toDownload.send(simklId)
                }
            }

            try {
                // Resume: hits resolved in a previous run but not yet seeded into the DCS (an interrupted seed).
                mapping.values.filterNotNull().forEach { queueForDownload(it) }

                // Resolve each still-unresolved myanimelist id exactly once, recording the result (hit or miss)
                // in the permanent mapping - flushed periodically so a crash never re-resolves from scratch.
                var sinceFlush = 0
                newMalIds.forEach { malId ->
                    waitBeforeResolution()
                    val simklId = resolver.resolve(malId)
                    mapping[malId] = simklId
                    if (simklId != null) {
                        queueForDownload(simklId)
                    }
                    if (++sinceFlush >= RESOLVED_FLUSH_INTERVAL) {
                        resolvedMalIdsRepository.saveAll(mapping)
                        sinceFlush = 0
                    }
                }
            } finally {
                toDownload.close()
                // Persist the mapping even on cancellation (a fail-fast abort) so resolutions done so far are kept.
                withContext(NonCancellable) { resolvedMalIdsRepository.saveAll(mapping) }
            }
            downloader.join()
        }

        log.info { "Finished discovering new [${metaDataProviderConfig.hostname()}] entries; [$downloadedCount] new entries downloaded." }
    }

    private suspend fun startDownload(idDownloadList: List<String>) = idDownloadList.forEach { downloadEntry(it) }

    private suspend fun downloadEntry(animeId: AnimeId) {
        val file = appConfig.workingDir(metaDataProviderConfig).resolve("$animeId.${metaDataProviderConfig.fileSuffix()}")

        wait()

        log.debug { "Downloading [simklId=$animeId]" }

        val response = downloader.download(animeId) {
            deadEntriesAccessor.addDeadEntry(it, metaDataProviderConfig)
        }

        if (response.neitherNullNorBlank()) {
            response.writeToFile(file, true)
        }
    }

    @KoverIgnore
    private suspend fun wait() {
        excludeFromTestContext(metaDataProviderConfig) {
            // Delay before each simkl.com anime-page download (fetched via FlareSolverr). simkl's unauthenticated
            // rate ceiling is unknown and it already IP-banned the datacenter once, so this stays deliberately
            // conservative: random(3000, 4500) ms is ~13.3-20 requests/min. Do NOT go faster without measuring -
            // a silent speedup is what banned anidb and anisearch. The redirect resolution uses its own, faster
            // delay (waitBeforeResolution) because it hits a different host (api.simkl.com).
            delay(random(3000, 4500).toDuration(MILLISECONDS))
        }
    }

    @KoverIgnore
    private suspend fun waitBeforeResolution() {
        excludeFromTestContext(metaDataProviderConfig) {
            // Delay before each api.simkl.com keyless redirect resolution. This is a different host from the
            // page download and a lightweight 301; the feasibility spike saw zero throttling at 2s across the
            // sample, so it does not need the conservative page-download delay: random(2500, 4000) ms is
            // ~15-24 requests/min. Do NOT go faster without measuring.
            delay(random(2500, 4000).toDuration(MILLISECONDS))
        }
    }

    companion object {
        private val log by LoggerDelegate()
        private const val RESOLVED_FLUSH_INTERVAL = 100
        private const val DOWNLOAD_QUEUE_CAPACITY = 100

        /**
         * Singleton of [SimklCrawler]
         * @since 1.0.0
         */
        val instance: SimklCrawler by lazy { SimklCrawler() }
    }
}
