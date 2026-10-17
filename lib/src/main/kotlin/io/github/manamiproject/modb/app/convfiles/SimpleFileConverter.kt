package io.github.manamiproject.modb.app.convfiles

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.converter.PathAnimeConverter
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_FS
import io.github.manamiproject.modb.core.extensions.*
import io.github.manamiproject.modb.core.json.Json
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.anime.AnimeRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * A simple [FileConverter] which allows the conversion of raw files for a specific metadata provider.
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property metaDataProviderConfig Configuration for a specific metadata provider.
 * @property converter Converts a [Path] to instances of [AnimeRaw]. Must match [metaDataProviderConfig].
 */
class SimpleFileConverter(
    private val appConfig: Config = AppConfig.instance,
    private val metaDataProviderConfig: MetaDataProviderConfig,
    private val converter: PathAnimeConverter,
): FileConverter {

    override suspend fun convertUnconvertedFiles(): Unit = withContext(LIMITED_FS) {
        log.info { "Converting unconverted files for [${metaDataProviderConfig.hostname()}]." }

        val workingDir = appConfig.workingDir(metaDataProviderConfig)
        val unconvertedFiles = workingDir.listRegularFiles("*.${metaDataProviderConfig.fileSuffix()}")
            .filterNot { it.changeSuffix(CONVERTED_FILE_SUFFIX).regularFileExists() }

        unconvertedFiles.chunked(250).forEach { chunk ->
            val jobs = chunk.map {
                async {
                    convertOne(it)
                }
            }

            awaitAll(*jobs.toTypedArray())
        }

        log.info { "Finished converting unconverted files for [${metaDataProviderConfig.hostname()}]." }
    }

    override suspend fun convertFileToConvFile(file: RegularFile) {
        log.debug { "Converting [${file.fileName()}] from [${metaDataProviderConfig.hostname()}]" }

        val anime = converter.convert(file).first()
        Json.toJson(anime).writeToFile(createOutputFile(file))
    }

    /**
     * Converts one file, keeping a failure to that file.
     *
     * awaitAll fails the whole batch as soon as any job throws, so without this a single unconvertible file
     * would abort the catch-up pass and leave every later file in the chunk unconverted - the same way one
     * truncated anidb page stopped conversion for a whole run on 2026-10-16. A file which cannot be converted
     * is left without its conv file, so a later run tries it again.
     */
    private suspend fun convertOne(file: RegularFile) {
        try {
            convertFileToConvFile(file)
        } catch (throwable: Throwable) {
            // A cancellation is the run shutting down, not a bad file, so it has to propagate.
            if (throwable is CancellationException) {
                throw throwable
            }

            log.error { "Unable to convert [${file.fileName()}] for [${metaDataProviderConfig.hostname()}], continuing with the remaining files: [${throwable.message}]" }
        }
    }

    private fun createOutputFile(originFile: RegularFile) = originFile.changeSuffix(CONVERTED_FILE_SUFFIX)

    companion object {
        private val log by LoggerDelegate()
    }
}