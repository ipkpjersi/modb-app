package io.github.manamiproject.modb.app.crawlers.simkl

import io.github.manamiproject.modb.app.network.TunnelConfig
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.simkl.SimklConfig
import java.net.URI

/**
 * Default [SimklMalIdRedirectResolver] using simkl's keyless redirect endpoint.
 *
 * `https://api.simkl.com/redirect?to=simkl&mal=<id>` 301-redirects to the matching
 * `https://simkl.com/anime/<simklId>/...` page, or to the bare `https://simkl.com/` homepage when simkl has no
 * entry for that myanimelist id. The endpoint needs no `client_id`/API key.
 *
 * The request must NOT follow the redirect: the simkl id is read from the `Location` header. Following it would
 * only hit the Cloudflare-protected target page, which is fetched separately by the FlareSolverr-based
 * downloader. Requests are routed through the residential tunnel because simkl bans the datacenter IP.
 * @since 1.15.0
 * @property metaDataProviderConfig Configuration for simkl. Used for the hostname (tunnel selection) and test
 * context. Uses [SimklConfig] by default.
 * @property httpClient Non-redirect-following, tunnel-routed client used to read the `Location` header.
 */
class DefaultSimklMalIdRedirectResolver(
    private val metaDataProviderConfig: MetaDataProviderConfig = SimklConfig,
    private val httpClient: HttpClient = DefaultHttpClient(
        proxy = TunnelConfig.instance.proxyFor(metaDataProviderConfig.hostname()),
        followRedirects = false,
        isTestContext = metaDataProviderConfig.isTestContext(),
    ),
): SimklMalIdRedirectResolver {

    override suspend fun resolve(malId: AnimeId): AnimeId? {
        val url = URI("https://$REDIRECT_HOST/redirect?to=$REDIRECT_TARGET&mal=$malId").toURL()
        val response = httpClient.get(url)
        val location = response.headers["location"]?.firstOrNull().orEmpty()
        response.close()

        check(response.code == 301 || response.code == 302) {
            "Unexpected non-redirect response resolving [malId=$malId]: http [${response.code}] (possible block or throttle)."
        }

        // A hit redirects to /anime/<simklId>/...; a miss redirects to the bare homepage (no /anime/ path).
        val simklId = ANIME_PATH.find(location)?.groupValues?.get(1)
        log.debug { "Resolved [malId=$malId] -> ${simklId?.let { "[simklId=$it]" } ?: "no simkl entry"}." }
        return simklId
    }

    companion object {
        private val log by LoggerDelegate()
        private const val REDIRECT_HOST = "api.simkl.com"
        private const val REDIRECT_TARGET = "simkl"
        private val ANIME_PATH = Regex("""/anime/(\d+)""")

        /**
         * Singleton of [DefaultSimklMalIdRedirectResolver]
         * @since 1.15.0
         */
        @KoverIgnore
        val instance: DefaultSimklMalIdRedirectResolver by lazy { DefaultSimklMalIdRedirectResolver() }
    }
}
