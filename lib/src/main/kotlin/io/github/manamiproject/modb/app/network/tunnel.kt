package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Holder for the logger. [LoggerDelegate] derives the logger name from the instance it is bound to, so it
 * cannot back a top-level property in a file of functions like this one.
 */
private object Tunnel {
    val log by LoggerDelegate()
}

/**
 * Milliseconds allowed for the TCP connect which probes the tunnel. The tunnel terminates on this host, so
 * a healthy one answers immediately and anything slower means it is not there.
 */
private const val TUNNEL_PROBE_TIMEOUT_MS = 5000

/**
 * Creates an [HttpClient] for a direct-scrape provider, routed through the tunnel if [hostname] requires a
 * residential exit IP.
 *
 * Safe to use as the default at any construction site: [TunnelConfig.proxyFor] returns `NO_PROXY` for every
 * provider which is not tunneled, which reproduces the previous behaviour exactly.
 *
 * This is only for provider(s) which are scraped directly. Provider(s) that go through FlareSolverr are proxied by
 * FlareSolverr itself (see [FlaresolverrHttpClient]), because it is FlareSolverr's container - not this
 * process - which makes the outbound request.
 *
 * @since 1.14.0
 * @param hostname Hostname of the metadata provider the client will be used for.
 * @param tunnelConfig Configuration of the reverse SSH tunnel.
 * @param appConfig Application specific configuration.
 * @return An [HttpClient] which routes through the tunnel if required, and directly otherwise.
 */
fun tunnelAwareHttpClient(
    hostname: Hostname,
    tunnelConfig: TunnelConfig = TunnelConfig.instance,
    appConfig: Config = AppConfig.instance,
): HttpClient = SuspendableHttpClient(
    appConfig = appConfig,
    httpClient = DefaultHttpClient(
        proxy = tunnelConfig.proxyFor(hostname),
        isTestContext = appConfig.isTestContext(),
    ),
)

/**
 * Verifies that the reverse SSH tunnel is up, and throws if it is not.
 *
 * Called before any crawler starts so that a missing tunnel aborts the run immediately instead of letting
 * every tunneled provider fail one by one against a dead proxy - or worse, silently fall back to the
 * datacenter IP and collect a fresh ban. This mirrors the fail-fast design used everywhere else: a run is
 * either complete or it stops.
 *
 * Does nothing when the tunnel is disabled, so a host without one is unaffected.
 *
 * @since 1.14.0
 * @param tunnelConfig Configuration of the reverse SSH tunnel.
 * @param appConfig Application specific configuration.
 * @throws IllegalStateException if the tunnel is enabled but not reachable.
 */
suspend fun checkTunnel(
    tunnelConfig: TunnelConfig = TunnelConfig.instance,
    appConfig: Config = AppConfig.instance,
) {
    if (!tunnelConfig.enabled || appConfig.isTestContext()) return

    // Only the provider(s) actually crawling this run matter. A run which skips every tunneled provider(s) -
    // for example '--only anilist', or all four still being deactivated - has no use for the tunnel, so a
    // missing one must not abort it.
    val activeTunneledProviders = tunnelConfig.providers - appConfig.deactivatedMetaDataProviders()

    if (activeTunneledProviders.isEmpty()) {
        Tunnel.log.info { "Tunnel is enabled but no tunneled provider(s) are active in this run. Skipping tunnel check." }
        return
    }

    val target = "${tunnelConfig.host}:${tunnelConfig.port}"
    val active = activeTunneledProviders.sorted().joinToString(", ")
    Tunnel.log.info { "Checking reverse SSH tunnel on [$target]." }

    val isReachable = withContext(IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(tunnelConfig.host, tunnelConfig.port), TUNNEL_PROBE_TIMEOUT_MS)
            }
            true
        } catch (throwable: Throwable) {
            Tunnel.log.warn { "Tunnel probe on [$target] failed: [${throwable.message}]." }
            false
        }
    }

    check(isReachable) {
        """
            Reverse SSH tunnel is not reachable on [$target], but 'modb.app.tunnel.enabled' is true and these
            provider(s) crawling in this run need it for a residential exit IP: [$active].
            Start the tunnel on the residential machine (see scripts/tunnel/README.md), deactivate those
            provider(s), or set 'modb.app.tunnel.enabled = false' to run without it.
        """.trimIndent().replace('\n', ' ')
    }

    Tunnel.log.info { "Reverse SSH tunnel on [$target] is up. Routing [$active] through it." }
}
