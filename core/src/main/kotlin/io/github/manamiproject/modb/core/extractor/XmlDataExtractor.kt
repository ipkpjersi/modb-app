package io.github.manamiproject.modb.core.extractor

import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.extensions.neitherNullNorBlank
import io.github.manamiproject.modb.core.logging.LoggerDelegate

/**
 * Extract data using XPath.
 * @since 12.0.0
 */
public object XmlDataExtractor : DataExtractor {

    private val log by LoggerDelegate()

    override suspend fun extract(rawContent: String, selection: Map<OutputKey, Selector>): ExtractionResult =
        extract(rawContent, selection, EMPTY)

    override suspend fun extract(rawContent: String, selection: Map<OutputKey, Selector>, identifier: String): ExtractionResult {
        return try {
            JsoupCssSelectorDataExtractor.extract(rawContent, selection)
        } catch (e: Exception) {
            val source = if (identifier.neitherNullNorBlank()) " for [$identifier]" else EMPTY
            log.warn { "Tried to execute query using JsoupCssDataExtractor$source. This resulted in error: [${e.message}]. Retrying extraction using JsoupXPathDataExtractor." }
            JsoupXPathDataExtractor.extract(rawContent, selection)
        }
    }
}