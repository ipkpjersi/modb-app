package io.github.manamiproject.modb.app.network

import io.github.manamiproject.kommand.CommandExecutor
import io.github.manamiproject.kommand.JavaProcessBuilder
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.extensions.EMPTY
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Docker image used for FlareSolverr.
 * @since 1.13.0
 */
private const val FLARESOLVERR_IMAGE = "ghcr.io/flaresolverr/flaresolverr:v3.5.0"


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
    val maxConcurrency by IntPropertyDelegate(
        namespace = "modb.app.flaresolverr",
        default = 1,
        configRegistry = configRegistry,
    )

    /**
     * Time in milliseconds FlareSolverr is allowed to spend solving a single challenge before it gives up.
     * Passed as `maxTimeout` in each request. A higher value makes heavy pages less likely to fail with an
     * internal error under load. Defaults to `120000` (2 minutes).
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
    val existingContainerId = currentConfig.commandExecutor.executeCmd(listOf(
        "docker",
        "ps",
        "--filter",
        "ancestor=$FLARESOLVERR_IMAGE",
        "--format",
        "{{.ID}}",
    )).trim()

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
        "${currentConfig.port}:8191",
        "-e",
        "LOG_LEVEL=${currentConfig.logLevel}",
        FLARESOLVERR_IMAGE,
    )).trim()

    if (output.lowercase().contains("error") || output.startsWith("docker:")) throw IllegalStateException("Error during container start:\n$output")

    return output
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