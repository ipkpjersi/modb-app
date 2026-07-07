package io.github.manamiproject.modb.myanimelist

import io.github.manamiproject.modb.core.anime.*
import io.github.manamiproject.modb.core.anime.AnimeMedia.NO_PICTURE
import io.github.manamiproject.modb.core.anime.AnimeMedia.NO_PICTURE_THUMBNAIL
import io.github.manamiproject.modb.core.anime.AnimeSeason.Season
import io.github.manamiproject.modb.core.anime.AnimeStatus.*
import io.github.manamiproject.modb.core.anime.AnimeType.*
import io.github.manamiproject.modb.core.anime.Duration.TimeUnit.SECONDS
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.converter.AnimeConverter
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_CPU
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.extensions.neitherNullNorBlank
import io.github.manamiproject.modb.core.extractor.DataExtractor
import io.github.manamiproject.modb.core.extractor.ExtractionResult
import io.github.manamiproject.modb.core.extractor.JsonDataExtractor
import kotlinx.coroutines.withContext
import java.net.URI
import io.github.manamiproject.modb.core.anime.AnimeStatus.UNKNOWN as UNKNOWN_STATUS
import io.github.manamiproject.modb.core.anime.AnimeType.UNKNOWN as UNKNOWN_TYPE
import io.github.manamiproject.modb.core.anime.Duration.Companion.UNKNOWN as UNKNOWN_DURATION

/**
 * Converts raw data to an [AnimeRaw].
 * Requires the JSON response of the MyAnimeList v2 API (see [MyanimelistConfig]).
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for converting data.
 * @property extractor Extracts specific data from raw content.
 */
