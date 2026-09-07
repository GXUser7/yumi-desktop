package com.yumi.desktop.model

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.core.xray.XrayConfigFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class TunnelOptions(
    val mtu: Int = 1500,
    val ipv6: Boolean = false,
    val bypassLan: Boolean = true,
    val dns: String = "https://1.1.1.1/dns-query",
    val splitMode: SplitMode = SplitMode.ExceptSelected,
    val applications: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
)
@Serializable data class ServiceRequest(
    val version: Int = 1,
    val command: String,
    val nodeId: String? = null,
    val outbound: JsonObject? = null,
    val options: TunnelOptions? = null,
)
@Serializable data class ServiceStatus(
    val version: Int = 1,
    val state: String = "disconnected",
    val error: String? = null,
    /** What the core said, verbatim. The [error] code is the category; this is the sentence. */
    val detail: String? = null,
    val nodeId: String? = null,
    val connectedAt: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val probePort: Int = 0,
    val probeUser: String = "",
    val probePassword: String = "",
) {
    fun probe() = if (state == "connected" && probePort > 0) ProbeEndpoint(probePort, probeUser, probePassword) else null
}

object ConnectRequestFactory {
    fun create(node: ProxyNode, settings: AppSettings, desktop: DesktopSettings): ServiceRequest {
        val document = XrayConfigFactory.build(listOf(node), node, settings.copy(routingMode = RoutingMode.Global), geoAvailable = false)
        val outbound = Json.parseToJsonElement(document.json).jsonObject.getValue("outbounds").jsonArray.first().jsonObject
        return ServiceRequest(command = "connect", nodeId = node.id, outbound = outbound,
            options = TunnelOptions(settings.mtu, settings.enableIpv6, settings.bypassLan, settings.remoteDns,
                desktop.splitMode, desktop.applications.map(RuleValidation::application),
                desktop.domains.map(RuleValidation::domain), desktop.networks.map(RuleValidation::network)))
    }
}
