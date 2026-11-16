package com.mydrop.vpn.core.xray

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.data.ProfileState
import com.yumi.desktop.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Opt-in: only reads the local profile. Private requests stay in the supplied ignored directory. */
class LiveTransportExportTest {
    @Test fun exportCountryNodesAndOneExampleOfEachSupportedTransport() {
        val source = System.getenv("YUMI_VERIFY_PROFILE_DIRECTORY")
        val output = System.getenv("YUMI_VERIFY_TRANSPORT_OUTPUT")
        assumeTrue("Requires an explicitly supplied local profile and output", source != null && output != null)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val state = json.decodeFromString<ProfileState>(File(source, "profiles.json").readText())
        val country = System.getenv("YUMI_VERIFY_COUNTRY") ?: "KZ"
        val nodes = state.visibleNodes.filter { it.group == null && XrayConfigFactory.unsupported(it) == null }
        val chosen = (nodes.filter { WorldMap.countryCodeOf(it.name) == country } +
            nodes.distinctBy { it.protocol to it.transport?.label }).distinctBy { it.id }
        assertTrue(chosen.isNotEmpty())
        val dir = File(requireNotNull(output)).also { it.mkdirs() }
        val manifest = buildJsonArray {
            chosen.forEachIndexed { index, node ->
                val file = "request-$index.json"
                File(dir, file).writeText(json.encodeToString(ConnectRequestFactory.create(node, AppSettings(mtu = 1500), DesktopSettings())))
                add(buildJsonObject {
                    put("file", file); put("name", node.name)
                    put("transport", node.transport?.label ?: when {
                        node.settings is ProxySettings.Hysteria2 -> "Hysteria2/UDP"
                        node.tls?.reality != null -> "TCP/REALITY"
                        else -> "TCP"
                    })
                    put("country", WorldMap.countryCodeOf(node.name).orEmpty())
                })
            }
        }
        File(dir, "manifest.json").writeText(manifest.toString())
    }
}
