package io.github.manamiproject.modb.app.network

import io.github.manamiproject.kommand.CommandExecutor
import io.github.manamiproject.kommand.JavaProcessBuilder
import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.excludeFromTestContext
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.anime.Seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.time.DurationUnit.SECONDS
import kotlin.time.toDuration

/**
 * Linux based [NetworkController] which rotates the outbound IPv6 source address.
 *
 * The application crawls various metadata providers. To avoid being blocked by per-IP rate limiting it changes its
 * outbound source IP address whenever a provider starts to reject requests. This implementation expects a routed IPv6
 * `/64` to be available on the configured network interface (see [Config.ipv6Prefix]). Every address within that `/64`
 * is routed to the machine, so a fresh random address can be used as the new outbound source at any time.
 *
 * A "restart" therefore does the following:
 * 1. Add a new random address from the configured `/64` to the interface. It becomes the preferred source address.
 * 2. On the very first restart deprecate any pre-existing global address of the `/64` (for example the static host
 *    address) so that it is no longer used as an outbound source. Deprecated addresses stay valid for inbound traffic
 *    such as SSH.
 * 3. Remove the previously used rotation address. Removing it terminates its open connections and forces the crawlers
 *    to reconnect using the new source address.
 *
 * Unlike a network device restart this does not bring the interface down and up, so other services running on the same
 * machine keep their connectivity.
 * @since 18.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property commandExecutor Generic CLI command executor.
 * @property timeRangeForMaxRestarts Time range in seconds in which the number of restarts defined by [maxNumberOfRestarts] is allowed.
 * @property maxNumberOfRestarts Maximum number of restarts that are allowed to occur within the time defined by [timeRangeForMaxRestarts].
 * @property ipv6AddressGenerator Creates a new random IPv6 address within the configured `/64`. Overridable for tests.
 * @throws TooManyRestartsException if the number of restarts within [timeRangeForMaxRestarts] exceeds [maxNumberOfRestarts].
 */
