package io.github.manamiproject.modb.analyzer.util

import org.assertj.core.api.Assertions.assertThat
import java.net.URI
import kotlin.test.Test

internal class TerminalHyperlinkKtTest {

    private val esc = Char(27).toString()

    @Test
    fun `wraps the uri in an OSC 8 escape sequence using the url as visible text by default`() {
        // given
        val uri = URI("https://myanimelist.net/anime/1")

        // when
        val result = terminalHyperlink(uri)

        // then
        assertThat(result).isEqualTo("$esc]8;;$uri$esc\\$uri$esc]8;;$esc\\")
    }

    @Test
    fun `uses the provided visible text`() {
        // given
        val uri = URI("https://myanimelist.net/anime/1")

        // when
        val result = terminalHyperlink(uri, "MAL 1")

        // then
        assertThat(result).isEqualTo("$esc]8;;${uri}${esc}\\MAL 1$esc]8;;$esc\\")
    }

    @Test
    fun `visible url text stays intact so non-supporting terminals still show the full url`() {
        // given
        val uri = URI("https://anisearch.com/anime/20004")

        // when
        val result = terminalHyperlink(uri)

        // then
        assertThat(result).contains(uri.toString())
    }

    @Test
    fun `clickableLinks renders one indented hyperlink per uri under the header`() {
        // given
        val uris = listOf(
            URI("https://anisearch.com/anime/20004"),
            URI("https://myanimelist.net/anime/60629"),
        )

        // when
        val result = clickableLinks(uris)

        // then
        assertThat(result).isEqualTo(
            "\nOpen (click, or copy):\n" +
                "  ${terminalHyperlink(uris[0])}\n" +
                "  ${terminalHyperlink(uris[1])}"
        )
    }

    @Test
    fun `clickableLinks returns an empty string for an empty collection`() {
        // when
        val result = clickableLinks(emptyList())

        // then
        assertThat(result).isEmpty()
    }
}
