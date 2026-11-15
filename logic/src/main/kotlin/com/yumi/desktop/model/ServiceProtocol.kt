@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
    /** While connected the service moves the system timezone onto the server's and silences the
     *  system's location sources. The timezone follows the country, so no server data is needed. */
    val matchLocation: Boolean = false,
    /** Russian sites go direct by the geo databases, above the application rules. */
    val bypassRussia: Boolean = true,
    /** The physical adapter the core sends proxy traffic out of; empty follows the Windows
     *  default route. The service names adapters the way Xray does. */
    val outboundAdapter: String = "",
)

/** One physical adapter the service could send proxy traffic out of. */
@Serializable data class NetworkAdapter(
    val name: String,
    val address: String? = null,
    /** The adapter Windows itself uses for its default route. */
    val default: Boolean = false,
)
@Serializable data class ServiceRequest(
    val version: Int = 1,
    val command: String,
    val nodeId: String? = null,
    val outbound: JsonObject? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val group: OutboundGroup? = null,
    val options: TunnelOptions? = null,
    /** The active server's country, read off the flag in its name. Carries the timezone the
     *  service applies in [TunnelOptions.matchLocation] mode. Top level rather than inside
     *  [options] so a hot switch to a server in another country does not restart the worker. */
    val serverCountry: String = "",
)
@Serializable data class OutboundGroup(
    val members: List<JsonObject>,
    val strategy: String,
    val maxDelayMillis: Int,
)
@Serializable data class ServiceStatus(
    val version: Int = 1,
    val state: String = "disconnected",
    val error: String? = null,
    /** What the core said, verbatim. The [error] code is the category; this is the sentence. */
    val detail: String? = null,
    val nodeId: String? = null,
    val connectedAt: Long = 0,
    val groupMember: Int = 0,
    val groupMembers: Int = 0,
    val groupLatency: Int = 0,
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
    /** The location mode as the machine sees it: the timezone the service has put the system
     *  into, or the sentence about why it could not. Empty whenever the mode is off. */
    val sysTimezone: String = "",
    val sysNote: String = "",
    /** Answered only for the "adapters" command; the status poll leaves it empty. */
    val adapters: List<NetworkAdapter> = emptyList(),
) {
    fun probe() = if (state == "connected" && probePort > 0) ProbeEndpoint(probePort, probeUser, probePassword) else null
}

object ConnectRequestFactory {
    fun create(node: ProxyNode, settings: AppSettings, desktop: DesktopSettings): ServiceRequest {
        fun outbound(server: ProxyNode): JsonObject {
            val document = XrayConfigFactory.build(listOf(server), server, settings.copy(routingMode = RoutingMode.Global), geoAvailable = false)
            return Json.parseToJsonElement(document.json).jsonObject.getValue("outbounds").jsonArray.first().jsonObject
        }
        val members = node.dialed().map(::outbound)
        val outbound = members.first()
        return ServiceRequest(command = "connect", nodeId = node.id, outbound = outbound,
            group = node.group?.let { OutboundGroup(members, it.strategy, it.maxDelayMillis) },
            serverCountry = WorldMap.countryCodeOf(node.name) ?: "",
            options = TunnelOptions(
                mtu = settings.mtu,
                ipv6 = settings.enableIpv6,
                bypassLan = settings.bypassLan,
                dns = settings.remoteDns,
                splitMode = desktop.splitMode,
                applications = desktop.applications.map(RuleValidation::application).distinct(),
                domains = desktop.domains.map(RuleValidation::domain),
                networks = desktop.networks.map(RuleValidation::network),
                killSwitch = desktop.killSwitch,
                matchLocation = desktop.matchServerLocation,
                bypassRussia = desktop.bypassRussia,
                outboundAdapter = desktop.outboundAdapter,
            ))
    }
}
