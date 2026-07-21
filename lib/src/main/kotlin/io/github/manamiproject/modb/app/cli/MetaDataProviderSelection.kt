package io.github.manamiproject.modb.app.cli

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.Hostname

/**
 * Resolves which metadata providers a single run crawls, based on the command line arguments passed to the app. This
 * lets a targeted run override 'deactivatedMetaDataProviders' from config.toml without editing config or rebuilding
 * the jar.
 *
 * Supported arguments ([ONLY_FLAG] and [SKIP_FLAG] are mutually exclusive):
 * - `--only <csv>` (alias `--providers <csv>`): crawl exactly the listed providers. Every other provider is treated
 *   as deactivated for this run, including providers config.toml did not deactivate. It can therefore also re-enable
 *   a provider that config.toml deactivated, e.g. a residential-only provider for a one-off test run.
 * - `--skip <csv>`: additionally deactivate the listed providers on top of whatever config.toml already deactivates.
 *
 * A provider is named either by its full hostname ('anidb.net') or by the label before the first dot ('anidb'). Both
 * flags accept the space form ('--only anidb') or the '=' form ('--only=anidb') and a comma separated list. When
 * neither flag is present the config.toml value is returned unchanged, so a normal run is unaffected.
 * @since 1.14.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 */
class MetaDataProviderSelection(
    private val appConfig: Config = AppConfig.instance,
) {

    /**
     * Computes the hostnames which must not have a crawler started for this run.
     * @since 1.14.0
     * @param args Command line arguments exactly as received by main.
     * @return Set of deactivated hostnames after applying the command line arguments over the config.
     * @throws IllegalArgumentException if the arguments are malformed, contradictory or name an unknown provider.
     */
    fun deactivatedProvidersForRun(args: Array<String>): Set<Hostname> {
        val knownHostnames = appConfig.metaDataProviderConfigurations().map { it.hostname() }.toSet()

        return when (val selection = parse(args)) {
            null -> appConfig.deactivatedMetaDataProviders()
            is Selection.Only -> knownHostnames - resolve(selection.tokens, knownHostnames)
            is Selection.Skip -> appConfig.deactivatedMetaDataProviders() + resolve(selection.tokens, knownHostnames)
        }
    }

    private fun parse(args: Array<String>): Selection? {
        var only: List<String>? = null
        var skip: List<String>? = null

        var index = 0
        while (index < args.size) {
            val arg = args[index]
            val separatorIndex = arg.indexOf('=')
            val flag = if (separatorIndex >= 0) arg.substring(0, separatorIndex) else arg
            val inlineValue = if (separatorIndex >= 0) arg.substring(separatorIndex + 1) else null

            when (flag) {
                ONLY_FLAG, PROVIDERS_FLAG, SKIP_FLAG -> {
                    // Without an inline value ('--only=x') consume the next argument, but never another flag, so a
                    // missing value fails fast instead of silently swallowing the following flag.
                    val value = if (inlineValue != null) {
                        inlineValue
                    } else {
                        args.getOrNull(index + 1)?.takeUnless { it.startsWith("-") }?.also { index++ }
                    }
                    val tokens = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    require(tokens.isNotEmpty()) { "Missing value for [$flag]." }

                    // --providers is an alias for --only.
                    if (flag == SKIP_FLAG) {
                        require(skip == null) { "[$flag] must not be passed more than once." }
                        skip = tokens
                    } else {
                        require(only == null) { "[$flag] must not be passed more than once." }
                        only = tokens
                    }
                }
                else -> throw IllegalArgumentException("Unknown argument: [$arg].")
            }
            index++
        }

        require(only == null || skip == null) { "[$ONLY_FLAG] and [$SKIP_FLAG] are mutually exclusive." }

        return when {
            only != null -> Selection.Only(only)
            skip != null -> Selection.Skip(skip)
            else -> null
        }
    }

    private fun resolve(tokens: List<String>, knownHostnames: Set<Hostname>): Set<Hostname> {
        return tokens.map { token ->
            knownHostnames.firstOrNull { it == token || it.substringBefore('.') == token }
                ?: throw IllegalArgumentException(
                    "Unknown metadata provider: [$token]. Valid values: [${knownHostnames.map { it.substringBefore('.') }.sorted().joinToString(", ")}]."
                )
        }.toSet()
    }

    private sealed interface Selection {
        data class Only(val tokens: List<String>): Selection
        data class Skip(val tokens: List<String>): Selection
    }

    companion object {
        private const val ONLY_FLAG = "--only"
        private const val PROVIDERS_FLAG = "--providers"
        private const val SKIP_FLAG = "--skip"

        /**
         * Usage text describing the supported command line arguments.
         * @since 1.14.0
         */
        const val USAGE = """Usage: modb-app [--only <providers> | --skip <providers>]
  --only <providers>  Crawl only the comma separated providers, deactivating all others for this run.
                      (--providers is an alias for --only.)
  --skip <providers>  Deactivate the comma separated providers in addition to config.toml.
  A provider is named by hostname (anidb.net) or its short label (anidb). --only and --skip are mutually exclusive."""
    }
}
