package io.github.manamiproject.modb.app.crawlers.simkl

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_FS
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.extensions.regularFileExists
import io.github.manamiproject.modb.core.extensions.writeToFile
import kotlinx.coroutines.withContext
import kotlin.io.path.readLines

/**
 * Default [SimklResolvedMalIdsRepository] backed by a plain text file, one resolved myanimelist id per line:
 * `<malId>=<simklId>` for a hit, or just `<malId>` for a recorded miss.
 *
 * Stored in the download control state directory because the mapping must persist across weeks for good - it is
 * a lifetime record so that a myanimelist id is only ever looked up against simkl once.
 * @since 1.15.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 */
class DefaultSimklResolvedMalIdsRepository(
    private val appConfig: Config = AppConfig.instance,
): SimklResolvedMalIdsRepository {

    private fun file() = appConfig.downloadControlStateDirectory().resolve(FILE_NAME)

    override suspend fun loadAll(): Map<AnimeId, AnimeId?> = withContext(LIMITED_FS) {
        val file = file()
        return@withContext when {
            file.regularFileExists() -> file.readLines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .associate { line ->
                    val separatorIndex = line.indexOf(SEPARATOR)
                    when {
                        separatorIndex < 0 -> line to null
                        else -> line.substring(0, separatorIndex) to line.substring(separatorIndex + 1).ifBlank { null }
                    }
                }
            else -> emptyMap()
        }
    }

    override suspend fun saveAll(mapping: Map<AnimeId, AnimeId?>) {
        withContext(LIMITED_FS) {
            mapping.entries
                .sortedBy { it.key }
                .joinToString("\n") { (malId, simklId) -> if (simklId == null) malId else "$malId$SEPARATOR$simklId" }
                .writeToFile(file())
        }
    }

    companion object {
        private const val FILE_NAME = "simkl-mal-simkl-mapping.txt"
        private const val SEPARATOR = "="

        /**
         * Singleton of [DefaultSimklResolvedMalIdsRepository]
         * @since 1.15.0
         */
        @KoverIgnore
        val instance: DefaultSimklResolvedMalIdsRepository by lazy { DefaultSimklResolvedMalIdsRepository() }
    }
}
