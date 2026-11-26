@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.yumi.desktop.model

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.core.xray.XrayConfigFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class FragmentSpec(
    val packets: String,
    val length: String,
    val interval: String,
    val maxSplit: String = "",
)

@Serializable data class CustomRulesSpec(
    val proxy: List<String> = emptyList(),
    val direct: List<String> = emptyList(),
    val block: List<String> = emptyList(),
)

@Serializable data class ProviderRuleSpec(
    val match: JsonObject,
    val target: String,
)

@Serializable data class ProviderDnsSpec(
    val servers: List<JsonElement> = emptyList(),
    val queryStrategy: String? = null,
)

@Serializable data class ProviderProfileSpec(
    val rules: List<ProviderRuleSpec> = emptyList(),
    val dns: ProviderDnsSpec? = null,
)

@Serializable data class ProbeTargetSpec(
    val id: String,
    val outbound: JsonObject,
    val fragment: FragmentSpec? = null,
)

@Serializable data class ProbeSpec(
    val targets: List<ProbeTargetSpec>,
    val probeUrl: String,
    val timeoutMillis: Int,
    val concurrency: Int = 8,
    val outboundAdapter: String = "",
    val resolveServerAddress: Boolean = true,
    val resolveDohUrl: String = "",
)

@Serializable data class ProbeResultItem(
    val id: String,
    val millis: Int,
)

@Serializable data class TunnelOptions(
    val mtu: Int = 1500,
    val ipv6: Boolean = false,
    val bypassLan: Boolean = true,
    val dns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "",
    val hijackDns: Boolean = true,
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
    val blockQuic: Boolean = false,
    val blockAds: Boolean = false,
    val customRules: CustomRulesSpec? = null,
    val providerProfile: ProviderProfileSpec? = null,
    /** The physical adapter the core sends proxy traffic out of; empty follows the Windows
     *  default route. The service names adapters the way Xray does. */
    val outboundAdapter: String = "",
    val resolveServerAddress: Boolean = true,
    val resolveDohUrl: String = "",
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
    val fragment: FragmentSpec? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val group: OutboundGroup? = null,
    val options: TunnelOptions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val probe: ProbeSpec? = null,
    /** The active server's country, read off the flag in its name. Carries the timezone the
     *  service applies in [TunnelOptions.matchLocation] mode. Top level rather than inside
     *  [options] so a hot switch to a server in another country does not restart the worker. */
    val serverCountry: String = "",
)

@Serializable data class OutboundGroup(
    val members: List<JsonObject>,
    val strategy: String,
    val maxDelayMillis: Int,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val memberFragments: Map<String, FragmentSpec> = emptyMap(),
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
    val probeResults: List<ProbeResultItem>? = null,
    val logs: String? = null,
) {
    fun probe() = if (state == "connected" && probePort > 0) ProbeEndpoint(probePort, probeUser, probePassword) else null
}

@Serializable
data class ConnectReconnectKey(
    val options: TunnelOptions,
    val tlsFingerprint: TlsFingerprintOverride,
    val fragmentMode: FragmentMode,
    val fragmentScope: FragmentScope,
    val fragmentLength: String,
    val fragmentInterval: String,
    val fragmentMaxChunks: String,
)

object ConnectRequestFactory {
    fun create(
        node: ProxyNode,
        settings: AppSettings,
        desktop: DesktopSettings,
        providerProfile: ProviderProfile? = null,
        dnsOverride: String? = null,
    ): ServiceRequest {
        require(node.settings !is ProxySettings.Direct) { "Direct node cannot be used as a proxy outbound" }
        val carried = requireNotNull(XrayConfigFactory.carriable(node)) {
            val reason = XrayConfigFactory.unsupported(node) ?: node.protocol.label
            "Unsupported node: $reason"
        }
        require(carried.dialed().none { it.settings is ProxySettings.Direct }) {
            "Direct node cannot be used as a proxy outbound"
        }
        val dialed = carried.dialed()
        val members = dialed.map { XrayConfigFactory.buildDesktopOutbound(it, settings) }
        val outbound = members.first()
        val primaryFragment = XrayConfigFactory.effectiveFragment(dialed.first(), settings)?.toSpec()
        val memberFragments = dialed.mapNotNull { member ->
            XrayConfigFactory.effectiveFragment(member, settings)
                ?.let { XrayConfigFactory.nodeTag(member.id) to it.toSpec() }
        }.toMap()
        return ServiceRequest(
            command = "connect",
            nodeId = node.id,
            outbound = outbound,
            fragment = primaryFragment,
            group = carried.group?.let {
                OutboundGroup(
                    members = members,
                    strategy = it.strategy,
                    maxDelayMillis = it.maxDelayMillis,
                    memberFragments = memberFragments,
                )
            },
            serverCountry = WorldMap.countryCodeOf(node.name) ?: "",
            options = options(settings, desktop, providerProfile, dnsOverride),
        )
    }