public class MyanimelistAnimeConverter(
    private val metaDataProviderConfig: MetaDataProviderConfig = MyanimelistConfig,
    private val extractor: DataExtractor = JsonDataExtractor,
) : AnimeConverter {

    override suspend fun convert(rawContent: String): AnimeRaw = withContext(LIMITED_CPU) {
        val data = extractor.extract(rawContent, mapOf(
            "id" to "$.id",
            "title" to "$.title",
            "episodes" to "$.num_episodes",
            "type" to "$.media_type",
            "status" to "$.status",
            "durationInSeconds" to "$.average_episode_duration",
            "pictureLarge" to "$.main_picture.large",
            "pictureMedium" to "$.main_picture.medium",
            "seasonYear" to "$.start_season.year",
            "season" to "$.start_season.season",
            "synonyms" to "$.alternative_titles.synonyms",
            "titleEn" to "$.alternative_titles.en",
            "titleJa" to "$.alternative_titles.ja",
            "relatedAnime" to "$.related_anime.*.node",
            "tags" to "$.genres.*.name",
            "studios" to "$.studios.*.name",
            "score" to "$.mean",
        ))

        val title = extractTitle(data)

        return@withContext AnimeRaw(
            _title = title,
            episodes = extractEpisodes(data),
            type = extractType(data),
            picture = extractPicture(data),
            thumbnail = extractThumbnail(data),
            status = extractStatus(data),
            duration = extractDuration(data),
            animeSeason = extractAnimeSeason(data),
            _sources = extractSourcesEntry(data),
            _synonyms = extractSynonyms(data, title),
            _relatedAnime = extractRelatedAnime(data),
            _tags = extractTags(data),
            _studios = extractStudios(data),
            _producers = hashSetOf(), // not exposed by the MAL v2 API
        ).addScores(extractScore(data))
    }

    private fun extractTitle(data: ExtractionResult): Title = data.string("title").trim()

    private fun extractEpisodes(data: ExtractionResult): Episodes = data.intOrDefault("episodes")

    private fun extractType(data: ExtractionResult): AnimeType {
        return when(data.string("type").trim().lowercase()) {
            "tv" -> TV
            "movie" -> MOVIE
            "ova" -> OVA
            "ona" -> ONA
            "special" -> SPECIAL
            "tv_special" -> SPECIAL
            "music" -> SPECIAL
            "pv" -> SPECIAL
            "cm" -> SPECIAL
            "unknown" -> UNKNOWN_TYPE
            else -> throw IllegalStateException("Unknown type [${data.string("type")}]")
        }
    }

    private fun extractPicture(data: ExtractionResult): URI {
        return if (data.notFound("pictureLarge")) {
            NO_PICTURE
        } else {
            URI(data.string("pictureLarge").trim())
        }
    }

    private fun extractThumbnail(data: ExtractionResult): URI {
        return if (data.notFound("pictureMedium")) {
            NO_PICTURE_THUMBNAIL
        } else {
            URI(data.string("pictureMedium").trim())
        }
    }

    private fun extractStatus(data: ExtractionResult): AnimeStatus {
        if (data.notFound("status")) {
            return UNKNOWN_STATUS
        }

        return when(data.string("status").trim().lowercase()) {
            "finished_airing" -> FINISHED
            "currently_airing" -> ONGOING
            "not_yet_aired" -> UPCOMING
            else -> throw IllegalStateException("Unknown status [${data.string("status")}]")
        }
    }

    private fun extractDuration(data: ExtractionResult): Duration {
        val durationInSeconds = data.intOrDefault("durationInSeconds")

        return if (durationInSeconds == 0) {
            UNKNOWN_DURATION
        } else {
            Duration(durationInSeconds, SECONDS)
        }
    }

    private fun extractAnimeSeason(data: ExtractionResult): AnimeSeason {
        val season = if (data.notFound("season")) {
            Season.UNDEFINED
        } else {
            Season.of(data.string("season").trim())
        }

        val year = data.intOrDefault("seasonYear")

        return AnimeSeason(
            season = season,
            year = year,
        )
    }

    private fun extractSourcesEntry(data: ExtractionResult): HashSet<URI> {
        return hashSetOf(metaDataProviderConfig.buildAnimeLink(data.string("id").trim()))
    }

    private fun extractSynonyms(data: ExtractionResult, title: Title): HashSet<Title> {
        val synonyms = hashSetOf<Title>()

        if (!data.notFound("synonyms")) {
            synonyms.addAll(data.listNotNull<Title>("synonyms"))
        }
        if (!data.notFound("titleEn")) {
            synonyms.add(data.string("titleEn"))
        }
        if (!data.notFound("titleJa")) {
            synonyms.add(data.string("titleJa"))
        }

        return synonyms.map { it.trim() }
            .filter { it.neitherNullNorBlank() }
            .filterNot { it == title }
            .toHashSet()
    }

    private fun extractRelatedAnime(data: ExtractionResult): HashSet<URI> {
        return if (data.notFound("relatedAnime")) {
            hashSetOf()
        } else {
            data.listNotNull<LinkedHashMap<String, Any>>("relatedAnime")
                .mapNotNull { it["id"] }
                .map { metaDataProviderConfig.buildAnimeLink(it.toString().trim()) }
                .toHashSet()
        }
    }

    private fun extractTags(data: ExtractionResult): HashSet<Tag> {
        return if (data.notFound("tags")) {
            hashSetOf()
        } else {
            data.listNotNull<Tag>("tags")
                .map { it.trim() }
                .filter { it.neitherNullNorBlank() }
                .toHashSet()
        }
    }

    private fun extractStudios(data: ExtractionResult): HashSet<Studio> {
        return if (data.notFound("studios")) {
            hashSetOf()
        } else {
            data.listNotNull<Studio>("studios")
                .map { it.trim().lowercase() }
                .filter { it.neitherNullNorBlank() }
                .toHashSet()
        }
    }

    private fun extractScore(data: ExtractionResult): MetaDataProviderScore {
        if (data.notFound("score")) {
            return NoMetaDataProviderScore
        }

        return MetaDataProviderScoreValue(
            hostname = metaDataProviderConfig.hostname(),
            value = data.double("score"),
            range = 1.0..10.0,
        )
    }

    public companion object {
        /**
         * Singleton of [MyanimelistAnimeConverter]
         * @since 6.1.0
         */
        public val instance: MyanimelistAnimeConverter by lazy { MyanimelistAnimeConverter() }
    }
}
