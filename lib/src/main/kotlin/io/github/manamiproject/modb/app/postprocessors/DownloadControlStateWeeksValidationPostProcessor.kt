package io.github.manamiproject.modb.app.postprocessors

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.downloadcontrolstate.*
import io.github.manamiproject.modb.core.date.WeekOfYear
import io.github.manamiproject.modb.core.date.compareTo
import io.github.manamiproject.modb.core.logging.LoggerDelegate

/**
 * Checks that all DCS entries have [DownloadControlStateEntry._nextDownload] set in the future and that
 * [DownloadControlStateEntry._lastDownloaded] is either set to the current week or a week in the past.
 * Supposed to run after updating all DCS entries.
 * Entries of a metadata provider listed in [Config.deactivatedMetaDataProviders] are skipped. No crawler runs for
 * those, so their DCS entries are never updated and their week for the next download passes by design.
 *
 * Entries of a provider with a [Config.maxEntriesPerRun] limit are skipped for the same reason. A capped run
 * downloads only the first n of the ids due this week and leaves the rest for a later run, so by design some
 * of its entries still carry a week in the past when this runs. Checking them would abort every capped run
 * at the very last step, after the dataset has already been written.
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property downloadControlStateAccessor Access to DCS files.
 * @throws IllegalStateException if there are entries that violate the constraint.
 */
class DownloadControlStateWeeksValidationPostProcessor(
    private val appConfig: Config = AppConfig.instance,
    private val downloadControlStateAccessor: DownloadControlStateAccessor = DefaultDownloadControlStateAccessor.instance,
): PostProcessor {

    override suspend fun process(): Boolean {
        log.info { "Validating weeks in DCS entries." }

        val deactivatedMetaDataProviders = appConfig.deactivatedMetaDataProviders()

        if (deactivatedMetaDataProviders.isNotEmpty()) {
            log.info { "Skipping deactivated metadata provider: [${deactivatedMetaDataProviders.sorted().joinToString(", ")}]" }
        }

        // A capped provider cannot satisfy this check by construction: it refreshes only the first n ids due
        // this week, so the ones it skipped still have a next-download week in the past.
        val cappedMetaDataProviders = appConfig.cappedMetaDataProviders()

        if (cappedMetaDataProviders.isNotEmpty()) {
            log.info { "Skipping metadata provider capped by 'maxEntriesPerRun': [${cappedMetaDataProviders.sorted().joinToString(", ")}]" }
        }

        val exemptMetaDataProviders = deactivatedMetaDataProviders + cappedMetaDataProviders

        downloadControlStateAccessor.allDcsEntries()
            .filterNot { it.anime.sources.first().host in exemptMetaDataProviders }
            .forEach {
                check(it.nextDownload > WeekOfYear.currentWeek()) { "Week for next download of ${it.anime.sources} is not set in the future." }
                check(it.lastDownloaded <= WeekOfYear.currentWeek()) { "Week for last download of ${it.anime.sources} is neither current week nor a week of the past." }
            }

        return true
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Singleton of [DownloadControlStateWeeksValidationPostProcessor]
         * @since 1.0.0
         */
        val instance: DownloadControlStateWeeksValidationPostProcessor by lazy { DownloadControlStateWeeksValidationPostProcessor() }
    }
}
