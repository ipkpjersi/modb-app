package io.github.manamiproject.modb.app.config

import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig

/**
 * [Config] decorator which replaces the set of deactivated metadata providers, for example with a selection derived
 * from command line arguments. Every other property is delegated unchanged to [delegate].
 *
 * Both [deactivatedMetaDataProviders] and [isDeactivated] are overridden: interface delegation would otherwise route
 * the default [isDeactivated] implementation back to the delegate's own deactivated set instead of this override.
 * @since 1.14.0
 * @property delegate Config that provides every property other than the deactivated providers.
 * @property deactivatedProviders Hostnames to report as deactivated, replacing whatever [delegate] would report.
 */
class SelectedProvidersConfig(
    private val delegate: Config,
    private val deactivatedProviders: Set<Hostname>,
): Config by delegate {

    override fun deactivatedMetaDataProviders(): Set<Hostname> = deactivatedProviders

    override fun isDeactivated(metaDataProviderConfig: MetaDataProviderConfig): Boolean =
        deactivatedProviders.contains(metaDataProviderConfig.hostname())
}
