package io.github.manamiproject.modb.app.config

import io.github.manamiproject.AnimenewsnetworkConfig
import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anilist.AnilistConfig
import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.core.config.ContextAware
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.kitsu.KitsuConfig
import io.github.manamiproject.modb.livechart.LivechartConfig
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import java.time.Clock

/**
 * Configuration options that are specific for the application.
 * @since 1.0.0
 */
interface Config: ContextAware {

    /**
     * Determines the directory in which the raw data and the converted files are stored.
     * The is just the root directory. It doesn't contain files. It contains directories for each week.
     * @since 1.0.0
     * @see currentWeekWorkingDir
     * @return Directory containing a directory for every week.
     */
    fun downloadsDirectory(): Directory

    /**
     * Subdirectory of [downloadsDirectory]. Contains raw data and the converted files for the current week of the year.
     * This directory doesn't contain files. It contains directories for each metadata provider.
     * @since 1.0.0
     * @see downloadsDirectory
     * @see workingDir
     * @return Directory containing a directory for every metadata provider.
     */
    fun currentWeekWorkingDir(): Directory

    /**
     * Retrieve the current working directory for a specific metadata provider.
     * This directory contains the raw and converted files.
     * @since 1.0.0
     * @param metaDataProviderConfig Configuration for a specific metadata provider.
     * @see currentWeekWorkingDir
     * @return
     */
    fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory

