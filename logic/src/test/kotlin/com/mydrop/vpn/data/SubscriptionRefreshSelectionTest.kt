package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.identified
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a routine subscription refresh may and may not do to the server the user is on.
 *
 * Written from field journals. A working tunnel on Belgium survived an auto-refresh by being
 * moved to France, which was dead — twenty seconds of no internet, from a background task the
 * user never asked for and could not see. The cause was the fallback: a node id is a hash of the
 * endpoint and the credentials, so a provider rotating an address, a port or a key hands the same
 * server back under a different id, the old one looks deleted, and the selection fell to whatever
 * happened to be first in the list. A second report came from the other side: an empty answer
 * blanked the selection outright, and the screen said no server was chosen while the tunnel kept
 * carrying the one it had.
 *
 * The contract now: a refresh never moves the selection and never blanks it. The id, then the
 * name, then the same row kept in place — the user decides what comes next, nothing else.
 */
class SubscriptionRefreshSelectionTest {

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun repository() = ProfileRepository(folder.newFolder(), scope)

    private fun node(name: String, server: String, uuid: String, subscription: String) = ProxyNode(
        id = "",
        name = name,
        server = server,
        port = 443,
        settings = ProxySettings.Vless(uuid),
        subscriptionId = subscription,
    ).identified()

    private val sub = "sub-1"

    private fun ProfileRepository.seed(vararg nodes: ProxyNode) {
        addSubscription(Subscription(id = sub, name = "мой", url = "https://example.com/s"))
        applySubscriptionUpdate(sub, nodes.toList(), null, null, null)
    }

    /**
     * The case that caused the outage. The provider rotates the credential, so every id moves, and
     * the server the user chose is still there under a new one.
     */
    @Test
    fun `a rotated credential does not move the user to another country`() {
        val repo = repository()
        val belgium = node("Бельгия", "be.example.com", "uuid-1", sub)
        val france = node("Франция", "fr.example.com", "uuid-2", sub)
        // France first in the list, exactly as the fallback would have picked it.
        repo.seed(france, belgium)
        repo.selectNode(belgium.id)

        val rotated = listOf(
            node("Франция", "fr.example.com", "uuid-9", sub),
            node("Бельгия", "be.example.com", "uuid-8", sub),
        )
        repo.applySubscriptionUpdate(sub, rotated, null, null, null)

        val selected = repo.state.value.nodes.first { it.id == repo.state.value.selectedNodeId }
        assertEquals("Бельгия", selected.name)
    }

    /** The ordinary refresh, where nothing moved: the selection must not so much as flicker. */
    @Test
    fun `an unchanged refresh leaves the selection alone`() {
        val repo = repository()
        val belgium = node("Бельгия", "be.example.com", "uuid-1", sub)
        val france = node("Франция", "fr.example.com", "uuid-2", sub)
        repo.seed(france, belgium)
        repo.selectNode(belgium.id)

        repo.applySubscriptionUpdate(sub, listOf(france, belgium), null, null, null)

        assertEquals(belgium.id, repo.state.value.selectedNodeId)
    }

    /**
     * A server the provider stopped listing does not take the selection with it. The tunnel may
     * still be carrying that row, and a screen that says "no server selected" — or quietly sits
     * on the first row instead — is a lie about where the traffic goes. The old row stays, still
     * selected, until the user picks something else.
     */
    @Test
    fun `a refresh that drops the selected server keeps its row and the selection`() {
        val repo = repository()
        val belgium = node("Бельгия", "be.example.com", "uuid-1", sub)
        val france = node("Франция", "fr.example.com", "uuid-2", sub)
        repo.seed(belgium, france)
        repo.selectNode(belgium.id)

        repo.applySubscriptionUpdate(sub, listOf(france), null, null, null)

        assertEquals(belgium.id, repo.state.value.selectedNodeId)
        assertEquals(belgium, repo.state.value.nodes.firstOrNull { it.id == belgium.id })
        assertEquals(belgium, repo.selectedNode())
    }

