package io.github.manamiproject.modb.analyzer.util

import java.net.URI

// ESC (0x1B) built programmatically to avoid embedding a raw control character in the source.
private val ESC = Char(27).toString()
private val OSC8 = "${ESC}]8;;"
private val ST = "$ESC\\"

/**
 * Wraps a URI in an OSC 8 terminal hyperlink escape sequence so terminals that support it render a clickable link.
 * Terminals without OSC 8 support ignore the escape and show [text], which defaults to the URL itself, so the link
 * stays visible and copy-pasteable everywhere. This makes the analyzer's review usable over a plain terminal (e.g.
 * SSH) where the browser cannot be opened automatically.
 *
 * See https://gist.github.com/egmontkob/eb114294efbcd5adb1944c9f3cb5feda for the sequence.
 * @since 1.14.0
 * @param uri Target the terminal navigates to when the link is clicked.
 * @param text Visible label. Defaults to the URI so non-supporting terminals still show the full URL.
 * @return The escape-wrapped hyperlink ready to be printed.
 */
fun terminalHyperlink(uri: URI, text: String = uri.toString()): String = "$OSC8$uri$ST$text$OSC8$ST"

/**
 * Formats a collection of URIs as an indented, clickable list under an "Open" header for printing during review.
 * Each URI is rendered with [terminalHyperlink]. Returns an empty string for an empty collection so callers can
 * skip printing a header with no links.
 * @since 1.14.0
 * @param uris URLs to render as clickable links.
 * @return The formatted block, or an empty string when [uris] is empty.
 */
fun clickableLinks(uris: Collection<URI>): String = when {
    uris.isEmpty() -> ""
    else -> "\nOpen (click, or copy):\n" + uris.joinToString("\n") { "  ${terminalHyperlink(it)}" }
}
