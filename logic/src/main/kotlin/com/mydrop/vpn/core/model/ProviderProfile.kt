package com.mydrop.vpn.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The routing and DNS a provider wrote into one server's configuration document.
 *
 * Stored already translated into roles rather than as the provider's own tags, because tags inside a
 * document name its own outbounds while the active desktop tunnel routes through one balancer/proxy
 * outbound.
 */
@Serializable
data class ProviderProfile(
    /** In the provider's order, which is the order Xray tries them in. */
    val rules: List<ProviderRule> = emptyList(),
    /** Null when the document named no resolver this core can use. */
    val dns: ProviderDns? = null,
    /** Rules in the document that could not be carried safely. Counted for diagnostics. */
    val skippedRules: Int = 0,
) {
    val isEmpty: Boolean get() = rules.isEmpty() && dns == null
}

/**
 * One routing rule: the conditions exactly as the provider wrote them, and where matching traffic
 * goes.
 */
@Serializable
data class ProviderRule(
    val match: JsonObject,
    val target: ProviderTarget,
)

/** What a provider's rule does with the traffic it matches, independent of anybody's tag names. */
@Serializable
enum class ProviderTarget {
    Proxy,
    Direct,
    Block,
}

/**
 * The provider's resolvers.
 */
@Serializable
data class ProviderDns(
    val servers: List<JsonElement>,
    /** `UseIP`, `UseIPv4` or `UseIPv6` as the provider set it; the tunnel may narrow it. */
    val queryStrategy: String? = null,
)

/**
 * Every distinct provider profile in a subscription, and which server carries which.
 */
@Serializable
data class ProviderRouting(
    /** Each distinct profile once, under a key derived from its content. */
    val profiles: Map<String, ProviderProfile> = emptyMap(),
    /** Server id to the key of the profile its document carried. */
    val byNode: Map<String, String> = emptyMap(),
) {
    fun profileFor(nodeId: String): ProviderProfile? = byNode[nodeId]?.let(profiles::get)

    /** Only the servers that still exist, and only the profiles one of them still uses. */
    fun keepingOnly(nodeIds: Set<String>): ProviderRouting {
        val kept = byNode.filterKeys { it in nodeIds }
        val used = kept.values.toSet()
        return ProviderRouting(profiles = profiles.filterKeys { it in used }, byNode = kept)
    }
}
