package io.github.manamiproject.modb.app.network

import io.github.manamiproject.kommand.CommandExecutor
import io.github.manamiproject.kommand.JavaProcessBuilder
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.BooleanPropertyDelegate
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.httpclient.HttpClient
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Docker image used for FlareSolverr.
 * @since 1.13.0
 */
/**
 * Image used both to start a container and - via `docker ps --filter ancestor=` - to recognise one that is
 * already running. Those two uses make the exact tag load-bearing: upgrading the running container without
 * changing this constant makes the reuse lookup miss, so a second container is started and fails with
 * "port is already allocated" (which is what happened on 2026-10-16).
 *
 * v3.5.2 is the minimum. On v3.5.0 an authenticated proxy is silently ignored - its generated proxy
 * extension relies on `--load-extension`, which that build's Chromium no longer honours, so the browser ends
 * up with no proxy at all and exits from this host's own IP. That is a ban risk rather than an error, since
 * nothing in the response says the proxy was skipped.
 */
private const val FLARESOLVERR_IMAGE = "ghcr.io/flaresolverr/flaresolverr:v3.5.2"


/**
 * Configuration flaresolverr.
 * @since 1.13.0
 * @property configRegistry Handles the retrieval of the value.
 */
class FlaresolverrConfig(
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
) {

    val port by IntPropertyDelegate(
        namespace = "modb.app.flaresolverr",
        default = 8191,
        configRegistry = configRegistry,
    )

    val logLevel by StringPropertyDelegate(
        namespace = "modb.app.flaresolverr",
        default = "info",
        configRegistry = configRegistry,
    )

    /**
     * Maximum number of requests sent to FlareSolverr concurrently. FlareSolverr solves one browser challenge
     * at a time, so hitting it concurrently causes it to return HTTP 500 under load. Defaults to `1` which
     * serializes all access. Raise it only if you run a FlareSolverr setup that can handle parallel requests.
     * @since 1.13.0
     */
    /**
     * Whether to resolve and log the exit IP of each newly created session which goes through a proxy.
     *
     * Costs one extra request per session creation - a few hundred over a full anidb seed, which is nothing
     * against pages measured in hundreds of kilobytes. Worth it because the crawl log otherwise cannot show
     * that rotation actually changed the exit IP: the app only ever sees FlareSolverr's own session id, and a
     * new session id proves a new browser, not a new address.
     *
     * **Default** is `true`.
     * @since 1.15.0
     */
    val logExitIp by BooleanPropertyDelegate(
        namespace = "modb.app.flaresolverr",
        default = true,
        configRegistry = configRegistry,
    )

    val maxConcurrency by IntPropertyDelegate(
        namespace = "modb.app.flaresolverr",
        default = 1,
        configRegistry = configRegistry,
    )

    /**
     * Time in milliseconds FlareSolverr is allowed to spend solving a single challenge before it gives up.
     * Passed as `maxTimeout` in each request. A higher value makes heavy pages less likely to fail with an
     * internal error under load. Defaults to `120000` (2 minutes).
     *
     * Raising this is not free, because the budget is spent in full on every failure rather than only on a
     * slow success. Measured against anidb on 2026-10-16: the slowest solves which still succeeded took 146s,
     * 117s, 116s, 104s and 103s, and nothing between 150s and 240s ever succeeded. Set it from what the
     * site's solves actually cost plus some headroom, rather than generously.
     * @since 1.13.0
     */
    val maxTimeout by IntPropertyDelegate(
        namespace = "modb.app.flaresolverr",
        default = 120000,
        configRegistry = configRegistry,
    )

    companion object {
        /**
         * Singleton of [FlaresolverrConfig]
         * @since 1.13.0
         */
        val instance: FlaresolverrConfig by lazy { FlaresolverrConfig() }
    }
}

/**
 * Configuration for [startFlaresolverr] and [stopFlaresolverr].
 * @since 1.13.0
 * @property commandExecutor Execution platform for commands.
 */
data class FlaresolverrActionConfig(
    var commandExecutor: CommandExecutor = JavaProcessBuilder.instance,
    var port: Int = FlaresolverrConfig.instance.port,
    var logLevel: String = FlaresolverrConfig.instance.logLevel,
    var clock: Clock = Clock.systemUTC(),
)

