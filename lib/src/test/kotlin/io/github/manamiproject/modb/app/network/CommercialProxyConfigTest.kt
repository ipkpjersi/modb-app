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

        @Test
        fun `substitutes a fresh session id into the username`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                url = "http://gw.example.com:7000",
                username = "user-NAME-session-{session}",
                password = "pass",
            ))

            // when
            val result = config.proxyJson()

            // then
            assertThat(result).doesNotContain("{session}")
            assertThat(result).containsPattern(""""username":"user-NAME-session-[a-z0-9]{8}"""")
        }

        @Test
        fun `substitutes a fresh session id into the password, which is where IPRoyal puts it`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                url = "http://geo.iproyal.com:12321",
                username = "user",
                password = "pass_session-{session}_lifetime-30m",
            ))

            // when
            val result = config.proxyJson()

            // then
            assertThat(result).doesNotContain("{session}")
            assertThat(result).containsPattern(""""password":"pass_session-[a-z0-9]{8}_lifetime-30m"""")
        }

        @Test
        fun `uses the same id in both fields so a parameter spanning them stays consistent`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                url = "http://gw.example.com:7000",
                username = "user-{session}",
                password = "pass-{session}",
            ))

            // when
            val result = config.proxyJson()

            // then
            val ids = Regex("""(?:user|pass)-([a-z0-9]{8})""").findAll(result).map { it.groupValues[1] }.toList()
            assertThat(ids).hasSize(2)
            assertThat(ids[0]).isEqualTo(ids[1])
        }

        @Test
        fun `generates a different id on every call, which is what makes a new session a new exit IP`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                url = "http://gw.example.com:7000",
                username = "user-{session}",
                password = "pass",
            ))

            // when
            val results = (1..25).map { config.proxyJson() }.toSet()

            // then
            // 36^8 possibilities, so 25 identical draws would not be chance.
            assertThat(results).hasSizeGreaterThan(20)
        }

        @Test
        fun `leaves credentials untouched when they carry no placeholder`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                url = "http://gw.example.com:7000",
                username = "user",
                password = "pass",
            ))

            // when
            val result = config.proxyJson()

            // then
            assertThat(result).isEqualTo(""","proxy":{"url":"http://gw.example.com:7000","username":"user","password":"pass"}""")
        }
    }

    @Nested
    inner class SessionRotationTests {

        @Test
        fun `rotateAfterRequests falls back to the default for an unlisted provider`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry())

            // when
            val result = config.rotateAfterRequests("anidb.net")

            // then
            // A real default rather than 0: an unconfigured routed provider that silently never rotated would
            // crawl everything from one exit IP, which is the failure the pool exists to prevent.
            assertThat(result).isEqualTo(50)
        }

        @Test
        fun `rotateAfterRequests is per provider`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                providers = listOf("anidb.net", "other.example"),
                rotateAfterRequests = listOf("anidb.net=50", "other.example=500"),
            ))

            // when / then
            assertThat(config.rotateAfterRequests("anidb.net")).isEqualTo(50)
            assertThat(config.rotateAfterRequests("other.example")).isEqualTo(500)
            assertThat(config.rotateAfterRequests("unlisted.example")).isEqualTo(50)
        }

        @Test
        fun `rotateAfterRequests of zero disables rotation for that provider only`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                rotateAfterRequests = listOf("anidb.net=0"),
            ))

            // when / then
            assertThat(config.rotateAfterRequests("anidb.net")).isZero()
            assertThat(config.rotateAfterRequests("other.example")).isEqualTo(50)
        }

        @Test
        fun `rotateAfterRequests strips a leading www and ignores hostname case`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                rotateAfterRequests = listOf("AniDB.net=7"),
            ))

            // when / then
            assertThat(config.rotateAfterRequests("www.anidb.net")).isEqualTo(7)
        }

        @Test
        fun `rotateAfterRequests throws for a malformed entry`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(
                rotateAfterRequests = listOf("anidb.net"),
            ))

            // when
            val result = exceptionExpected<IllegalStateException> {
                config.rotateAfterRequests("anidb.net")
            }

            // then
            assertThat(result).hasMessageContaining("Expected 'hostname=limit'")
        }

        @Test
        fun `rotatesSessionId is false without a placeholder`() {
            // given
            val config = CommercialProxyConfig(commercialRegistry(username = "user", password = "pass"))

            // when / then
            assertThat(config.rotatesSessionId()).isFalse()
        }

        @Test
        fun `rotatesSessionId is true for a placeholder in either field`() {
            // given
            val inUsername = CommercialProxyConfig(commercialRegistry(username = "user-{session}", password = "pass"))
            val inPassword = CommercialProxyConfig(commercialRegistry(username = "user", password = "pass-{session}"))

            // when / then
            assertThat(inUsername.rotatesSessionId()).isTrue()
            assertThat(inPassword.rotatesSessionId()).isTrue()
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
    rotateAfterRequests: List<String>? = null,
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
    override fun <T : Any> list(key: String): List<T>? = when {
        key.endsWith(".rotateAfterRequests") -> rotateAfterRequests as List<T>?
        else -> providers as List<T>?
    }
}
