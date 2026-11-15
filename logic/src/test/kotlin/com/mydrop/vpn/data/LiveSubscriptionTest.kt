package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.*
import com.yumi.desktop.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in compatibility check. Reads an existing profile without modifying it or its tunnel.
 * CI has no credentials and skips this test. Output contains counts; the request stays local. */
class LiveSubscriptionTest {
    @Test fun configuredSubscriptionImportsGroupsAndBuildsACompleteServiceRequest() = runBlocking {
        val source = System.getenv("YUMI_VERIFY_PROFILE_DIRECTORY")
        assumeTrue("Requires an explicitly supplied local profile", source != null)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val profiles = json.decodeFromString(ProfileState.serializer(), File(source, "profiles.json").readText())
        val settings = json.decodeFromString(AppSettings.serializer(), File(source, "settings.json").readText())
        val subscription = profiles.subscriptions.first { it.enabled }
        val service = SubscriptionService(LogRepository(), Strings(), identity = {
            DeviceIdentity(settings.deviceId, System.getProperty("os.name"), System.getProperty("os.version"), "Yumi Desktop")
        })
        val update = service.fetch(subscription)
        assertTrue("Subscription fetch did not succeed; private connection details are omitted", update is SubscriptionUpdate.Success)
        val nodes = (update as SubscriptionUpdate.Success).nodes
        val groups = nodes.filter { it.group != null }
        assertTrue("The subscription returned no multi-server documents", groups.isNotEmpty())
        val chosen = groups.first()
        val request = ConnectRequestFactory.create(chosen, settings, DesktopSettings())
        assertEquals(chosen.group!!.members.size, request.group!!.members.size)
        val ping = LatencyTester().measure(chosen, PingMode.Tcp)
        val output = File(requireNotNull(System.getenv("YUMI_VERIFY_OUTPUT"))).also { it.mkdirs() }
        File(output, "connect.json").writeText(json.encodeToString(request))
        File(output, "groups.json").writeText(json.encodeToString(groups.map { ConnectRequestFactory.create(it, settings, DesktopSettings()) }))
        val results = LatencyTester().measureAll(groups, PingMode.Tcp)
        assertEquals(groups.map { it.id }.toSet(), results.map { it.nodeId }.toSet())
        File(output, "summary.json").writeText("""{"nodes":${nodes.size},"groups":${groups.size},"totalMembers":${groups.sumOf { it.group!!.members.size }},"reachableGroups":${results.count { !it.failed }},"members":${request.group!!.members.size},"endpointPingSucceeded":${!ping.failed}}""")
        assertFalse("No group member answered an endpoint probe on this network", ping.failed)
    }
}
