package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.BooleanPropertyDelegate
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.SetPropertyDelegate
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.extensions.EMPTY

/**
 * Configuration for a commercial rotating residential proxy served by a dedicated second FlareSolverr
 * instance.
 *
 * Some metadata provider - anidb in particular - enforce a per-IP daily quota rather than a rate. A single
 * residential exit (the reverse SSH tunnel) cannot get past that quota, so the traffic for those provider(s)
 * has to leave through a pool of rotating residential IPs supplied by a commercial provider.
 *
 * The rotating proxy is not shared with the tunnel's FlareSolverr container. It gets its own container (on
 * [flaresolverrPort]) so anidb's heavy initial crawl runs on a separate browser in parallel with the
 * tunneled provider(s), instead of serializing behind them on the single-browser tunnel container.
 *
 * Routing here takes precedence over [TunnelConfig]: a provider listed in both is sent through the commercial
 * proxy. That keeps the tunnel as the safe default while [enabled] is `false` (anidb still routes through the
 * tunnel), and lets the commercial proxy override it when `true`, without mutating the tunnel's provider set.
 *
 * Both auth models are supported and selected purely by whether credentials are present:
 * - [username] and [password] blank: an authless proxy request is sent, which works when the provider
 *   authorizes the crawl server by IP (IP whitelisting).
 * - [username] and [password] set: the credentials are sent in the FlareSolverr session's proxy block.
 *
 * Credentials only work over an `http(s)://` proxy, where FlareSolverr's generated extension can answer the
 * HTTP 407 challenge. Chromium does not implement SOCKS5 username/password auth (RFC 1929), so a
 * `socks5://` [url] carrying credentials fails at the SOCKS handshake and surfaces as a connection error
 * rather than an auth error. Use IP whitelisting, the provider's HTTP endpoint, or a local authless shim
 * instead - see `scripts/tunnel/README.md`.
 *
 * **Default** is disabled so a host without a commercial proxy behaves exactly as before.
 *
 * @since 1.15.0
 * @property configRegistry Handles the retrieval of the value.
 */
class CommercialProxyConfig(
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
) {

    /**
     * Whether the commercial proxy is in use. While `false` no provider is routed through it, regardless of
     * [providersForCommercialProxy], and the second FlareSolverr container is neither started nor
     * required. **Default** is `false`.
     * @since 1.15.0
     */
    val enabled: Boolean by BooleanPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = false,
        configRegistry = configRegistry,
    )

    /**
     * Host port the dedicated second FlareSolverr container publishes (mapped to the container's own 8191).
     * Must differ from [FlaresolverrConfig.port]. **Default** is `8192`.
     * @since 1.15.0
     */
    val flaresolverrPort: Int by IntPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = 8192,
        configRegistry = configRegistry,
    )

    /**
     * Hostnames of the metadata provider(s) whose traffic is routed through the commercial proxy. Everything
     * not listed here keeps the tunnel or the direct path. **Default** is `anidb.net`.
     * @since 1.15.0
     */
    val providersForCommercialProxy: Set<Hostname> by SetPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = setOf(
            "anidb.net",
        ),
        configRegistry = configRegistry,
    )

    /**
     * Proxy endpoint URL, for example `socks5://gateway.example.com:7000`. Secret in practice, so it belongs
     * in the gitignored `config.toml`, never in source. **Default** is empty.
     * @since 1.15.0
     */
    val url: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = EMPTY,
        configRegistry = configRegistry,
    )

    /**
     * Proxy username. Leave blank to send an authless request (IP whitelisting). If set, [password] must be
     * set too. **Default** is empty.
     * @since 1.15.0
     */
    val username: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = EMPTY,
        configRegistry = configRegistry,
    )

    /**
     * Proxy password. Leave blank to send an authless request (IP whitelisting). If set, [username] must be
     * set too. **Default** is empty.
     * @since 1.15.0
     */
    val password: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = EMPTY,
        configRegistry = configRegistry,
    )

    /**
     * A leading `www.` is stripped before matching, mirroring [TunnelConfig.isTunneled].
     * @since 1.15.0
     * @param hostname Hostname of a metadata provider.
     * @return `true` if traffic for [hostname] must be routed through the commercial proxy.
     */
    fun isRouted(hostname: Hostname): Boolean =
        enabled && providersForCommercialProxy.contains(hostname.lowercase().removePrefix("www."))

    /**
     * The `proxy` fragment appended to a FlareSolverr `sessions.create` body, including the leading comma.
     * Credentials are included only when both are present. FlareSolverr's structured `username`/`password`
     * fields are used rather than embedding `user:pass@` in the URL, so credentials do not need URL encoding.
     *
     * Values are JSON-escaped so a password containing a quote or backslash cannot break the request body.
     * @since 1.15.0
     * @return The proxy fragment, for example `,"proxy":{"url":"..."}`.
     */
    fun proxyJson(): String = when {
        username.isNotBlank() && password.isNotBlank() ->
            ""","proxy":{"url":"${jsonEscape(url)}","username":"${jsonEscape(username)}","password":"${jsonEscape(password)}"}"""
        else -> ""","proxy":{"url":"${jsonEscape(url)}"}"""
    }

    /**
     * Fails fast on an incomplete configuration so a run cannot silently start a routed provider against a
     * broken proxy. Does nothing while disabled.
     * @since 1.15.0
     * @throws IllegalStateException if enabled with a blank [url], or with only one of [username]/[password] set.
     */
    fun validate() {
        if (!enabled) {
            return
        }
        check(url.isNotBlank()) {
            "$CONFIG_NAMESPACE.enabled is true but '$CONFIG_NAMESPACE.url' is blank."
        }
        check(username.isBlank() == password.isBlank()) {
            "$CONFIG_NAMESPACE.username and $CONFIG_NAMESPACE.password must be set together (both or neither)."
        }
    }

    companion object {
        private const val CONFIG_NAMESPACE = "modb.app.proxy.commercial"

        private fun jsonEscape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        /**
         * Singleton of [CommercialProxyConfig]
         * @since 1.15.0
         */
        val instance: CommercialProxyConfig by lazy { CommercialProxyConfig() }
    }
}
