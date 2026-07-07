package io.github.manamiproject.modb.myanimelist

import io.github.manamiproject.modb.core.anime.AnimeMedia.NO_PICTURE
import io.github.manamiproject.modb.core.anime.AnimeMedia.NO_PICTURE_THUMBNAIL
import io.github.manamiproject.modb.core.anime.AnimeSeason
import io.github.manamiproject.modb.core.anime.AnimeSeason.Season.*
import io.github.manamiproject.modb.core.anime.AnimeStatus
import io.github.manamiproject.modb.core.anime.AnimeStatus.*
import io.github.manamiproject.modb.core.anime.AnimeType
import io.github.manamiproject.modb.core.anime.AnimeType.*
import io.github.manamiproject.modb.core.anime.Duration
import io.github.manamiproject.modb.core.anime.Duration.TimeUnit.SECONDS
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.FileSuffix
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.test.loadTestResource
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import kotlin.test.Test
import io.github.manamiproject.modb.core.anime.AnimeStatus.UNKNOWN as UNKNOWN_STATUS
import io.github.manamiproject.modb.core.anime.AnimeType.UNKNOWN as UNKNOWN_TYPE

internal class MyanimelistAnimeConverterTest {

    private val testConfig = object : MetaDataProviderConfig by TestMetaDataProviderConfig {
        override fun hostname(): Hostname = MyanimelistConfig.hostname()
        override fun buildAnimeLink(id: AnimeId): URI = MyanimelistConfig.buildAnimeLink(id)
        override fun buildDataDownloadLink(id: String): URI = MyanimelistConfig.buildDataDownloadLink(id)
        override fun fileSuffix(): FileSuffix = MyanimelistConfig.fileSuffix()
    }

    @Nested
    inner class HappyPathTests {

        @Test
        fun `correctly converts a full api response (death note)`() {
            runTest {
                // given
                val converter = MyanimelistAnimeConverter(testConfig)
                val testFile = loadTestResource<String>("MyanimelistAnimeConverterTest/death_note.json")

                // when
                val result = converter.convert(testFile)

                // then
                assertThat(result.title).isEqualTo("Death Note")
                assertThat(result.episodes).isEqualTo(37)
                assertThat(result.type).isEqualTo(TV)
                assertThat(result.status).isEqualTo(FINISHED)
                assertThat(result.duration).isEqualTo(Duration(1380, SECONDS))
                assertThat(result.animeSeason).isEqualTo(AnimeSeason(FALL, 2006))
                assertThat(result.picture).isEqualTo(URI("https://cdn.myanimelist.net/images/anime/1079/138100l.jpg"))
                assertThat(result.thumbnail).isEqualTo(URI("https://cdn.myanimelist.net/images/anime/1079/138100.jpg"))
                assertThat(result.sources).containsExactly(URI("https://myanimelist.net/anime/1535"))
                assertThat(result.relatedAnime).containsExactly(URI("https://myanimelist.net/anime/2994"))
                assertThat(result.synonyms).containsExactlyInAnyOrder("DN", "デスノート")
                assertThat(result.tags).containsExactlyInAnyOrder("psychological", "shounen", "supernatural", "suspense")
                assertThat(result.studios).containsExactly("madhouse")
                assertThat(result.producers).isEmpty()
                assertThat(result.scores).hasSize(1)
            }
        }
    }

    @Nested
    inner class DefaultsTests {

        @Test
        fun `optional fields absent - falls back to sensible defaults`() {
            runTest {
                // given
                val converter = MyanimelistAnimeConverter(testConfig)
                val minimal = """{"id":1,"title":"Test","media_type":"tv","status":"finished_airing","num_episodes":0}"""

                // when
                val result = converter.convert(minimal)

                // then
                assertThat(result.title).isEqualTo("Test")
                assertThat(result.sources).containsExactly(URI("https://myanimelist.net/anime/1"))
                assertThat(result.picture).isEqualTo(NO_PICTURE)
                assertThat(result.thumbnail).isEqualTo(NO_PICTURE_THUMBNAIL)
                assertThat(result.duration).isEqualTo(Duration.UNKNOWN)
                assertThat(result.animeSeason).isEqualTo(AnimeSeason(UNDEFINED, AnimeSeason.UNKNOWN_YEAR))
                assertThat(result.relatedAnime).isEmpty()
                assertThat(result.synonyms).isEmpty()
                assertThat(result.tags).isEmpty()
                assertThat(result.studios).isEmpty()
                assertThat(result.producers).isEmpty()
                assertThat(result.scores).isEmpty()
            }
        }

        @Test
        fun `status absent maps to unknown`() {
            runTest {
                // given
                val converter = MyanimelistAnimeConverter(testConfig)
                val minimal = """{"id":1,"title":"Test","media_type":"tv"}"""

                // when
                val result = converter.convert(minimal)

                // then
                assertThat(result.status).isEqualTo(UNKNOWN_STATUS)
            }
        }
    }

    @Nested
    inner class TypeTests {

        @ParameterizedTest
        @CsvSource(value = [
            "tv,TV",
            "movie,MOVIE",
            "ova,OVA",
            "ona,ONA",
            "special,SPECIAL",
            "tv_special,SPECIAL",
            "music,SPECIAL",
            "pv,SPECIAL",
            "cm,SPECIAL",
            "unknown,UNKNOWN",
        ])
        fun `correctly maps media_type`(mediaType: String, expected: String) {
            runTest {
                // given
                val converter = MyanimelistAnimeConverter(testConfig)
                val json = """{"id":1,"title":"Test","media_type":"$mediaType","status":"finished_airing"}"""

                // when
                val result = converter.convert(json)

                // then
                val expectedType = if (expected == "UNKNOWN") UNKNOWN_TYPE else AnimeType.valueOf(expected)
                assertThat(result.type).isEqualTo(expectedType)
            }
        }
    }

    @Nested
    inner class StatusTests {

        @ParameterizedTest
        @CsvSource(value = [
            "finished_airing,FINISHED",
            "currently_airing,ONGOING",
            "not_yet_aired,UPCOMING",
        ])
        fun `correctly maps status`(status: String, expected: String) {
            runTest {
                // given
                val converter = MyanimelistAnimeConverter(testConfig)
                val json = """{"id":1,"title":"Test","media_type":"tv","status":"$status"}"""

                // when
                val result = converter.convert(json)

                // then
                assertThat(result.status).isEqualTo(AnimeStatus.valueOf(expected))
            }
        }
    }
}
