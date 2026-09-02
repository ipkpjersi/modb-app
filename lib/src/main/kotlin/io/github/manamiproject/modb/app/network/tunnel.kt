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
 * Whether traffic for [hostname] leaves through the reverse SSH tunnel or the commercial rotating proxy
 * rather than through this host's own IP.
 *
 * The two routes are mutually exclusive (enforced by [checkCommercialProxy]), so this only answers whether
 * the exit IP belongs to this host, which is what the recovery strategies below turn on.
 *
 * @since 1.15.0
 * @param hostname Hostname of a metadata provider.
 * @param tunnelConfig Configuration of the reverse SSH tunnel.
 * @param commercialProxyConfig Configuration of the commercial rotating proxy.
 * @return `true` if [hostname] exits through the tunnel or the commercial proxy.
 */
fun isRoutedElsewhere(
    hostname: Hostname,
    tunnelConfig: TunnelConfig = TunnelConfig.instance,
    commercialProxyConfig: CommercialProxyConfig = CommercialProxyConfig.instance,
): Boolean = tunnelConfig.isTunneled(hostname) || commercialProxyConfig.isRouted(hostname)

/**
 * Builds the `executeBefore` action for the connection-failure retry cases of a crawler.
 *
 * Rotating this host's outbound IPv6 address is only a valid recovery while the provider is crawled over the
 * direct datacenter path. Once [hostname] is routed, the rotation is:
 * - **useless**, because the provider sees the tunnel's or the proxy pool's exit IP, not this host's,
 * - **harmful to every other provider**, because [SuspendableHttpClient] gates all requests while the
 *   interface bounces,
 * - **counterproductive**, because dropping the interface also drops the SSH tunnel that the routed
 *   provider(s) depend on, forcing autossh to reconnect.
 *
 * Returning a no-op leaves the retry itself intact, so a routed provider still retries with
 * [io.github.manamiproject.modb.core.httpclient.ThrowableRetryCase]'s default backoff.
 *
 * @since 1.15.0
 * @param hostname Hostname of a metadata provider.
 * @param networkController Rotates this host's outbound IPv6 address.
 * @param tunnelConfig Configuration of the reverse SSH tunnel.
 * @param commercialProxyConfig Configuration of the commercial rotating proxy.
 * @return An action which restarts the network on the direct path and does nothing while routed.
 */
fun restartUnlessRouted(
    hostname: Hostname,
    networkController: NetworkController,
    tunnelConfig: TunnelConfig = TunnelConfig.instance,
    commercialProxyConfig: CommercialProxyConfig = CommercialProxyConfig.instance,
): suspend () -> Unit = if (isRoutedElsewhere(hostname, tunnelConfig, commercialProxyConfig)) {
    { }
} else {
    { networkController.restartAsync().join() }
}

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
    // for example '--only anilist', or all still being deactivated - has no use for the tunnel, so a missing
    // one must not abort it. Commercially-routed provider(s) are never in this set (the tunnel and commercial
    // sets are mutually exclusive), so they cannot demand a tunnel they do not use.
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

/**
 * Validates the commercial proxy setup up front, so a misconfigured run aborts before the crawlers start
 * instead of silently banning a provider that needs the pool. Checks, in order:
 *
 * 1. the commercial proxy config itself (a non-blank url when enabled, credentials both-or-neither);
 * 2. the tunnel and commercial provider sets are mutually exclusive (a provider has one residential path);
 * 3. no commercial provider is left active while the commercial proxy is disabled - it would fall back to the
 *    direct datacenter path and get banned.
 *
 * Does nothing under test. The deeper check - that the commercial container actually exits through a
 * non-datacenter IP - is left to `scripts/tunnel/check-commercial-proxy.sh`, run by hand before a crawl.
 *
 * @since 1.15.0
 * @param commercialProxyConfig Configuration of the commercial rotating proxy.
 * @param tunnelConfig Configuration of the reverse SSH tunnel.
 * @param appConfig Application specific configuration.
 * @throws IllegalStateException on any of the failures above.
 */
fun checkCommercialProxy(
    commercialProxyConfig: CommercialProxyConfig = CommercialProxyConfig.instance,
    tunnelConfig: TunnelConfig = TunnelConfig.instance,
    appConfig: Config = AppConfig.instance,
) {
    if (appConfig.isTestContext()) return

    commercialProxyConfig.validate()

    // The tunnel set and the commercial set are mutually exclusive - a provider has exactly one residential
    // path. A hostname in both is a configuration error, because the two would fight over its routing.
    val inBoth = tunnelConfig.providers intersect commercialProxyConfig.providers
    check(inBoth.isEmpty()) {
        "Provider(s) [${inBoth.sorted().joinToString(", ")}] are in both the tunnel and the commercial proxy set. A provider must be in only one."
    }

    // A commercial provider that is still active cannot run without the commercial proxy: it would fall back
    // to the direct datacenter path and get banned. Fail fast unless the proxy is enabled or the provider is
    // turned off (deactivatedCommercialMetaDataProviders is already folded into deactivatedMetaDataProviders).
    val activeCommercial = commercialProxyConfig.providers - appConfig.deactivatedMetaDataProviders()
    check(commercialProxyConfig.enabled || activeCommercial.isEmpty()) {
        "Commercial provider(s) [${activeCommercial.sorted().joinToString(", ")}] are active but the commercial proxy is disabled. Enable 'modb.app.proxy.commercial' or deactivate them via 'deactivatedCommercialMetaDataProviders'."
    }

    if (commercialProxyConfig.enabled) {
        val routed = commercialProxyConfig.providers.sorted().joinToString(", ")
        Tunnel.log.info { "Commercial proxy enabled. Routing [$routed] through FlareSolverr on port [${commercialProxyConfig.flaresolverrPort}]." }
    }
}
