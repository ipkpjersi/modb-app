package io.github.manamiproject.modb.app

import io.github.manamiproject.AnimenewsnetworkConfig
import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anilist.AnilistConfig
import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.anisearch.AnisearchRelationsConfig
import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.convfiles.DefaultRawFileConversionService
import io.github.manamiproject.modb.app.crawlers.Crawler
import io.github.manamiproject.modb.app.crawlers.anidb.AnidbCrawler
import io.github.manamiproject.modb.app.crawlers.anilist.AnilistCrawler
import io.github.manamiproject.modb.app.crawlers.animenewsnetwork.AnimenewsnetworkCrawler
import io.github.manamiproject.modb.app.crawlers.animeplanet.AnimePlanetCrawler
import io.github.manamiproject.modb.app.crawlers.anisearch.AnisearchCrawler
import io.github.manamiproject.modb.app.crawlers.kitsu.KitsuCrawler
import io.github.manamiproject.modb.app.crawlers.livechart.LivechartCrawler
import io.github.manamiproject.modb.app.crawlers.myanimelist.MyanimelistCrawler
import io.github.manamiproject.modb.app.crawlers.simkl.SimklCrawler
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateUpdater
import io.github.manamiproject.modb.app.extensions.alertDeletedAnimeByTitle
import io.github.manamiproject.modb.app.fluentapi.*
import io.github.manamiproject.modb.app.network.LinuxNetworkController
import io.github.manamiproject.modb.app.network.startFlaresolverr
import io.github.manamiproject.modb.app.network.stopFlaresolverr
import io.github.manamiproject.modb.app.postprocessors.*
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coroutines.CoroutineManager.runCoroutine
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.kitsu.KitsuConfig
import io.github.manamiproject.modb.livechart.LivechartConfig
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JOptionPane.*
import javax.swing.JPasswordField
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

@KoverIgnore
fun main() = runCoroutine {
    val appConfig = AppConfig.instance
    val networkController = LinuxNetworkController.instance
    networkController.sudoPasswordValue = passwordPrompt()
    Runtime.getRuntime().addShutdownHook(Thread { networkController.restore() })
    val flaresolverrContainerId = startFlaresolverr()

    val rawFileConversionService = DefaultRawFileConversionService.instance
    rawFileConversionService.start()

    // Which metadata provider are deactivated is defined by 'deactivatedMetaDataProviders' in config.toml, so a
    // provider can be switched off without a rebuild. Crawlers are created lazily to keep a deactivated provider
    // from being instantiated at all.
    withContext(LIMITED_NETWORK) {
        listOf<Pair<MetaDataProviderConfig, () -> Crawler>>(
            AnidbConfig to { AnidbCrawler.instance },
            AnilistConfig to { AnilistCrawler.instance },
            AnimePlanetConfig to { AnimePlanetCrawler.instance },
            AnimenewsnetworkConfig to { AnimenewsnetworkCrawler.instance },
            AnisearchConfig to { AnisearchCrawler(metaDataProviderConfig = AnisearchConfig) },
            AnisearchRelationsConfig to { AnisearchCrawler(metaDataProviderConfig = AnisearchRelationsConfig) },
            KitsuConfig to { KitsuCrawler.instance },
            LivechartConfig to { LivechartCrawler.instance },
            MyanimelistConfig to { MyanimelistCrawler.instance },
            SimklConfig to { SimklCrawler.instance },
        ).filterNot { (metaDataProviderConfig, _) -> appConfig.isDeactivated(metaDataProviderConfig) }
            .map { (_, crawler) -> launch { crawler().start() } }
            .joinAll()
    }

    rawFileConversionService.waitForAllRawFilesToBeConverted()
    rawFileConversionService.shutdown()

    stopFlaresolverr(flaresolverrContainerId)

    DefaultDownloadControlStateUpdater.instance.updateAll()
    DefaultDownloadControlStateAccessor.instance.allAnime()
        .alertDeletedAnimeByTitle()
        .mergeAnime()
        .removeUnknownEntriesFromRelatedAnime()
        .addAnimeCountdown()
        .transformToDatasetEntries()
        .saveToDataset()
        .updateStatistics()

    listOf(
        NoLockFilesLeftValidationPostProcessor.instance,
        DownloadControlStateWeeksValidationPostProcessor.instance,
        StudiosAndProducersExtractionChecker.instance,
        DuplicatesValidationPostProcessor.instance,
        ZstandardFilesForDeadEntriesCreatorPostProcessor.instance,
        DeadEntriesValidationPostProcessor.instance,
        SourcesConsistencyValidationPostProcessor.instance,
        NumberOfEntriesValidationPostProcessor.instance,
        FileSizePlausibilityValidationPostProcessor.instance,
        DeleteOldDownloadDirectoriesPostProcessor.instance,
        ReleaseInfoFileCreatorPostProcessor.instance
    ).forEach { it.process() }
}

@KoverIgnore
private fun passwordPrompt(): String {
    val console = System.console()
    if (console != null) {
        return String(console.readPassword("sudo password:"))
    }

    return try {
        var ret = EMPTY
        SwingUtilities.invokeAndWait {
            val passwordField = JPasswordField()
            val options = arrayOf<Any>("OK", "Cancel")
            val option = showOptionDialog(
                null,
                passwordField,
                "sudo password:",
                NO_OPTION, PLAIN_MESSAGE,
                null,
                options,
                options[0],
            )
            when (option) {
                0 -> {
                    val passwordArray = passwordField.password
                    ret = String(passwordArray)
                }
                else -> exitProcess(0)
            }
        }
        ret
    } catch (_: Exception) {
        println("sudo password:")
        readlnOrNull() ?: EMPTY
    }
}