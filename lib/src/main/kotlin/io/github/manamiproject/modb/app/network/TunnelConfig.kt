package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.BooleanPropertyDelegate
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.SetPropertyDelegate
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Proxy.NO_PROXY
import java.net.Proxy.Type.SOCKS

/**
 * Configuration for the reverse SSH tunnel which provides a residential exit IP.
 *
 * Some metadata provider ban this host's datacenter IP range outright. FlareSolverr solves their Cloudflare
 * challenge but cannot change the exit IP, and IPv6 rotation only shuffles addresses within the same
 * datacenter /64, so neither helps. Those provider(s) need their traffic to leave via a residential connection.
 *
 * The tunnel is established from the residential side: the home machine runs
 * `ssh -N -R <host>:<port>:127.0.0.1:1080 user@this-server`, which makes this server listen on
 * [host]:[port] and forwards anything sent there back down the existing SSH connection to a SOCKS5 daemon
 * running on the home machine. The server therefore holds no credentials for the home machine and cannot
 * initiate a connection to it - the forwarded port is the only channel, and the daemon on the other end
 * restricts which destinations it is willing to connect to.
 *
 * [host] defaults to the docker bridge gateway rather than `127.0.0.1` because FlareSolverr runs in a
 * bridge-network container, where `127.0.0.1` is the container itself. The bridge gateway is reachable by
 * both the containers and this host, and is not routable from the internet.
 *
 * @since 1.14.0
 * @property configRegistry Handles the retrieval of the value.
 */
class TunnelConfig(
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
) {

    /**
     * Whether the tunnel is in use. While `false` no provider is routed through the tunnel, regardless of
     * [providers]. **Default** is `false` so a host without a tunnel behaves exactly as before.
     * @since 1.14.0
     */
    val enabled: Boolean by BooleanPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = false,
        configRegistry = configRegistry,
    )

    /**
     * Address the reverse tunnel listens on. Must be reachable from the FlareSolverr container, which rules
     * out `127.0.0.1`. **Default** is the docker bridge gateway.
     * @since 1.14.0
     */
    val host: String by StringPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = "172.17.0.1",
        configRegistry = configRegistry,
    )

    /**
     * Port the reverse tunnel listens on.
     * @since 1.14.0
     */
    val port: Int by IntPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = 1080,
        configRegistry = configRegistry,
    )

    /**
     * Hostnames of the metadata provider(s) whose traffic is routed through the tunnel. Everything not listed
     * here keeps the direct datacenter path, which is both faster and does not consume the residential
     * connection's bandwidth.
     * @since 1.14.0
     */
    val providers: Set<Hostname> by SetPropertyDelegate(
        namespace = CONFIG_NAMESPACE,
        default = setOf(
            "anidb.net",
            "anime-planet.com",
            "anisearch.com",
            "simkl.com",
        ),
        configRegistry = configRegistry,
    )

    /**
     * A leading `www.` is stripped before matching. Provider configs are keyed by the bare hostname and
     * [io.github.manamiproject.modb.core.config.MetaDataProviderConfig.buildAnimeLink] builds URLs from it,
     * so this is defensive: a host that failed to match would silently take the direct path and walk into
     * the very IP ban the tunnel exists to avoid.
     * @since 1.14.0
     * @param hostname Hostname of a metadata provider.
     * @return `true` if traffic for [hostname] must be routed through the tunnel.
     */
    fun isTunneled(hostname: Hostname): Boolean =
        enabled && providers.contains(hostname.lowercase().removePrefix("www."))

    /**
     * Proxy to use for direct-scrape provider. Returns [NO_PROXY] for anything not routed through the
     * tunnel, which makes this safe to apply unconditionally at a client construction site.
     * @since 1.14.0
     * @param hostname Hostname of a metadata provider.
     * @return A SOCKS proxy pointing at the tunnel, or [NO_PROXY].
     */
    fun proxyFor(hostname: Hostname): Proxy = when {
        isTunneled(hostname) -> Proxy(SOCKS, InetSocketAddress(host, port))
        else -> NO_PROXY
    }

    /**
     * The tunnel as a SOCKS5 URL. This is the form FlareSolverr expects in the `proxy` field of its request
     * payload, and it is resolved from inside the FlareSolverr container.
     * @since 1.14.0
     * @return SOCKS5 URL of the tunnel.
     */
    fun socksUrl(): String = "socks5://$host:$port"

    companion object {
        private const val CONFIG_NAMESPACE = "modb.app.tunnel"

        /**
         * Singleton of [TunnelConfig]
         * @since 1.14.0
         */
        val instance: TunnelConfig by lazy { TunnelConfig() }
    }
}
