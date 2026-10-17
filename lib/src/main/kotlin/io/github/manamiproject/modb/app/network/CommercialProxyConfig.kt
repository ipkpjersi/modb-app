package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.BooleanPropertyDelegate
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.SetPropertyDelegate
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.app.config.parseHostnameLimits
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import java.net.URI

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
 * **Session rotation.** A provider cannot tell that a new FlareSolverr session has begun, because every
 * session presents the same credentials. It keeps serving whatever exit IP that credential string is bound
 * to, so recreating the session on its own buys nothing. Rotation therefore has to be expressed in the
 * credentials: put [SESSION_PLACEHOLDER] wherever the provider's own sticky-session parameter goes and a
 * fresh random id is substituted on every [proxyJson] call, which is once per session creation. Combined
 * with [rotateAfterRequests] that yields one fresh residential IP per batch of entries, which is what beats
 * a per-IP daily quota without re-solving the Cloudflare challenge on every single request.
 *
 * The parameter's spelling differs per provider, so the placeholder goes in whichever field that provider
 * documents, for example `user-NAME-session-{session}` as a [username] or
 * `pass_session-{session}_lifetime-30m` as a [password].
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
     * Whether a request for a routed provider may be sent with a plain HTTP client once the provider's
     * FlareSolverr session has cleared Cloudflare, instead of going through the browser every time.
     *
     * The browser is only needed to solve the challenge. Afterwards the clearance cookie plus the same exit
     * IP and user agent are everything the site checks, so the remaining requests of a session can be fetched
     * directly - which skips the CSS, fonts and roughly 59 thumbnails per anidb page that a real browser also
     * loads and this crawl throws away. Measured on 2026-10-16: 534 KB of metered residential traffic per
     * page through the browser against 32 KB fetched directly, with byte-identical converter output on 9
     * sample pages and identical pending/hentai/removed detection on 3 more.
     *
     * Falls back to the browser whenever the direct response is not usable, so turning this off only costs
     * bandwidth. Set to `false` if a provider starts rejecting the plain client. **Default** is `true`.
     * @since 1.16.0
     */
    val directFetch: Boolean by BooleanPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = true,
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
     * Per-provider rotation thresholds as `hostname=limit` entries, for example `["anidb.net=50"]`. A
     * provider which is not listed uses [DEFAULT_ROTATE_AFTER_REQUESTS]; `hostname=0` disables rotation for
     * that provider and keeps one session for the whole run.
     *
     * Per-provider because the binding limit belongs to the target site, not to the proxy: anidb allows only
     * ~100-200 requests per IP per day, while another site might tolerate hundreds.
     * @since 1.15.0
     */
    private val rotateAfterRequests: Set<String> by SetPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = emptySet(),
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
    /**
     * Number of requests one FlareSolverr session serves for [hostname] before it is destroyed and
     * recreated, which draws a fresh exit IP when the credentials carry [SESSION_PLACEHOLDER].
     *
     * Sized against two opposing costs. Too high and a batch exhausts the provider's per-IP daily quota. Too
     * low and each short-lived session pays the full Cloudflare solve again (~19s against ~2-4s on a reused
     * session), so rotating every request would cost days across anidb's 15,000-odd entries.
     *
     * @since 1.15.0
     * @param hostname Hostname of a metadata provider.
     * @return Requests per session, or `0` to never rotate.
     */
    fun rotateAfterRequests(hostname: Hostname): Int =
        parseHostnameLimits("$CONFIG_NAMESPACE.rotateAfterRequests", rotateAfterRequests)[
            hostname.lowercase().removePrefix("www.")
        ] ?: DEFAULT_ROTATE_AFTER_REQUESTS

    fun isRouted(hostname: Hostname): Boolean =
        enabled && providersForCommercialProxy.contains(hostname.lowercase().removePrefix("www."))

    /**
     * Whether the credentials carry [SESSION_PLACEHOLDER], and therefore whether recreating a FlareSolverr
     * session actually draws a new exit IP.
     * @since 1.15.0
     * @return `true` if a fresh session id is substituted into the credentials on each [proxyJson] call.
     */
    /**
     * A fresh session id, for a caller which needs to log it as well as send it.
     * @since 1.15.0
     * @return A random 8-character lowercase alphanumeric session id.
     */
    fun newSessionId(): String = randomSessionId()

    fun rotatesSessionId(): Boolean =
        username.contains(SESSION_PLACEHOLDER) || password.contains(SESSION_PLACEHOLDER)

    /**
     * The `proxy` fragment appended to a FlareSolverr `sessions.create` body, including the leading comma.
     * Credentials are included only when both are present. FlareSolverr's structured `username`/`password`
     * fields are used rather than embedding `user:pass@` in the URL, so credentials do not need URL encoding.
     *
     * Every [SESSION_PLACEHOLDER] in the credentials is replaced with the SAME freshly generated id, so a
     * provider whose parameter spans both fields stays consistent. Because this is called once per session
     * creation, each new session presents a credential string the provider has not seen before and is given
     * a new exit IP from the pool.
     *
     * Values are JSON-escaped so a password containing a quote or backslash cannot break the request body.
     * @since 1.15.0
     * @param sessionId Session id to substitute. Defaults to a fresh one from [newSessionId]; pass an
     * explicit one when it also has to be logged, so the log and the request cannot disagree.
     * @return The proxy fragment, for example `,"proxy":{"url":"..."}`.
     */
    fun proxyJson(sessionId: String = newSessionId()): String {
        val user = username.replace(SESSION_PLACEHOLDER, sessionId)
        val pass = password.replace(SESSION_PLACEHOLDER, sessionId)

        return when {
            user.isNotBlank() && pass.isNotBlank() ->
                ""","proxy":{"url":"${jsonEscape(url)}","username":"${jsonEscape(user)}","password":"${jsonEscape(pass)}"}"""
            else -> ""","proxy":{"url":"${jsonEscape(url)}"}"""
        }
    }

    /**
     * The credentials for [sessionId], with every [SESSION_PLACEHOLDER] substituted exactly as [proxyJson]
     * does it.
     *
     * Needed because a client which fetches directly rather than through FlareSolverr has to present the same
     * sticky-session credentials, or the provider hands it a different exit IP and the clearance cookie - which
     * is bound to the address that solved the challenge - is worthless.
     * @since 1.16.0
     * @param sessionId Session id to substitute. Use the one the FlareSolverr session was created with.
     * @return Username and password, either of which may be blank when no credentials are configured.
     */
    fun credentials(sessionId: String): Pair<String, String> =
        username.replace(SESSION_PLACEHOLDER, sessionId) to password.replace(SESSION_PLACEHOLDER, sessionId)

    /**
     * Host and port of [url], for a client which needs them separately rather than as a URL string.
     * @since 1.16.0
     * @return Host and port.
     * @throws IllegalStateException if [url] carries no host or no explicit port.
     */
    fun proxyHostAndPort(): Pair<String, Int> {
        val parsed = URI(url)
        val host: String = parsed.host ?: EMPTY

        check(host.isNotBlank() && parsed.port > 0) {
            "$CONFIG_NAMESPACE.url [$url] needs a host and an explicit port to be used without FlareSolverr."
        }

        return host to parsed.port
    }

    /**
     * Whether requests for [hostname] may bypass the browser once its session has cleared Cloudflare.
     *
     * Restricted to the commercial proxy's own `http(s)://` endpoint. A `socks5://` endpoint is excluded
     * because credentials cannot be sent over SOCKS5 here (see the note on [url]), and a provider which is
     * not routed has no sticky exit IP for a clearance cookie to be bound to.
     * @since 1.16.0
     * @param hostname Hostname of a metadata provider.
     * @return `true` if the direct path is available for [hostname].
     */
    fun allowsDirectFetch(hostname: Hostname): Boolean =
        directFetch && isRouted(hostname) && url.startsWith("http", ignoreCase = true)

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

        // Neither case is fatal - the crawl still works - but both mean the operator almost certainly did not
        // get the rotation they intended, and silently crawling a 14,517-entry seed on a single IP is
        // expensive to discover late.
        val thresholds = providersForCommercialProxy.associateWith { rotateAfterRequests(it) }
        val rotating = thresholds.filterValues { it > 0 }.keys

        if (rotating.isNotEmpty() && !rotatesSessionId()) {
            log.warn {
                "$CONFIG_NAMESPACE.rotateAfterRequests would rotate [${rotating.sorted().joinToString(", ")}] but neither username nor password contains '$SESSION_PLACEHOLDER', so every session presents the same credentials. Whether a recreated session gets a new exit IP is then entirely up to the provider's gateway."
            }
        }
        if (rotating.isEmpty() && rotatesSessionId()) {
            log.warn {
                "$CONFIG_NAMESPACE.username/password contain '$SESSION_PLACEHOLDER' but no routed provider has a rotation threshold above 0, so the session is never recreated and the exit IP never changes."
            }
        }
    }

    companion object {
        private val log by LoggerDelegate()

        private const val CONFIG_NAMESPACE = "modb.app.proxy.commercial"

        /**
         * Rotation threshold for a routed provider which is not listed in `rotateAfterRequests`. Deliberately
         * a real value rather than `0`: an unconfigured routed provider silently never rotating would run a
         * whole crawl from one exit IP, which is the failure the pool exists to avoid.
         * @since 1.15.0
         */
        const val DEFAULT_ROTATE_AFTER_REQUESTS: Int = 50

        /**
         * Token replaced with a freshly generated session id wherever it appears in [username] or
         * [password]. Not any provider's own syntax - it marks the spot inside whichever parameter that
         * provider documents, because the spelling is not standardised across providers.
         * @since 1.15.0
         */
        const val SESSION_PLACEHOLDER: String = "{session}"

        /**
         * Lowercase alphanumerics only, and exactly [SESSION_ID_LENGTH] of them. That is the intersection of
         * what the providers accept: IPRoyal documents an exactly-8-character alphanumeric session id, while
         * the others take an arbitrary string, so an 8-character alphanumeric id is valid everywhere.
         */
        private const val SESSION_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val SESSION_ID_LENGTH = 8

        private fun randomSessionId(): String = (1..SESSION_ID_LENGTH)
            .map { SESSION_ID_ALPHABET.random() }
            .joinToString(EMPTY)

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
