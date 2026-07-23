package io.github.manamiproject.modb.app.crawlers.simkl

import io.github.manamiproject.modb.core.config.AnimeId

/**
 * Permanent record of every myanimelist id that has been resolved against simkl's redirect endpoint and what it
 * resolved to - its simkl id, or a recorded miss when simkl has no entry (see [SimklMalIdRedirectResolver]).
 *
 * A given anime's myanimelist id and simkl id never change, so this mapping is effectively immutable once
 * written: resolution is a one-time operation per myanimelist id. Persisting the whole mapping - not just which
 * ids were resolved - lets the download run entirely off it (and resume) without ever re-resolving: hits that
 * were resolved but not yet downloaded are re-queued from here on the next run. Both hits and misses are
 * recorded, so a myanimelist id simkl had no entry for is not retried every week; forcing a full re-resolve
 * (for example to pick up anime simkl has since added) is done by deleting the underlying store.
 *
 * Stored in the download control state directory because it must persist across weeks, unlike the per-week
 * working-directory state used by the pagination-based crawlers.
 * @since 1.15.0
 */
interface SimklResolvedMalIdsRepository {

    /**
     * Loads the complete mapping of resolved myanimelist ids to their simkl id.
     * @since 1.15.0
     * @return Map of myanimelist id to simkl id. A `null` value is a recorded miss (simkl has no entry for that
     * myanimelist id); a key being present at all means it has already been resolved. Empty if nothing has been
     * resolved yet.
     */
    suspend fun loadAll(): Map<AnimeId, AnimeId?>

    /**
     * Persists the complete mapping, replacing anything stored before.
     * @since 1.15.0
     * @param mapping Myanimelist id to simkl id (or `null` for a recorded miss).
     */
    suspend fun saveAll(mapping: Map<AnimeId, AnimeId?>)
}
