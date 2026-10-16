package io.github.manamiproject.modb.app.config

import io.github.manamiproject.AnimenewsnetworkConfig
import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anilist.AnilistConfig
import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.anisearch.AnisearchRelationsConfig
import io.github.manamiproject.modb.app.crawlers.animeplanet.AnimePlanetPaginationIdRangeSelectorConfig
import io.github.manamiproject.modb.app.crawlers.livechart.LivechartPaginationIdRangeSelectorConfig
import io.github.manamiproject.modb.core.date.weekOfYear
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.config.SetPropertyDelegate
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.core.extensions.directoryExists
import io.github.manamiproject.modb.core.extensions.regularFileExists
import io.github.manamiproject.modb.kitsu.KitsuConfig
import io.github.manamiproject.modb.livechart.LivechartConfig
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import java.time.LocalDate
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

/**
 * Implementation of [Config] which contains all necessary properties and all derivative configurations.
 * @since 1.0.0
 * @param configRegistry Implementation of [ConfigRegistry] used for populating properties. Uses [DefaultConfigRegistry] by default.
 */
class AppConfig(
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
): Config {

    private val weekOfYear = LocalDate.now(clock()).weekOfYear()

    private val downloadsDirectory: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        configRegistry = configRegistry,
    )

    private val outputDirectory: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        configRegistry = configRegistry,
    )

    private val downloadControlStateDirectory: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        configRegistry = configRegistry,
    )

    private val networkInterface: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        configRegistry = configRegistry,
    )

    private val ipv6Prefix: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        configRegistry = configRegistry,
    )

    private val deactivatedMetaDataProviders: Set<Hostname> by SetPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        configRegistry = configRegistry,
        default = emptySet(),
    )

    private val deactivatedCommercialMetaDataProviders: Set<Hostname> by SetPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        configRegistry = configRegistry,
        default = emptySet(),
    )

    /**
     * Per-provider entry caps as `hostname=limit` entries, for example `["anidb.net=100"]`. A provider which
     * is not listed is uncapped. Per-provider rather than one global value because the limit is specific to
     * the provider: anidb needs one, the API-backed provider(s) do not.
     *
     * A list of strings rather than a TOML map, because [DefaultConfigRegistry] flattens an inline table
     * into individual dotted properties - so `maxEntriesPerRun = { "anidb.net" = 100 }` is stored as the
     * property `modb.app.maxEntriesPerRun.anidb.net` and a map lookup on `modb.app.maxEntriesPerRun`
     * returns `null`. A list is read back intact, exactly as `deactivatedMetaDataProviders` is.
     */
    private val maxEntriesPerRun: Set<String> by SetPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        configRegistry = configRegistry,
        default = emptySet(),
    )

    override fun downloadsDirectory(): Directory {
        val dir = Path(downloadsDirectory)
        check(dir.directoryExists()) { "Download directory set by 'downloadsDirectory' to [$downloadsDirectory] doesn't exist or is not a directory." }
        return dir
    }

    override fun currentWeekWorkingDir(): Directory {
        val zeroBasedWeek = if (weekOfYear.week < 10) "0${weekOfYear.week}" else weekOfYear.week.toString()
        return downloadsDirectory().resolve("${weekOfYear.year}-$zeroBasedWeek")
    }

    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory {
        val hostname = metaDataProviderConfig.hostname()
        val workingDir = when(metaDataProviderConfig) {
            AnidbConfig,
            AnilistConfig,
            AnimePlanetConfig,
            AnimePlanetPaginationIdRangeSelectorConfig,
            AnimenewsnetworkConfig,
            AnisearchConfig,
            KitsuConfig,
            LivechartConfig,
            LivechartPaginationIdRangeSelectorConfig,
            MyanimelistConfig,
            SimklConfig -> currentWeekWorkingDir().resolve(hostname)
            AnisearchRelationsConfig -> currentWeekWorkingDir().resolve("$hostname-relations")
            else -> throw IllegalStateException("No working directory mapping for [${metaDataProviderConfig::class.simpleName}]")
        }

        check(!workingDir.regularFileExists()) { "Working directory must not be a regular file." }

        if (!workingDir.directoryExists()) {
            workingDir.createDirectories()
        }

        return workingDir
    }

    override fun outputDirectory(): Directory {
        val dir = Path(outputDirectory)
        check(dir.directoryExists()) { "Output directory set by 'outputDirectory' to [$outputDirectory] doesn't exist or is not a directory." }
        return dir
    }

    override fun downloadControlStateDirectory(): Directory {
        val dir = Path(downloadControlStateDirectory)
        check(dir.directoryExists()) { "Output directory set by 'downloadControlStateDirectory' to [$downloadControlStateDirectory] doesn't exist or is not a directory." }
        return dir
    }

    override fun networkInterface(): String {
        check(networkInterface.isNotBlank()) { "Network interface set by 'networkInterface' must not be blank." }
        return networkInterface
    }

    override fun ipv6Prefix(): String {
        check(ipv6Prefix.isNotBlank()) { "IPv6 prefix set by 'ipv6Prefix' must not be blank." }
        return ipv6Prefix
    }

    override fun deactivatedMetaDataProviders(): Set<Hostname> {
        val knownHostnames = metaDataProviderConfigurations().map { it.hostname() }.toSet()
        val unknownHostnames = deactivatedMetaDataProviders - knownHostnames

        check(unknownHostnames.isEmpty()) { "Unknown hostnames set by 'deactivatedMetaDataProviders': [${unknownHostnames.sorted().joinToString(", ")}]." }

        return deactivatedMetaDataProviders
    }

    override fun maxEntriesPerRun(metaDataProviderConfig: MetaDataProviderConfig): Int =
        validatedMaxEntriesPerRun()[metaDataProviderConfig.hostname()] ?: 0

    override fun cappedMetaDataProviders(): Set<Hostname> =
        validatedMaxEntriesPerRun().filterValues { it > 0 }.keys

    /**
     * Parses and validates the configured limits. Both accessors go through this, so a broken configuration
     * is rejected no matter which of them is consulted first.
     */
    private fun validatedMaxEntriesPerRun(): Map<Hostname, Int> {
        val limits = parseHostnameLimits("maxEntriesPerRun", maxEntriesPerRun)
        val knownHostnames = metaDataProviderConfigurations().map { it.hostname() }.toSet()
        val unknownHostnames = limits.keys - knownHostnames

        check(unknownHostnames.isEmpty()) { "Unknown hostnames set by 'maxEntriesPerRun': [${unknownHostnames.sorted().joinToString(", ")}]." }

        // A cap only takes effect for a crawler which resolves its whole id list up front. The paginating
        // crawler(s) discover ids page by page and memorize the last completed page, so truncating them would
        // risk recording a page as done that was not fully downloaded. Rejecting the configuration is far
        // better than accepting a limit and silently not applying it, since the whole point of the limit is
        // to bound spend on a metered or quota-limited provider.
        val unsupportedHostnames = limits.keys - CAP_CAPABLE_HOSTNAMES

        check(unsupportedHostnames.isEmpty()) {
            "'maxEntriesPerRun' is not supported for [${unsupportedHostnames.sorted().joinToString(", ")}] - only [${CAP_CAPABLE_HOSTNAMES.sorted().joinToString(", ")}] resolve their id list up front and can honor a cap."
        }

        return limits
    }

    override fun deactivatedCommercialMetaDataProviders(): Set<Hostname> {
        val knownHostnames = metaDataProviderConfigurations().map { it.hostname() }.toSet()
        val unknownHostnames = deactivatedCommercialMetaDataProviders - knownHostnames

        check(unknownHostnames.isEmpty()) { "Unknown hostnames set by 'deactivatedCommercialMetaDataProviders': [${unknownHostnames.sorted().joinToString(", ")}]." }

        return deactivatedCommercialMetaDataProviders
    }

    companion object {
        /**
         * Namespace under which configuration properties are being placed.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE = "modb.app"

        /**
         * Hostnames whose crawler builds its download list through
         * [io.github.manamiproject.modb.app.crawlers.CappedIdRangeSelector] and can therefore honor
         * `maxEntriesPerRun`. Kept here rather than imported from the crawler package to avoid a dependency
         * from config back onto crawlers; keep the two in step when a crawler is wired or unwired.
         */
        private val CAP_CAPABLE_HOSTNAMES = setOf(
            "anidb.net",
            "anilist.co",
            "kitsu.app",
            "myanimelist.net",
        )

        /**
         * Singleton of [AppConfig]
         * @since 1.0.0
         */
        val instance: AppConfig by lazy { AppConfig() }
    }
}