    fun options(
        settings: AppSettings,
        desktop: DesktopSettings,
        providerProfile: ProviderProfile? = null,
        dnsOverride: String? = null,
    ): TunnelOptions {
        val remoteDns = dnsOverride?.trim()?.takeIf { it.isNotEmpty() } ?: settings.remoteDns
        val directDns = XrayConfigFactory.xrayResolver(settings.directDns).orEmpty()
        val customRules = settings.customRules.takeUnless { it.isEmpty }?.let {
            CustomRulesSpec(proxy = it.proxy, direct = it.direct, block = it.block)
        }
        val appliedProvider = if (settings.providerRouting) providerProfile else null
        return TunnelOptions(
            mtu = settings.mtu.coerceIn(1280, 9000),
            ipv6 = settings.enableIpv6,
            bypassLan = settings.bypassLan,
            dns = remoteDns,
            directDns = directDns,
            hijackDns = settings.hijackDns,
            splitMode = desktop.splitMode,
            applications = if (desktop.applicationsEnabled) desktop.applications.map(RuleValidation::application).distinct() else emptyList(),
            domains = if (desktop.sitesEnabled) desktop.domains.map(RuleValidation::domain) else emptyList(),
            networks = if (desktop.sitesEnabled) desktop.networks.map(RuleValidation::network) else emptyList(),
            killSwitch = desktop.killSwitch,
            matchLocation = desktop.matchServerLocation,
            bypassRussia = desktop.bypassRussia,
            blockQuic = settings.blockQuic,
            blockAds = settings.blockAds,
            customRules = customRules,
            providerProfile = appliedProvider?.toSpec(),
            outboundAdapter = desktop.outboundAdapter,
            resolveServerAddress = settings.resolveServerAddress,
            resolveDohUrl = settings.resolveDohUrl,
        )
    }

    fun reconnectKey(
        settings: AppSettings,
        desktop: DesktopSettings,
        dnsOverride: String? = null,
        providerProfile: ProviderProfile? = null,
    ): ConnectReconnectKey = ConnectReconnectKey(
        options = options(settings, desktop, providerProfile, dnsOverride),
        tlsFingerprint = settings.tlsFingerprint,
        fragmentMode = settings.fragmentMode,
        fragmentScope = settings.fragmentScope,
        fragmentLength = settings.fragmentLength,
        fragmentInterval = settings.fragmentInterval,
        fragmentMaxChunks = settings.fragmentMaxChunks,
    )

    fun buildProbeSpec(
        nodes: List<ProxyNode>,
        settings: AppSettings,
        desktop: DesktopSettings,
        timeoutMillis: Int,
        concurrency: Int = 8,
    ): ProbeSpec {
        val usable = nodes.filter { XrayConfigFactory.unsupported(it) == null }.distinctBy { it.id }
        val targets = usable.map { node ->
            ProbeTargetSpec(
                id = node.id,
                outbound = XrayConfigFactory.buildDesktopOutbound(node, settings),
                fragment = XrayConfigFactory.effectiveFragment(node, settings)?.toSpec(),
            )
        }
        val probeUrl = settings.probeUrl.trim().takeIf { ProbeTargets.isValidProbeUrl(it) }
            ?: ProbeTargets.DEFAULT_URL
        return ProbeSpec(
            targets = targets,
            probeUrl = probeUrl,
            timeoutMillis = timeoutMillis.coerceIn(AppSettings.MIN_PROBE_TIMEOUT_MILLIS, AppSettings.MAX_PROBE_TIMEOUT_MILLIS),
            concurrency = concurrency.coerceIn(1, 16),
            outboundAdapter = desktop.outboundAdapter,
            resolveServerAddress = settings.resolveServerAddress,
            resolveDohUrl = settings.resolveDohUrl,
        )
    }

    private fun FragmentOptions.toSpec(): FragmentSpec = FragmentSpec(
        packets = packets,
        length = length,
        interval = interval,
        maxSplit = maxSplit,
    )

    private fun ProviderProfile.toSpec(): ProviderProfileSpec = ProviderProfileSpec(
        rules = rules.map { rule ->
            ProviderRuleSpec(
                match = rule.match,
                target = rule.target.name.lowercase(),
            )
        },
        dns = dns?.let { d ->
            ProviderDnsSpec(
                servers = d.servers,
                queryStrategy = d.queryStrategy,
            )
        },
    )
}