    /**
     * An empty answer — an expired quota, a rate-limit page that parses to nothing — used to
     * blank the selection outright, which is the field report that got this file rewritten: the
     * tunnel kept carrying the old server while the screen said none was chosen.
     */
    @Test
    fun `an empty refresh cannot blank the selection`() {
        val repo = repository()
        val belgium = node("Бельгия", "be.example.com", "uuid-1", sub)
        val france = node("Франция", "fr.example.com", "uuid-2", sub)
        repo.seed(belgium, france)
        repo.selectNode(belgium.id)

        repo.applySubscriptionUpdate(sub, emptyList(), null, null, null)

        assertEquals(belgium.id, repo.state.value.selectedNodeId)
        assertEquals(listOf(belgium), repo.state.value.nodes)
        assertEquals(belgium, repo.selectedNode())
    }

    /**
     * The pin is the user's to release, not the refresh's: after they pick another server, the
     * next refresh — which no longer has anything to hold on — lets the kept row go.
     */
    @Test
    fun `a kept row leaves once the user picks another server`() {
        val repo = repository()
        val belgium = node("Бельгия", "be.example.com", "uuid-1", sub)
        val france = node("Франция", "fr.example.com", "uuid-2", sub)
        repo.seed(belgium, france)
        repo.selectNode(belgium.id)
        repo.applySubscriptionUpdate(sub, listOf(france), null, null, null)
        assertEquals(belgium.id, repo.state.value.selectedNodeId)

        repo.selectNode(france.id)
        repo.applySubscriptionUpdate(sub, listOf(france), null, null, null)

        assertEquals(france.id, repo.state.value.selectedNodeId)
        assertEquals(listOf(france), repo.state.value.nodes)
    }

    /**
     * The name is a weaker key than the id, so it is scoped to one subscription — and the scoping
     * has to be the thing that decides, not a coincidence of ordering.
     *
     * Arranged so the two candidates differ. Another provider sells a server under the same label
     * and, being outside the subscription being refreshed, sits first in the combined list — which
     * is exactly what the fallback would take. The user's own server comes back under a new id, and
     * that is what must win.
     */
    @Test
    fun `the name match prefers our own subscription over an identical label elsewhere`() {
        val repo = repository()
        val theirs = node("Бельгия", "be.other.com", "uuid-9", "sub-2")
        repo.addSubscription(Subscription(id = "sub-2", name = "чужой", url = "https://other.example/s"))
        repo.applySubscriptionUpdate("sub-2", listOf(theirs), null, null, null)

        val mine = node("Бельгия", "be.example.com", "uuid-1", sub)
        repo.seed(mine)
        repo.selectNode(mine.id)

        val rotated = node("Бельгия", "be.example.com", "uuid-8", sub)
        repo.applySubscriptionUpdate(sub, listOf(rotated), null, null, null)

        assertEquals(
            "the choice must stay with the user's own provider",
            rotated.id,
            repo.state.value.selectedNodeId,
        )
    }

    @Test
    fun `selectedNode hides nodes belonging to a disabled subscription`() {
        val repo = repository()
        val mine = node("Бельгия", "be.example.com", "uuid-1", sub)
        repo.seed(mine)
        repo.selectNode(mine.id)
        assertEquals(mine, repo.selectedNode())

        repo.setSubscriptionEnabled(sub, false)
        assertNull(repo.selectedNode())

        repo.setSubscriptionEnabled(sub, true)
        assertEquals(mine, repo.selectedNode())
    }

    @Test
    fun `recordLatencies map overload records successes and failures atomically`() {
        val repo = repository()
        val a = node("A", "a.example.com", "u1", sub)
        val b = node("B", "b.example.com", "u2", sub)
        repo.seed(a, b)

        repo.recordLatencies(mapOf(a.id to 42L, b.id to null))
        val latencies = repo.state.value.latencies
        assertEquals(42, latencies[a.id]!!.millis)
        assertFalse(latencies[a.id]!!.failed)
        assertTrue(latencies[b.id]!!.failed)
    }
}
