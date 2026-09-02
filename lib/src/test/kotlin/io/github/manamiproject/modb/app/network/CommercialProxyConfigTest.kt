package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.test.exceptionExpected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.test.Test

internal class CommercialProxyConfigTest {

    @Nested
    inner class DefaultTests {

        @Test
        fun `commercial proxy is disabled by default`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry())

            // when
            val result = config.enabled

            // then
            assertThat(result).isFalse()
        }

        @Test
        fun `default flaresolverr port is 8192 so it does not collide with the tunnel container`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry())

            // when
            val result = config.flaresolverrPort

            // then
            assertThat(result).isEqualTo(8192)
        }

        @Test
        fun `default routed provider is anidb`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry())

            // when
            val result = config.providersForCommercialProxy

            // then
            assertThat(result).containsExactly("anidb.net")
        }

        @Test
        fun `url, username and password default to empty`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry())

            // when / then
            assertThat(config.url).isEmpty()
            assertThat(config.username).isEmpty()
            assertThat(config.password).isEmpty()
        }
    }

    @Nested
    inner class IsRoutedTests {

        @Test
        fun `returns false for a listed provider while disabled`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(enabled = false))

            // when
            val result = config.isRouted("anidb.net")

            // then
            assertThat(result).isFalse()
        }

        @Test
        fun `returns true for a listed provider while enabled`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(enabled = true))

            // when
            val result = config.isRouted("anidb.net")

            // then
            assertThat(result).isTrue()
        }

        @Test
        fun `returns false for a provider which is not routed`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(enabled = true))

            // when
            val result = config.isRouted("myanimelist.net")

            // then
            assertThat(result).isFalse()
        }

        @Test
        fun `strips a leading www`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(enabled = true))

            // when
            val result = config.isRouted("www.anidb.net")

            // then
            assertThat(result).isTrue()
        }

        @Test
        fun `matching is case insensitive`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(enabled = true))

            // when
            val result = config.isRouted("AniDB.net")

            // then
            assertThat(result).isTrue()
        }
    }

    @Nested
    inner class ProxyJsonTests {

        @Test
        fun `url only when no credentials are set`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(url = "socks5://gw.example.com:7000"))

            // when
            val result = config.proxyJson()

            // then
            assertThat(result).isEqualTo(""","proxy":{"url":"socks5://gw.example.com:7000"}""")
        }

        @Test
        fun `includes credentials when both are set`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                url = "socks5://gw.example.com:7000",
                username = "user",
                password = "pass",
            ))

            // when
            val result = config.proxyJson()

            // then
            assertThat(result).isEqualTo(""","proxy":{"url":"socks5://gw.example.com:7000","username":"user","password":"pass"}""")
        }

        @Test
        fun `omits credentials when only username is set`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                url = "socks5://gw.example.com:7000",
                username = "user",
            ))

            // when
            val result = config.proxyJson()

            // then
            assertThat(result).isEqualTo(""","proxy":{"url":"socks5://gw.example.com:7000"}""")
        }

        @Test
        fun `json escapes credential values so a quote or backslash cannot break the body`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                url = "socks5://gw.example.com:7000",
                username = "us\"er",
                password = "pa\\ss",
            ))

            // when
            val result = config.proxyJson()

            // then
            assertThat(result).isEqualTo(""","proxy":{"url":"socks5://gw.example.com:7000","username":"us\"er","password":"pa\\ss"}""")
        }
    }

    @Nested
    inner class ValidateTests {

        @Test
        fun `does nothing while disabled even with a blank url`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(enabled = false, url = ""))

            // when / then (no exception)
            config.validate()
        }

        @Test
        fun `throws when enabled but url is blank`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(enabled = true, url = ""))

            // when
            val result = exceptionExpected<IllegalStateException> {
                config.validate()
            }

            // then
            assertThat(result).hasMessageContaining("url")
        }

        @Test
        fun `throws when only username is set`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                enabled = true,
                url = "socks5://gw.example.com:7000",
                username = "user",
            ))

            // when
            val result = exceptionExpected<IllegalStateException> {
                config.validate()
            }

            // then
            assertThat(result).hasMessageContaining("both or neither")
        }

        @Test
        fun `passes when enabled with a url and no credentials`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(enabled = true, url = "socks5://gw.example.com:7000"))

            // when / then (no exception)
            config.validate()
        }

        @Test
        fun `passes when enabled with a url and both credentials`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                enabled = true,
                url = "socks5://gw.example.com:7000",
                username = "user",
                password = "pass",
            ))

            // when / then (no exception)
            config.validate()
        }
    }
}

/**
 * [CommercialProxyConfig] reads one boolean, one int and one list, plus three distinct string properties, so
 * the string getter switches on the key suffix. `null` means "not configured", so the delegate falls back to
 * its default.
 */
private fun commercialRegistry(
    enabled: Boolean? = null,
    flaresolverrPort: Int? = null,
    providers: List<String>? = null,
    url: String? = null,
    username: String? = null,
    password: String? = null,
) = object : ConfigRegistry by TestConfigRegistry {
    override fun boolean(key: String): Boolean? = enabled
    override fun int(key: String): Int? = flaresolverrPort
    override fun string(key: String): String? = when {
        key.endsWith(".url") -> url
        key.endsWith(".username") -> username
        key.endsWith(".password") -> password
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> list(key: String): List<T>? = providers as List<T>?
}
