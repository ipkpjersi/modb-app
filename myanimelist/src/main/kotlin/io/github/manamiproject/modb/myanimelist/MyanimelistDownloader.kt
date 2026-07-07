package io.github.manamiproject.modb.myanimelist

import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.extensions.neitherNullNorBlank
import io.github.manamiproject.modb.core.httpclient.*
import io.github.manamiproject.modb.core.logging.LoggerDelegate

/**
 * Downloads anime data from the MyAnimeList v2 API.
 * Requires a MAL API client id set via the `modb.myanimelist.clientId` property (sent as `X-MAL-CLIENT-ID`).
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for downloading data.
 * @property configRegistry Provides the MAL API client id.
 * @property httpClient To actually download the anime data.
 */
public class MyanimelistDownloader(
    private val metaDataProviderConfig: MetaDataProviderConfig = MyanimelistConfig,
    private val configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
    private val httpClient: HttpClient = DefaultHttpClient(isTestContext = metaDataProviderConfig.isTestContext()).apply {
        addRetryCases(
            HttpResponseRetryCase { it.code == 403 },
            HttpResponseRetryCase { it.code == 429 },
            HttpResponseRetryCase { it.code == 500 },
        )
    },
) : Downloader {

    private val clientId: String by StringPropertyDelegate(
        namespace = "modb.myanimelist",
        configRegistry = configRegistry,
    )

    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
        log.debug { "Downloading [myanimelistId=$id]" }

        val url = metaDataProviderConfig.buildDataDownloadLink(id).toURL()
        val response = httpClient.get(
            url = url,
            headers = mapOf("X-MAL-CLIENT-ID" to setOf(clientId)),
        )
        val responseBody = response.bodyAsString()

        check(responseBody.neitherNullNorBlank()) { "Response body was blank for [myanimelistId=$id] with response code [${response.code}]" }

        return when(response.code) {
            200 -> responseBody
            404 -> {
                onDeadEntry.invoke(id)
                EMPTY
            }
            else -> throw IllegalStateException("Unable to determine the correct case for [myanimelistId=$id], [responseCode=${response.code}]")
        }
    }

    public companion object {
        private val log by LoggerDelegate()

        /**
         * Singleton of [MyanimelistDownloader]
         * @since 1.0.0
         */
        public val instance: MyanimelistDownloader by lazy { MyanimelistDownloader() }
    }
}
