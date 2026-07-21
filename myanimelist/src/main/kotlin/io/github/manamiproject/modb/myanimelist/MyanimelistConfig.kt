package io.github.manamiproject.modb.myanimelist

import io.github.manamiproject.modb.core.config.FileSuffix
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import java.net.URI

/**
 * Configuration for downloading and converting anime data from myanimelist.net.
 * Data is downloaded from the official MyAnimeList v2 API (JSON) instead of scraping the website.
 * [hostname] stays `myanimelist.net` so that source and related-anime links keep pointing at the public site.
 * @since 1.0.0
 */
public object MyanimelistConfig: MetaDataProviderConfig {

    override fun hostname(): Hostname = "myanimelist.net"

    override fun buildDataDownloadLink(id: String): URI = URI(
        "https://api.myanimelist.net/v2/anime/$id?fields=id,title,main_picture,alternative_titles,start_season,media_type,num_episodes,status,average_episode_duration,mean,genres,studios,related_anime"
    )

    override fun fileSuffix(): FileSuffix = "json"
}
