package io.github.manamiproject.modb.app.config

import io.github.manamiproject.modb.core.config.Hostname

/**
 * Parses per-provider integer limits expressed as `hostname=limit` strings, for example
 * `["anidb.net=100", "kitsu.app=500"]`.
 *
 * A list of strings rather than a TOML map because [io.github.manamiproject.modb.core.config.DefaultConfigRegistry]
 * flattens an inline table into individual dotted properties - so `{ "anidb.net" = 100 }` is stored as the
 * property `<namespace>.anidb.net` and a map lookup on the parent key returns `null`. A list is read back
 * intact, exactly as `deactivatedMetaDataProviders` is.
 *
 * Every malformed entry throws rather than being skipped. These limits exist to bound spend and request
 * volume against metered or quota-limited provider(s), so a value that is quietly dropped causes precisely
 * the unbounded run it was configured to prevent.
 *
 * @since 1.15.0
 * @param propertyName Name of the property being parsed, used in the error messages.
 * @param entries Raw `hostname=limit` entries.
 * @return The limit per hostname, hostnames lowercased.
 * @throws IllegalStateException if an entry is malformed, non-numeric, negative, or a duplicate.
 */
fun parseHostnameLimits(propertyName: String, entries: Collection<String>): Map<Hostname, Int> {
    val parsed = mutableMapOf<Hostname, Int>()

    entries.forEach { entry ->
        val parts = entry.split('=')

        check(parts.size == 2) { "Malformed entry in '$propertyName': [$entry]. Expected 'hostname=limit'." }

        val hostname = parts[0].trim().lowercase()
        val limit = parts[1].trim().toIntOrNull()

        check(hostname.isNotBlank()) { "Blank hostname in '$propertyName' entry: [$entry]." }
        check(limit != null) { "Non-numeric limit in '$propertyName' entry: [$entry]." }
        check(limit >= 0) { "Negative limit in '$propertyName' entry: [$entry]." }
        check(!parsed.containsKey(hostname)) { "Duplicate hostname in '$propertyName': [$hostname]." }

        parsed[hostname] = limit
    }

    return parsed
}
