package com.yumi.desktop.model

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.LogLevel
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop's log level used to stop at this boundary.
 *
 * The request had no such field, so the service built every tunnel with `loglevel: none` and the
 * one component that knows why a tunnel failed was the one forbidden from saying so — while the
 * settings screen went on offering a choice that changed nothing on this platform.
 *
 * The service's vocabulary is written out again below on purpose. It refuses anything outside it
 * as `invalid_log_level`, and a level this side invents would not be a wrong log — it would be a
 * connection that never starts.
 */
class ConnectRequestTest {

    private val node = ProxyNode(
        id = "fixture",
        name = "fixture",
        server = "example.com",
        port = 443,
        settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
    )

    private fun requestWith(level: LogLevel) =
        ConnectRequestFactory.create(node, AppSettings(mtu = 1500, logLevel = level), DesktopSettings())

    @Test fun `the chosen level travels with the request`() {
        val expected = mapOf(
            LogLevel.Trace to "debug",
            LogLevel.Debug to "debug",
            LogLevel.Info to "info",
            LogLevel.Warn to "warning",
            LogLevel.Error to "error",
        )
        for ((chosen, spelling) in expected) assertEquals(spelling, requestWith(chosen).options?.logLevel)
    }

    @Test fun `every level the app can choose is one the service accepts`() {
        val vocabulary = setOf("none", "error", "warning", "info", "debug")
        for (level in LogLevel.entries) {
            val carried = requestWith(level).options?.logLevel
            assertTrue("$level became $carried, which the service refuses", carried in vocabulary)
        }
    }
}