class LinuxNetworkController(
    private val appConfig: Config = AppConfig.instance,
    private val commandExecutor: CommandExecutor = JavaProcessBuilder.instance,
    private val timeRangeForMaxRestarts: Seconds = 600,
    private val maxNumberOfRestarts: Int = timeRangeForMaxRestarts + timeRangeForMaxRestarts / 2,
    private val ipv6AddressGenerator: () -> String = { randomIpv6Address(appConfig.ipv6Prefix()) },
): NetworkController {

    private val writeLock = Mutex()
    private var isNetworkActive = true
    private val restarts = mutableListOf<LocalDateTime>()
    private var currentRotationAddress: String? = null
    private var hostAddressesDeprecated = false
    private val deprecatedHostAddresses = mutableListOf<String>()

    /**
     * Sudo password required to add, remove or change IPv6 addresses on the network interface.
     * @since 18.0.0
     */
    var sudoPasswordValue = EMPTY

    override suspend fun restartAsync(): Deferred<Boolean> = withContext(LIMITED_NETWORK) {
        return@withContext writeLock.withLock {
            if (!isRestartRequestValid()) {
                log.info { "Ignoring request to rotate the IPv6 address, because it is already rotating or has been rotated within the last minute." }
                return@withLock async { false }
            }

            isNetworkActive = false
            log.info { "IPv6 address rotation has been triggered." }

            async {
                val networkInterface = appConfig.networkInterface()
                val preExistingAddresses = if (!hostAddressesDeprecated) {
                    globalAddresses(networkInterface)
                } else {
                    emptyList()
                }

                val newAddress = ipv6AddressGenerator.invoke()
                log.info { "Rotating outbound IPv6 source address to [$newAddress]." }
                addAddress(networkInterface, newAddress)

                waitForLastCallsToSucceed()

                if (!hostAddressesDeprecated) {
                    preExistingAddresses.forEach { deprecateAddress(networkInterface, it) }
                    deprecatedHostAddresses.addAll(preExistingAddresses)
                    hostAddressesDeprecated = true
                }

                currentRotationAddress?.let { removeAddress(networkInterface, it) }
                currentRotationAddress = newAddress

                isNetworkActive = true
                return@async true
            }
        }
    }

    override fun isNetworkActive(): Boolean = isNetworkActive

    /**
     * Reverts the changes made to the network interface: re-promotes the previously deprecated host addresses to
     * preferred again and removes the currently used rotation address. Intended to be called on shutdown so the machine
     * is left in its original state after a run. Does nothing if no rotation has taken place.
     * @since 18.0.0
     */
    fun restore() {
        if (deprecatedHostAddresses.isEmpty() && currentRotationAddress == null) {
            return
        }

        val networkInterface = appConfig.networkInterface()
        deprecatedHostAddresses.forEach { restoreAddress(networkInterface, it) }
        currentRotationAddress?.let { removeAddress(networkInterface, it) }

        deprecatedHostAddresses.clear()
        currentRotationAddress = null
        hostAddressesDeprecated = false
        log.info { "Restored the original IPv6 address configuration." }
    }

    private fun isRestartRequestValid(): Boolean {
        val now = LocalDateTime.now(appConfig.clock())

        if (restarts.isEmpty()) {
            restarts.add(now)
            return true
        }

        val differenceInSeconds = differenceInSeconds(restarts.first(), now)

        if (differenceInSeconds <= 30) {
            return false
        }

        if (restarts.size < maxNumberOfRestarts && differenceInSeconds < timeRangeForMaxRestarts) {
            restarts.add(now)
            return true
        }

        if (restarts.size < maxNumberOfRestarts && differenceInSeconds > timeRangeForMaxRestarts) {
            restarts.clear()
            restarts.add(now)
            return true
        }

        throw TooManyRestartsException(maxNumberOfRestarts, timeRangeForMaxRestarts)
    }

    private fun globalAddresses(networkInterface: String): List<String> {
        val prefixBase = ipv6PrefixBase(appConfig.ipv6Prefix())
        val output = runIp(listOf("ip", "-o", "-6", "addr", "show", "dev", networkInterface, "scope", "global"), useSudo = false)

        return INET6_REGEX.findAll(output)
            .map { it.groupValues[1] }
            .filter { it.substringBefore('/').startsWith(prefixBase) }
            .toList()
    }

    private fun addAddress(networkInterface: String, address: String) {
        // `nodad` skips Duplicate Address Detection. The whole /64 is routed to this single machine, so a duplicate is
        // impossible and DAD would only leave the address in the unusable `tentative` state for a moment, during which
        // the kernel would keep using the old (deprecated) source address.
        runIp(listOf("sudo", "ip", "-6", "addr", "add", "$address/64", "dev", networkInterface, "nodad"), useSudo = true)
    }

    private fun removeAddress(networkInterface: String, address: String) {
        runIp(listOf("sudo", "ip", "-6", "addr", "del", "$address/64", "dev", networkInterface), useSudo = true)
    }

    private fun deprecateAddress(networkInterface: String, addressWithPrefixLength: String) {
        runIp(listOf("sudo", "ip", "-6", "addr", "change", addressWithPrefixLength, "dev", networkInterface, "valid_lft", "forever", "preferred_lft", "0"), useSudo = true)
    }

    private fun restoreAddress(networkInterface: String, addressWithPrefixLength: String) {
        runIp(listOf("sudo", "ip", "-6", "addr", "change", addressWithPrefixLength, "dev", networkInterface, "valid_lft", "forever", "preferred_lft", "forever"), useSudo = true)
    }

    private fun runIp(command: List<String>, useSudo: Boolean): String {
        commandExecutor.config.useSudo = useSudo
        commandExecutor.config.sudoPassword = if (useSudo) sudoPasswordValue else EMPTY

        val output = commandExecutor.executeCmd(command)

        commandExecutor.config.useSudo = false
        commandExecutor.config.sudoPassword = EMPTY

        return output
    }

    private fun differenceInSeconds(previous: LocalDateTime, recent: LocalDateTime): Long {
        var difference = 0L
        val seconds = previous.until(recent, ChronoUnit.SECONDS)
        difference += seconds

        return difference
    }

    @KoverIgnore
    private suspend fun waitForLastCallsToSucceed() {
        excludeFromTestContext(appConfig) {
            delay(5.toDuration(SECONDS))
        }
    }

    companion object {
        private val log by LoggerDelegate()
        private val INET6_REGEX = """inet6\s+([0-9a-fA-F:]+/\d+)\s+scope global""".toRegex()

        /**
         * Creates a random IPv6 address within the given `/64` prefix.
         * @since 18.0.0
         * @param prefix IPv6 `/64` prefix in CIDR notation, for example `2001:db8:1234:5678::/64`.
         * @return A random address within [prefix], for example `2001:db8:1234:5678:1a2b:3c4d:5e6f:7890`.
         */
        fun randomIpv6Address(prefix: String): String {
            val base = ipv6PrefixBase(prefix)
            val interfaceIdentifier = (1..4).joinToString(":") { Random.nextInt(0, 0x10000).toString(16) }
            return "$base:$interfaceIdentifier"
        }

        private fun ipv6PrefixBase(prefix: String): String {
            return prefix.substringBefore("::")
                .substringBefore("/")
                .trimEnd(':')
        }

        /**
         * Singleton of [LinuxNetworkController]
         * @since 18.0.0
         */
        val instance: LinuxNetworkController by lazy { LinuxNetworkController() }
    }
}
