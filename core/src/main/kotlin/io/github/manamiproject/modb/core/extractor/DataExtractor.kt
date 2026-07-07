package io.github.manamiproject.modb.core.extractor

/**
 * @since 11.1.0
 */
public typealias OutputKey = String

/**
 * Can either be XPath or JsonPath.
 * @since 11.1.0
 */
public typealias Selector = String


/**
 * Extracts specific data from raw content.
 * @since 11.1.0
 */
public interface DataExtractor {

    /**
     * Selectively extracts data either using XPath for HTML/XML or JsonPath for JSON.
     * @since 11.1.0
     * @param rawContent The raw content. This can be either HTML/XML or JSON.
     * @param selection A [Map] defining selectors. The key is the name of the identifier in the result set. The value
     * is either the XPath or the JsonPath string which identifies the data to select from the [rawContent].
     * @return An [ExtractionResult] containing the resulting data. Key is the identifier corresponding to the key
     * from [selection]. The value is the identified data from [rawContent] based on the XPath or JsonPath.
     */
    public suspend fun extract(rawContent: String, selection: Map<OutputKey, Selector>): ExtractionResult

    /**
     * Same as [extract], but with an [identifier] (e.g. a meta data provider hostname) that implementations may
     * include in log output so a failed or fallback extraction can be attributed to its source. The default
     * implementation ignores the [identifier] and delegates to [extract].
     * @since 20.0.0
     * @param rawContent The raw content. This can be either HTML/XML or JSON.
     * @param selection A [Map] defining selectors. See [extract].
     * @param identifier Context describing where the [rawContent] originated, used only for logging.
     * @return An [ExtractionResult] containing the resulting data. See [extract].
     */
    public suspend fun extract(rawContent: String, selection: Map<OutputKey, Selector>, identifier: String): ExtractionResult =
        extract(rawContent, selection)
}