    /**
     * List of all configurations for all metadata provider.
     * @since 1.0.0
     * @return Duplicate free list of all metadata provider configs.
     */
    fun metaDataProviderConfigurations(): Set<MetaDataProviderConfig> = setOf(
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

    /**
     * Hostnames of metadata provider which are currently deactivated.
     * No crawler is started for a deactivated metadata provider, which means that it doesn't produce any files in
     * [workingDir] and that its DCS entries are not updated. Data which has been downloaded before the metadata
     * provider was deactivated is still merged into the dataset. Consequently the DCS entries of a deactivated
     * metadata provider keep the schedule of their last successful download and their week for the next download
     * inevitably passes. They are therefore excluded from
     * [io.github.manamiproject.modb.app.postprocessors.DownloadControlStateWeeksValidationPostProcessor].
     * @since 1.13.0
     * @return Hostnames of all metadata provider which are currently deactivated.
     */
    fun deactivatedMetaDataProviders(): Set<Hostname> = emptySet()

    /**
     * Checks whether a metadata provider is currently deactivated.
     * @since 1.13.0
     * @param metaDataProviderConfig Configuration for a specific metadata provider.
     * @see deactivatedMetaDataProviders
     * @return `true` if no crawler is supposed to run for the given metadata provider.
     */
    fun isDeactivated(metaDataProviderConfig: MetaDataProviderConfig): Boolean = deactivatedMetaDataProviders().contains(metaDataProviderConfig.hostname())

    /**
     * Hostnames of commercial provider(s) which are turned off for this run. Parallel to
     * [deactivatedMetaDataProviders] but scoped to provider(s) routed through the commercial proxy (see
     * [io.github.manamiproject.modb.app.network.CommercialProxyConfig]): it is the dedicated place to disable
     * them, for example while the commercial proxy is not configured yet. A provider listed here is folded
     * into the run's deactivated set, so it does not crawl at all.
     * @since 1.15.0
     * @return Hostnames of commercial provider(s) which are turned off.
     */
    fun deactivatedCommercialMetaDataProviders(): Set<Hostname> = emptySet()

    /**
     * Maximum number of entries a single run may download for a metadata provider, or `0` for no limit.
     *
     * Exists for provider(s) whose budget is consumed per request rather than per unit of time: anidb
     * enforces a per-IP daily quota, and a commercial proxy bills per gigabyte, so "crawl everything due"
     * is the wrong default there. A capped run stops cleanly having downloaded the first [maxEntriesPerRun]
     * ids, and the remainder are picked up by the next run - download control state already makes the work
     * resumable.
     *
     * **Default** is `0` for every provider, so an unconfigured host behaves exactly as before.
     * @since 1.15.0
     * @param metaDataProviderConfig Configuration of the metadata provider.
     * @return Maximum entries to download in this run, or `0` for unlimited.
     */
    fun maxEntriesPerRun(metaDataProviderConfig: MetaDataProviderConfig): Int = 0

    /**
     * Hostnames of the metadata provider(s) which have a [maxEntriesPerRun] limit.
     *
     * A capped provider refreshes only the first n of the ids due this week, so the rest keep a
     * next-download week in the past by design - which is why
     * [io.github.manamiproject.modb.app.postprocessors.DownloadControlStateWeeksValidationPostProcessor]
     * has to skip them, exactly as it skips [deactivatedMetaDataProviders].
     *
     * **Default** is empty.
     * @since 1.15.0
     * @return Hostnames of the provider(s) with an entry limit configured.
     */
    fun cappedMetaDataProviders(): Set<Hostname> = emptySet()

    /**
     * Finds a specific [MetaDataProviderConfig] for a given hostname.
     * @since 1.0.0
     * @param host Hostname of a metadata provider
     * @return The corresponding [MetaDataProviderConfig]
     * @throws IllegalArgumentException if no [MetaDataProviderConfig] can be found for the given [host].
     */
    fun findMetaDataProviderConfig(host: Hostname) = metaDataProviderConfigurations().find { it.hostname() == host } ?: throw IllegalArgumentException("No config found for [$host]")

    /**
     * Directory for the git repository of anime-offline-database.
     * This is the target directory in which the finalized files are being written to.
     * @since 1.0.0
     * @return The directory in which the JSON files and the `README.md` are saved.
     */
    fun outputDirectory(): Directory

    /**
     * Root directory of download control state files.
     * @since 1.0.0
     * @return Directory which contains subdirectories for all download control state files.
     */
    fun downloadControlStateDirectory(): Directory

    /**
     * Name of the network interface used for crawling, for example `eth0`.
     * The [io.github.manamiproject.modb.app.network.NetworkController] rotates the outbound IPv6 source address on this
     * interface whenever a metadata provider starts rate limiting requests.
     * @since 18.0.0
     * @return Name of the network interface.
     */
    fun networkInterface(): String

    /**
     * Routed IPv6 `/64` prefix available on [networkInterface] given in CIDR notation, for example
     * `2001:db8:1234:5678::/64`. Every address within this prefix must be routed to the machine so that it can be used
     * as an outbound source address for crawling.
     * @since 18.0.0
     * @return IPv6 `/64` prefix in CIDR notation.
     */
    fun ipv6Prefix(): String

    /**
     * Determines if anime of a specific metadata provider can change their Ids.
     * This is most likely to happen if the ID is a SEO optimized title.
     * @since 1.0.0
     * @param metaDataProviderConfig Configuration for a specific metadata provider.
     * @return `true` if the anime ids can change for the given metadata provider.
     */
    fun canChangeAnimeIds(metaDataProviderConfig: MetaDataProviderConfig): Boolean = setOf(
        AnimePlanetConfig
    ).contains(metaDataProviderConfig)

    /**
     * Checks if tracking IDs for anime which cannot be downloaded is supported for a specific metadata provider.
     * @since 1.0.0
     * @param metaDataProviderConfig Configuration for a specific metadata provider.
     * @return `true` if the metadata provider has its own dead entries file.
     */
    fun deadEntriesSupported(metaDataProviderConfig: MetaDataProviderConfig): Boolean = setOf(
        AnidbConfig,
        AnilistConfig,
        AnimenewsnetworkConfig,
        KitsuConfig,
        MyanimelistConfig,
    ).contains(metaDataProviderConfig)

    /**
     * Clock being used whenever dates and timestamps are created.
     * Default is system default zone.
     * @since 1.0.0
     * @return Instance of [Clock] as basis for any type of date instances.
     */
    fun clock(): Clock = Clock.systemDefaultZone()
}