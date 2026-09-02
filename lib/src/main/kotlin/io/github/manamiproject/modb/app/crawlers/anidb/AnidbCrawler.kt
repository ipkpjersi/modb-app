package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anidb.AnidbDownloader
import io.github.manamiproject.modb.anidb.AnidbDownloader.Companion.ANIDB_PENDING_FILE_INDICATOR
import io.github.manamiproject.modb.anidb.AnidbResponseChecker
import io.github.manamiproject.modb.anidb.CrawlerDetectedException
import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.crawlers.Crawler
import io.github.manamiproject.modb.app.crawlers.IdRangeSelector
import io.github.manamiproject.modb.app.crawlers.IntegerBasedIdRangeSelector
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.app.dataset.DefaultDeadEntriesAccessor
import io.github.manamiproject.modb.app.network.CommercialProxyConfig
import io.github.manamiproject.modb.app.network.LinuxNetworkController
import io.github.manamiproject.modb.app.network.NetworkController
import io.github.manamiproject.modb.app.network.SuspendableHttpClient
import io.github.manamiproject.modb.app.network.TunnelConfig
import io.github.manamiproject.modb.app.network.flaresolverrHttpClientFor
import io.github.manamiproject.modb.app.network.isRoutedElsewhere
import io.github.manamiproject.modb.app.network.restartUnlessRouted
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.excludeFromTestContext
import io.github.manamiproject.modb.core.extensions.*
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.ThrowableRetryCase
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.random
import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration

/**
 * Implementation of [Crawler] for `anidb.net`.
 * Uses [IntegerBasedIdRangeSelector] to determine which data to download.
 * Includes a hard coded random waiting time to reduce pressure on the metadata provider.
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property metaDataProviderConfig Configuration for a specific metadata provider.
 * @property deadEntriesAccess Access to dead entries files.
 * @property idRangeSelector Delivers the IDs to download.
 * @property httpClient To actually download the anime data.
 * @property downloader Downloader for a specific metadata provider.
 * @property networkController Rotates this host's outbound IPv6 address. Only used on the direct path.
 * @property tunnelConfig Determines whether anidb exits through the reverse SSH tunnel.
 * @property commercialProxyConfig Determines whether anidb exits through the commercial rotating proxy.
 */