/**
 * Starts flaresolverr via docker.
 * @since 1.13.0
 * @param config Configuration.
 * @return The container ID if the container was able to start successfully.
 */
fun startFlaresolverr(config: FlaresolverrActionConfig.() -> Unit = { }): String {
    val currentConfig = FlaresolverrActionConfig().apply(config)

    // Reuse an already-running FlareSolverr container instead of recreating one on every run. Reusing is
    // faster and lets the container be managed externally (e.g. kept permanently up on a crawl host). The
    // container is matched by its image so the name doesn't matter. A blank return value signals "reused a
    // pre-existing container" so that stopFlaresolverr leaves it running.
    // Match by the host-published port, not just by image, because there can be two FlareSolverr containers of
    // the same image (the tunnel one and the commercial one). A published mapping shows in the ports column as
    // '127.0.0.1:<port>->8191/tcp', so the ':<port>->' fragment identifies this endpoint's container
    // unambiguously - the image's inert EXPOSE of a port has no '->' and cannot false-match.
    val existingContainerId = currentConfig.commandExecutor.executeCmd(listOf(
        "docker",
        "ps",
        "--filter",
        "ancestor=$FLARESOLVERR_IMAGE",
        "--format",
        "{{.ID}} {{.Ports}}",
    )).trim()
        .lineSequence()
        .firstOrNull { it.contains(":${currentConfig.port}->") }
        ?.substringBefore(' ')
        ?.trim()
        .orEmpty()

    if (existingContainerId.isNotBlank()) {
        return EMPTY
    }

    val output = currentConfig.commandExecutor.executeCmd(listOf(
        "docker",
        "run",
        "-d",
        "--rm",
        "--name=flaresolverr-${LocalDateTime.now(currentConfig.clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}",
        "-p",
        "127.0.0.1:${currentConfig.port}:8191", // bind to localhost only - never expose FlareSolverr publicly (open-proxy abuse risk)
        "-e",
        "LOG_LEVEL=${currentConfig.logLevel}",
        FLARESOLVERR_IMAGE,
    )).trim()

    if (output.lowercase().contains("error") || output.startsWith("docker:")) throw IllegalStateException("Error during container start:\n$output")

    return output
}

/**
 * Destroys every FlareSolverr session created during this run.
 *
 * Each session holds a browser open inside the container. Since [startFlaresolverr] deliberately reuses an
 * already-running container - and [stopFlaresolverr] leaves such a container running - nothing else would
 * ever reclaim them, so they would accumulate across runs.
 *
 * Call this before [stopFlaresolverr]: destroying a session is itself a request to the container.
 *
 * @since 1.14.0
 */
suspend fun destroyFlaresolverrSessions() {
    FlaresolverrHttpClient.instance.destroySessions()
    if (CommercialProxyConfig.instance.enabled) {
        FlaresolverrHttpClient.commercialInstance.destroySessions()
    }
}

/**
 * Returns a [FlaresolverrHttpClient] bound to the correct FlareSolverr container for [hostname]: the
 * commercial-proxy container for a commercially-routed provider, otherwise the default (tunnel) container.
 *
 * While the commercial proxy is disabled this always returns the default client, so a provider behaves
 * exactly as it did before.
 * @since 1.15.0
 * @param hostname Hostname of the metadata provider the client will be used for.
 * @return An [HttpClient] talking to the container that provider's traffic must exit through.
 */
fun flaresolverrHttpClientFor(hostname: Hostname): HttpClient = when {
    CommercialProxyConfig.instance.isRouted(hostname) -> FlaresolverrHttpClient(port = CommercialProxyConfig.instance.flaresolverrPort)
    else -> FlaresolverrHttpClient()
}

/**
 * Stops the flaresolverr docker container.
 * @since 1.13.0
 * @param config Configuration.
 * @return The container ID if the container was able to start successfully.
 */
fun stopFlaresolverr(containerId: String, config: FlaresolverrActionConfig.() -> Unit = { }) {
    // A blank id means startFlaresolverr reused a pre-existing container - leave it running.
    if (containerId.isBlank()) return

    val currentConfig = FlaresolverrActionConfig().apply(config)
    val output = currentConfig.commandExecutor.executeCmd(listOf(
        "docker",
        "stop",
        containerId,
    )).trim()

    if (output != containerId.trim()) throw IllegalStateException("Error during container stop:\n$output")
}