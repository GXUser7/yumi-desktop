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
    /** Process names, `chrome.exe`; see [RuleValidation.application]. */
    val applications: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    /** Nothing leaves outside the tunnel from connect until an explicit disconnect. */
    val killSwitch: Boolean = false,
    /** Russian sites go direct by the geo databases, above the application rules. */
    val bypassRussia: Boolean = true,
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
    /** The kill switch is holding traffic. Outlives a tunnel that died; a disconnect clears it. */
    val killSwitch: Boolean = false,
    /** The routing databases, as the service sees them on disk. */
    val geoReady: Boolean = false,
    val geoUpdating: Boolean = false,
    val geoUpdatedAt: Long = 0,
    val geoBytes: Long = 0,
    val geoError: String? = null,
) {
    fun probe() = if (state == "connected" && probePort > 0) ProbeEndpoint(probePort, probeUser, probePassword) else null
}

object ConnectRequestFactory {
    fun create(node: ProxyNode, settings: AppSettings, desktop: DesktopSettings): ServiceRequest {
        // One server, always the same one. The window never moves the tunnel by itself, so a
        // provider's auto-select group is carried as its first member — the provider's own first
        // choice — rather than as a balancer that would pick among them behind the user's back.
        val server = node.dialed().first().copy(id = node.id)
        val document = XrayConfigFactory.build(listOf(server), server, settings.copy(routingMode = RoutingMode.Global), geoAvailable = false)
        val outbound = Json.parseToJsonElement(document.json).jsonObject.getValue("outbounds").jsonArray.first().jsonObject
        return ServiceRequest(command = "connect", nodeId = node.id, outbound = outbound,
            options = TunnelOptions(settings.mtu, settings.enableIpv6, settings.bypassLan, settings.remoteDns,
                desktop.splitMode, desktop.applications.map(RuleValidation::application).distinct(),
                desktop.domains.map(RuleValidation::domain), desktop.networks.map(RuleValidation::network),
                killSwitch = desktop.killSwitch, bypassRussia = desktop.bypassRussia))
    }
}
