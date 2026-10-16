package io.github.manamiproject.modb.app.crawlers

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.logging.LoggerDelegate

/**
 * Decorator which truncates another [IdRangeSelector]'s download list to the per-provider limit from
 * [Config.maxEntriesPerRun].
 *
 * Needed for provider(s) whose budget is consumed per request rather than per unit of time. anidb enforces a
 * per-IP daily quota, and its traffic leaves through a commercial proxy billed per gigabyte, so an
 * unbounded "download everything due" run is both the wrong shape and expensive to interrupt - the only
 * brake would be killing the process part way through.
 *
 * Truncating is safe because the work is resumable: download control state records what was fetched, so the
 * ids left over are simply picked up by the next run. The list is taken in the order the delegate produced
 * it, which keeps that progression stable rather than re-randomising which ids get dropped.
 *
 * Applied as the default at each crawler's construction site, so it is inert (returns the delegate's list
 * unchanged) for every provider without a configured limit.
 *
 * @since 1.15.0
 * @param ANIME_ID_TYPE Type of the IDs produced by [delegate].
 * @property metaDataProviderConfig Configuration of the metadata provider whose limit applies.
 * @property delegate The [IdRangeSelector] whose list is capped.
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 */
class CappedIdRangeSelector<out ANIME_ID_TYPE>(
    private val metaDataProviderConfig: MetaDataProviderConfig,
    private val delegate: IdRangeSelector<ANIME_ID_TYPE>,
    private val appConfig: Config = AppConfig.instance,
): IdRangeSelector<ANIME_ID_TYPE> {

    override suspend fun idDownloadList(): List<ANIME_ID_TYPE> {
        val ids = delegate.idDownloadList()
        val limit = appConfig.maxEntriesPerRun(metaDataProviderConfig)

        if (limit <= 0 || ids.size <= limit) {
            return ids
        }

        log.info { "Capping [${metaDataProviderConfig.hostname()}] at [$limit] of [${ids.size}] entries for this run. The remaining [${ids.size - limit}] are downloaded by a later run." }

        return ids.take(limit)
    }

    companion object {
        private val log by LoggerDelegate()
    }
}
