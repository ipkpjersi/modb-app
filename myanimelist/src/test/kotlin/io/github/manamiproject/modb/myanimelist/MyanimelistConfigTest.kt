package io.github.manamiproject.modb.myanimelist

import org.assertj.core.api.Assertions.assertThat
import java.net.URI
import kotlin.test.Test

internal class MyanimelistConfigTest {

    @Test
    fun `isTestContext is false`() {
        // when
        val result = MyanimelistConfig.isTestContext()

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `hostname must be correct`() {
        // when
        val result = MyanimelistConfig.hostname()

        // then
        assertThat(result).isEqualTo("myanimelist.net")
    }

    @Test
    fun `build anime link correctly`() {
        // given
        val id = "1535"

        // when
        val result = MyanimelistConfig.buildAnimeLink(id)

        // then
        assertThat(result).isEqualTo(URI("https://myanimelist.net/anime/$id"))
    }

    @Test
    fun `build data download link points at the MAL v2 API`() {
        // given
        val id = "1535"

        // when
        val result = MyanimelistConfig.buildDataDownloadLink(id)

        // then
        assertThat(result).isEqualTo(URI("https://api.myanimelist.net/v2/anime/$id?fields=id,title,main_picture,alternative_titles,start_season,media_type,num_episodes,status,average_episode_duration,mean,genres,studios,related_anime"))
    }

    @Test
    fun `file suffix must be json`() {
        // when
        val result = MyanimelistConfig.fileSuffix()

        // then
        assertThat(result).isEqualTo("json")
    }
}
