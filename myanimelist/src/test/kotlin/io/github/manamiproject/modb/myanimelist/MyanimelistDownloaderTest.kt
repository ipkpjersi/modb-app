package io.github.manamiproject.modb.myanimelist

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.FileSuffix
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.test.MockServerTestCase
import io.github.manamiproject.modb.test.WireMockServerCreator
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import io.github.manamiproject.modb.test.tempDirectory
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import java.net.URI
import kotlin.test.Test

internal class MyanimelistDownloaderTest : MockServerTestCase<WireMockServer> by WireMockServerCreator() {

    private val testConfigRegistry: ConfigRegistry = DefaultConfigRegistry(
        environmentVariables = mapOf("modb.myanimelist.clientId" to CLIENT_ID),
    )

    private fun testConfig(): MetaDataProviderConfig = object : MetaDataProviderConfig by TestMetaDataProviderConfig {
        override fun hostname(): Hostname = "localhost"
        override fun buildAnimeLink(id: AnimeId): URI = MyanimelistConfig.buildAnimeLink(id)
        override fun buildDataDownloadLink(id: String): URI = URI("http://localhost:$port/v2/anime/$id")
        override fun fileSuffix(): FileSuffix = MyanimelistConfig.fileSuffix()
    }

    @Test
    fun `successfully loads an entry and returns the raw json`() {
        runTest {
            // given
            val id = "1535"
            val body = """{"id":1535,"title":"Death Note"}"""
            serverInstance.stubFor(
                get(urlPathEqualTo("/v2/anime/$id")).willReturn(
                    aResponse().withHeader("Content-Type", "application/json").withStatus(200).withBody(body)
                )
            )
            val downloader = MyanimelistDownloader(testConfig(), testConfigRegistry)

            // when
            val result = downloader.download(id) { shouldNotBeInvoked() }

            // then
            assertThat(result).isEqualTo(body)
        }
    }

    @Test
    fun `sends the client id as X-MAL-CLIENT-ID header`() {
        runTest {
            // given
            val id = "1535"
            serverInstance.stubFor(
                get(urlPathEqualTo("/v2/anime/$id")).willReturn(
                    aResponse().withHeader("Content-Type", "application/json").withStatus(200).withBody("""{"id":1535}""")
                )
            )
            val downloader = MyanimelistDownloader(testConfig(), testConfigRegistry)

            // when
            downloader.download(id) { shouldNotBeInvoked() }

            // then
            serverInstance.verify(
                getRequestedFor(urlPathEqualTo("/v2/anime/$id")).withHeader("X-MAL-CLIENT-ID", equalTo(CLIENT_ID))
            )
        }
    }

    @Test
    fun `responding 404 indicates a dead entry - invokes onDeadEntry and returns empty string`() {
        runTest {
            // given
            val id = "1535"
            serverInstance.stubFor(
                get(urlPathEqualTo("/v2/anime/$id")).willReturn(
                    aResponse().withHeader("Content-Type", "application/json").withStatus(404).withBody("""{"error":"not_found"}""")
                )
            )
            var deadEntry = EMPTY
            val downloader = MyanimelistDownloader(testConfig(), testConfigRegistry)

            // when
            val result = downloader.download(id) { deadEntry = it }

            // then
            assertThat(deadEntry).isEqualTo(id)
            assertThat(result).isEqualTo(EMPTY)
        }
    }

    @Test
    fun `retries on 403 and succeeds afterwards`() {
        runTest {
            // given
            val id = "1535"
            val scenario = "retry-403"
            serverInstance.stubFor(
                get(urlPathEqualTo("/v2/anime/$id")).inScenario(scenario).whenScenarioStateIs("Started")
                    .willReturn(aResponse().withStatus(403).withBody("blocked"))
                    .willSetStateTo("recovered")
            )
            serverInstance.stubFor(
                get(urlPathEqualTo("/v2/anime/$id")).inScenario(scenario).whenScenarioStateIs("recovered")
                    .willReturn(aResponse().withHeader("Content-Type", "application/json").withStatus(200).withBody("""{"id":1535}"""))
            )
            val downloader = MyanimelistDownloader(testConfig(), testConfigRegistry)

            // when
            val result = downloader.download(id) { shouldNotBeInvoked() }

            // then
            assertThat(result).isEqualTo("""{"id":1535}""")
        }
    }

    @Test
    fun `throws an exception if the response body is blank`() {
        runTest {
            // given
            val id = "1535"
            serverInstance.stubFor(
                get(urlPathEqualTo("/v2/anime/$id")).willReturn(
                    aResponse().withHeader("Content-Type", "application/json").withStatus(200).withBody(EMPTY)
                )
            )
            val downloader = MyanimelistDownloader(testConfig(), testConfigRegistry)

            // when
            val result = exceptionExpected<IllegalStateException> {
                downloader.download(id) { shouldNotBeInvoked() }
            }

            // then
            assertThat(result).hasMessageContaining("Response body was blank")
        }
    }

    @Test
    fun `instance property always returns same instance`() {
        tempDirectory {
            // given
            val previous = MyanimelistDownloader.instance

            // when
            val result = MyanimelistDownloader.instance

            // then
            assertThat(result).isExactlyInstanceOf(MyanimelistDownloader::class.java)
            assertThat(result === previous).isTrue()
        }
    }

    private companion object {
        private const val CLIENT_ID = "test-client-id"
    }
}
