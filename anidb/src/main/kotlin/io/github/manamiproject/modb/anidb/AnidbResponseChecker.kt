package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.BooleanPropertyDelegate
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_CPU
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.extractor.DataExtractor
import io.github.manamiproject.modb.core.extractor.XmlDataExtractor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/**
 * Thrown when a response is a syntactically fine HTML page which is missing the anime data it should carry.
 *
 * Distinct from a network error, because the request succeeded: the browser simply captured the DOM before
 * the page had finished rendering. On 2026-10-16 anidb id 396 was saved that way at 12,908 bytes against a
 * 203 KB average, and re-fetching the same id minutes later returned a complete 243 KB page, so this is
 * transient and worth one retry.
 * @since 1.16.0
 * @param id Id of the anime whose response was incomplete.
 */
public class IncompleteResponseException(id: AnimeId) : RuntimeException(
    "Response for [anidbId=$id] is missing the anime info table - the page was most likely captured before it finished rendering."
)

/**
 * Thrown in case the crawler has been detected.
 * @since 2.1.0
 */
public object CrawlerDetectedException : RuntimeException("Crawler has been detected") {

    @KoverIgnore
    private fun readResolve(): Any = CrawlerDetectedException
}

/**
 * Checks the response for indicators of special cases.
 * @since 2.1.0
 * @param response raw HTML
 */
public class AnidbResponseChecker(
    response: String,
    extractor: DataExtractor = XmlDataExtractor,
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
    private val metaDataProviderConfig: MetaDataProviderConfig = AnidbConfig,
) {

    private val openBrowserOnCrawlerDetected: Boolean by BooleanPropertyDelegate(
        namespace = "modb.anidb",
        default = false,
        configRegistry = configRegistry,
    )

    private val data by lazy { runBlocking {
        extractor.extract(response, mapOf(
            "contentContainer" to "//div[@id='layout-content']//div[@class='container']/text()",
            "title" to "//title/text()",
            "type" to "//tr[contains(@class, 'type')]//th[text()='Type']/following-sibling::*/text()",
        ))
    }}

    /**
     * Checks if an entry's addition is still pending. This means that the page doesn't contain any data.
     * @since 4.0.0
     * @return `true` if the entry is still pending addition.
     */
    public suspend fun isAdditionPending(): Boolean = withContext(LIMITED_CPU) {
        data.stringOrDefault("contentContainer").startsWith("A request for the addition of this title to AniDB is currently")
    }

    /**
     * Checks if an entry is only visible with an account, because it is an anime for adults only. In this case
     * the page doesn't contain any data.
     * @since 4.0.0
     * @return `true` if the entry is marked as for adults only.
     */
    public suspend fun isHentai(): Boolean = withContext(LIMITED_CPU) {
        data.stringOrDefault("contentContainer").startsWith("This anime is marked as 18+ restricted")
    }

    /**
     * Checks if an entry has been removed.
     * @since 4.0.0
     * @return `true` if the entry has been removed.
     */
    public suspend fun isRemovedFromAnidb(): Boolean = withContext(LIMITED_CPU) {
        data.stringOrDefault("contentContainer").startsWith("Unknown anime id.")
    }

    /**
     * Checks whether the response is missing the anime info table.
     *
     * Only meaningful once [isAdditionPending], [isHentai] and [isRemovedFromAnidb] have been ruled out:
     * those three are legitimately served without the table. For anything else its absence means the page is
     * not usable, and converting it would fail - which is how a single truncated capture took down the whole
     * conversion pipeline on 2026-10-16.
     *
     * Uses the same expression [AnidbAnimeConverter] reads the type from, so this returns `true` exactly when
     * the conversion would have no type to work with. Verified against the 6,645 complete pages of that run:
     * every one carries it, and only the truncated capture did not.
     * @since 1.16.0
     * @return `true` if the response carries no anime info table.
     */
    public suspend fun isIncompletePage(): Boolean = withContext(LIMITED_CPU) {
        data.stringOrDefault("type").isBlank()
    }

    /**
     * Checks if crawler has been detected.
     * @since 4.0.0
     * @return `true` if the antileech page is shown.
     */
    public suspend fun checkIfCrawlerIsDetected(): Unit = withContext(LIMITED_CPU) {
        val isAntiLeechPage = data.stringOrDefault("title") == "AniDB AntiLeech - AniDB"
        val isNginxPage = data.stringOrDefault("title") == "403 Forbidden"

        if (isAntiLeechPage || isNginxPage) {
            if (openBrowserOnCrawlerDetected) {
                openUri(URI("https://${metaDataProviderConfig.hostname()}"))
            }
            throw CrawlerDetectedException
        }
    }

    @KoverIgnore
    private fun openUri(uri: URI) {
        if (!metaDataProviderConfig.isTestContext()) {
            Desktop.getDesktop().browse(uri)
        }
    }
}