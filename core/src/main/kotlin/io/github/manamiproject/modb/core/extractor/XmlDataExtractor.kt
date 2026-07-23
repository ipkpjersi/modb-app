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
        } catch (cssException: Exception) {
            val source = if (identifier.neitherNullNorBlank()) " for [$identifier]" else EMPTY
            // The CSS extractor failing is expected whenever a selector is written in XPath syntax, and the
            // XPath extractor below is the real attempt - so this routine fallback stays at debug to avoid log
            // noise. Only a failure of BOTH extractors means the data could not be extracted at all and is
            // worth a warning.
            log.debug { "JsoupCssSelectorDataExtractor$source failed with [${cssException.message}]. Falling back to JsoupXPathDataExtractor." }
            try {
                JsoupXPathDataExtractor.extract(rawContent, selection)
            } catch (xpathException: Exception) {
                log.warn { "Both JsoupCssSelectorDataExtractor and JsoupXPathDataExtractor$source failed. CSS error: [${cssException.message}], XPath error: [${xpathException.message}]." }
                throw xpathException
            }
        }
    }
}