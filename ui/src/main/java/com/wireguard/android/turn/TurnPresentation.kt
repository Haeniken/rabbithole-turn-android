package com.wireguard.android.turn

import com.wireguard.config.Config

/** Presentation comments never change the active WireGuard or TURN connection. */
internal object TurnPresentation {
    private val metadata = Regex("^#@rhv:(ProfileName|ProfileOrder|ProfileSubtitle|SubscriptionName)\\s*=", RegexOption.IGNORE_CASE)

    private fun operationalConfig(config: Config) = config.toWgQuickString().lineSequence()
        .map(String::trim)
        .filterNot { it.isEmpty() || metadata.containsMatchIn(it) }
        .joinToString("\n")

    fun onlyPresentationChanged(old: Config, oldTurn: TurnSettings?, new: Config, newTurn: TurnSettings?): Boolean =
        oldTurn != null && newTurn != null &&
            oldTurn.connectionSettings() == newTurn.connectionSettings() &&
            operationalConfig(old) == operationalConfig(new)
}
