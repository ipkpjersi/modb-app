package io.github.manamiproject.modb.app.crawlers.simkl

import io.github.manamiproject.modb.core.config.AnimeId

/**
 * Resolves a myanimelist.net id to its simkl id.
 *
 * simkl cannot be enumerated by any path (the pagination POST is edge-WAF blocked even for a real browser,
 * the year pages will not paginate, and the public API has no bulk endpoint). But modb already holds every
 * myanimelist id, so it inverts the problem and looks each one up.
 * @since 1.15.0
 */
interface SimklMalIdRedirectResolver {

    /**
     * Resolves a single myanimelist id to its simkl id.
     * @since 1.15.0
     * @param malId The myanimelist id to resolve.
     * @return The simkl id that [malId] maps to, or `null` if simkl has no entry for it.
     */
    suspend fun resolve(malId: AnimeId): AnimeId?
}