class AnidbCrawler(
    private val appConfig: Config = AppConfig.instance,
    private val metaDataProviderConfig: MetaDataProviderConfig = AnidbConfig,
    private val deadEntriesAccess: DeadEntriesAccessor = DefaultDeadEntriesAccessor.instance,
    private val idRangeSelector: IdRangeSelector<Int> = IntegerBasedIdRangeSelector(
        metaDataProviderConfig = metaDataProviderConfig,
        highestIdDetector = AnidbHighestIdDetector.instance,
    ),
    private val httpClient: HttpClient = SuspendableHttpClient(httpClient = flaresolverrHttpClientFor(metaDataProviderConfig.hostname())),
    private val downloader: Downloader = AnidbDownloader(
        metaDataProviderConfig = metaDataProviderConfig,
        httpClient = httpClient,
    ),
    private val networkController: NetworkController = LinuxNetworkController.instance,
    private val tunnelConfig: TunnelConfig = TunnelConfig.instance,
    private val commercialProxyConfig: CommercialProxyConfig = CommercialProxyConfig.instance,
): Crawler {

    /**
     * Whether anidb exits through the tunnel or the commercial proxy rather than this host's own IP. See
     * [restartUnlessRouted] for why that makes rotating this host's address the wrong recovery.
     */
    private val isRoutedElsewhere: Boolean = isRoutedElsewhere(
        hostname = metaDataProviderConfig.hostname(),
        tunnelConfig = tunnelConfig,
        commercialProxyConfig = commercialProxyConfig,
    )

    init {
        val executeBefore = restartUnlessRouted(
            hostname = metaDataProviderConfig.hostname(),
            networkController = networkController,
            tunnelConfig = tunnelConfig,
            commercialProxyConfig = commercialProxyConfig,
        )
        httpClient.addRetryCases(ThrowableRetryCase(executeBefore = executeBefore) { it is ConnectException})
        httpClient.addRetryCases(ThrowableRetryCase(executeBefore = executeBefore) { it is UnknownHostException})
        httpClient.addRetryCases(ThrowableRetryCase(executeBefore = executeBefore) { it is NoRouteToHostException})
    }

    override suspend fun start() {
        log.info { "Starting crawler for [${metaDataProviderConfig.hostname()}]." }

        val idDownloadList = idRangeSelector.idDownloadList().toMutableList()

        if (idDownloadList.isNotEmpty()) {
            appConfig.workingDir(metaDataProviderConfig).listRegularFiles("*.${ANIDB_PENDING_FILE_SUFFIX}").forEach {
                idDownloadList.remove(it.fileName().remove(".${ANIDB_PENDING_FILE_SUFFIX}").toInt())
            }

            when {
                idDownloadList.isEmpty() -> log.info { "No IDs left for [${metaDataProviderConfig.hostname()}] crawler to download." }
                else -> startDownload(idDownloadList)
            }
        }

        log.info { "Finished crawling data for [${metaDataProviderConfig.hostname()}]." }
    }

    private suspend fun startDownload(idDownloadList: List<Int>) = repeat(idDownloadList.size) { index ->
        val animeId = idDownloadList[index]
        wait()

        log.debug { "Downloading ${index+1}/${idDownloadList.size}: [anidbId=$animeId]" }

        val response = try {
            downloader.download(animeId.toString()) {
                deadEntriesAccess.addDeadEntry(it, metaDataProviderConfig)
            }
        } catch (e: Throwable) {
            when(e) {
                is CrawlerDetectedException -> {
                    when {
                        // AntiLeech flagged the tunnel/proxy exit IP, which this host cannot rotate. Back off
                        // instead, which is the only lever left: on a rotating pool the pause is also what
                        // gives the next request a chance of a fresh exit IP. If anidb is still flagged
                        // afterwards, checkIfCrawlerIsDetected below aborts the run, which is the intended
                        // fail-fast rather than hammering a flagged IP.
                        isRoutedElsewhere -> backOffAfterCrawlerDetected()
                        else -> networkController.restartAsync().await()
                    }
                    val response = downloader.download(animeId.toString()) {
                        deadEntriesAccess.addDeadEntry(it, metaDataProviderConfig)
                    }
                    AnidbResponseChecker(response).checkIfCrawlerIsDetected()
                    response
                }
                else -> throw e
            }
        }

        when {
            response.neitherNullNorBlank() && response != ANIDB_PENDING_FILE_INDICATOR -> {
                val file = appConfig.workingDir(metaDataProviderConfig).resolve("$animeId.${metaDataProviderConfig.fileSuffix()}")
                response.writeToFile(file, true)
            }
            response == ANIDB_PENDING_FILE_INDICATOR -> {
                val pendingFile = appConfig.workingDir(metaDataProviderConfig).resolve("$animeId.${ANIDB_PENDING_FILE_SUFFIX}")
                response.writeToFile(pendingFile, true)
            }
        }
    }

    @KoverIgnore
    private suspend fun backOffAfterCrawlerDetected() {
        log.warn { "[${metaDataProviderConfig.hostname()}] detected the crawler on a routed exit IP. Backing off before the retry." }
        excludeFromTestContext(metaDataProviderConfig) {
            // Mirrors ThrowableRetryCase's default backoff so both recovery paths wait comparably long.
            delay(random(60000, 90000).toDuration(MILLISECONDS))
        }
    }

    @KoverIgnore
    private suspend fun wait() {
        excludeFromTestContext(metaDataProviderConfig) {
            // anidb's binding limit is a DAILY QUOTA per IP (~100-200 requests/day, see TODO item 5), not a
            // per-request rate, so this delay cannot buy throughput. A single IP cannot beat the quota; the
            // commercial rotating residential proxy does (see CommercialProxyConfig and scripts/tunnel/README.md),
            // so anidb can be re-enabled once that is configured. This 2.5-3.5s gap only satisfies anidb's
            // separate flood limit of 2 requests per 5 seconds. Do NOT reduce it, and never go faster than this:
            // item 3's session reuse silently removed the ~19s Cloudflare solve that had been acting as an
            // accidental rate limiter, the rate jumped to ~800 requests/hour, and the residential IP was flagged
            // for AntiLeech.
            delay(random(2500, 3500).toDuration(MILLISECONDS))
        }
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Singleton of [AnidbCrawler]
         * @since 1.0.0
         */
        val instance: AnidbCrawler by lazy { AnidbCrawler() }

        /**
         * Suffix for pending files which have been downloaded, but cannot be converted.
         * @since 7.0.0
         */
        const val ANIDB_PENDING_FILE_SUFFIX: String = "pending"
    }
}