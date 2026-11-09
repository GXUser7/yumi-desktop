package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.JsonStore
import com.mydrop.vpn.data.ProfileRepository
import com.mydrop.vpn.data.SettingsRepository
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.TunnelBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/*
 * `--ui-demo`: the window against a service that pretends.
 *
 * `--ui-smoke` proves every screen draws against a service that can never connect. This is the
 * other half — what the screens look like with a subscription, a tunnel up and traffic moving — for
 * the screenshots in the README and for looking at a design change without a server or Windows.
 * Nothing here touches the network: the servers point at documentation addresses, and the
 * "service" counts bytes it makes up.
 */

class DemoBackend : TunnelBackend {
    private var state = "disconnected"
    private var nodeId: String? = null
    private var connectedAt = 0L
    private var since = 0L

    override suspend fun request(request: ServiceRequest): ServiceStatus {
        val now = System.currentTimeMillis()
        when (request.command) {
            "connect" -> { state = "connecting"; nodeId = request.nodeId; since = now }
            "disconnect" -> { state = "disconnected"; nodeId = null }
        }
        if (state == "connecting" && now - since > 900) { state = "connected"; connectedAt = now - 754_000 }
        val seconds = if (state == "connected") (now - connectedAt) / 1000.0 else 0.0
        // A browsing-shaped load: a steady trickle with a slow swell in it.
        val down = if (state == "connected") (seconds * 1_450_000 + kotlin.math.sin(seconds / 3) * 2_400_000).toLong().coerceAtLeast(0) else 0
        val up = if (state == "connected") (seconds * 210_000 + kotlin.math.cos(seconds / 2) * 300_000).toLong().coerceAtLeast(0) else 0
        return ServiceStatus(
            state = state,
            nodeId = nodeId,
            connectedAt = connectedAt,
            upload = up,
            download = down,
            geoReady = true,
            geoBytes = 23_800_000,
            geoUpdatedAt = now - 3 * 3_600_000,
        )
    }
}

/** A subscription's worth of made-up servers, written where the model will read them. */
fun seedDemo(directory: File, nodeCount: Int = 6) {
    directory.listFiles()?.forEach { it.deleteRecursively() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val settings = SettingsRepository(directory, scope)
        settings.update {
            it.copy(deviceId = "demo", mtu = 1500, routingMode = RoutingMode.Global, autoFailover = false, subscriptionAutoUpdate = false, visualizer = Visualizer.Planet, language = AppLanguage.Russian)
        }
        val desktop = JsonStore(File(directory, "desktop.json"), DesktopSettings.serializer(), DesktopSettings(), scope)
        desktop.update { it.copy(applications = listOf("Telegram.exe", "chrome.exe", "Discord.exe"), domains = listOf("example.ru"), killSwitch = true) }

        val profiles = ProfileRepository(directory, scope)
        val subscription = profiles.upsertSubscriptionSource("Yumi Cloud", "https://subscription.example/demo", null, emptyMap())
        val servers = listOf(
            "🇩🇪 Германия · Франкфурт" to ("198.51.100.11" to Protocol.VLESS),
            "🇳🇱 Нидерланды · Амстердам" to ("198.51.100.12" to Protocol.VLESS),
            "🇫🇮 Финляндия · Хельсинки ⚡" to ("198.51.100.13" to Protocol.HYSTERIA2),
            "🇹🇷 Турция · Стамбул" to ("198.51.100.14" to Protocol.TROJAN),
            "🇺🇸 США · Нью-Йорк" to ("198.51.100.15" to Protocol.VLESS),
            "🇯🇵 Япония · Токио ⭐" to ("198.51.100.16" to Protocol.SHADOWSOCKS),
        ).map { (name, endpoint) ->
            val (host, protocol) = endpoint
            val settingsFor = when (protocol) {
                Protocol.HYSTERIA2 -> ProxySettings.Hysteria2(password = "demo")
                Protocol.TROJAN -> ProxySettings.Trojan(password = "demo")
                Protocol.SHADOWSOCKS -> ProxySettings.Shadowsocks(method = "aes-256-gcm", password = "demo")
                else -> ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555", flow = "xtls-rprx-vision")
            }
            ProxyNode(
                id = "",
                name = name,
                server = host,
                port = 443,
                settings = settingsFor,
                tls = TlsOptions(serverName = "www.example.com", reality = if (protocol == Protocol.VLESS) RealityOptions(publicKey = "demo", shortId = "0a") else null),
                subscriptionId = subscription.id,
            ).identified()
        }
        val many = List(nodeCount) { index ->
            val node = servers[index % servers.size]
            if (index < servers.size) node else node.copy(
                id = "", name = "${node.name} · ${index + 1}", server = "192.0.${index / 250}.${index % 250 + 1}",
            ).identified()
        }
        profiles.applySubscriptionUpdate(
            subscription.id,
            many,
            SubscriptionUserInfo(uploadBytes = 4_100_000_000, downloadBytes = 61_300_000_000, totalBytes = 200_000_000_000, expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 23 * 86_400),
            "Yumi Cloud",
            null,
        )
        profiles.selectNode(servers.first().id)
        val now = System.currentTimeMillis()
        val latencies = listOf(38, 46, 61, 97, 142, 214)
        profiles.recordLatencies(many.mapIndexed { index, node -> LatencyResult(node.id, latencies[index % latencies.size], now) })
    } finally {
        scope.cancel()
    }
}
