package com.yumi.desktop

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The range that tells one failure apart from the other two.
 *
 * A name answered from 198.18.0.0/15 was answered by a fake-ip client — mihomo, sing-box and
 * everything built on them hand out addresses from RFC 2544's benchmarking space precisely because
 * nothing real lives there. Widening this by a byte would accuse an innocent host; narrowing it to
 * the /16 everyone quotes would miss half of the pool, since the default range spans both.
 *
 * Literals only: `getByName` on a dotted quad parses it and asks nothing of a resolver, which is
 * what keeps this a test of the predicate rather than of the network under it.
 */
class TunnelCheckTest {

    private fun at(literal: String) = listOf(InetAddress.getByName(literal))

    @Test fun `both halves of the pool are recognised`() {
        assertTrue(TunnelCheck.answeredFromBenchmarkSpace(at("198.18.0.1")))
        assertTrue(TunnelCheck.answeredFromBenchmarkSpace(at("198.18.0.23")))
        assertTrue(TunnelCheck.answeredFromBenchmarkSpace(at("198.19.255.255")))
    }

    @Test fun `its neighbours are not`() {
        for (literal in listOf("198.17.255.255", "198.20.0.1", "198.51.100.7", "93.184.216.34", "1.1.1.1")) {
            assertFalse(TunnelCheck.answeredFromBenchmarkSpace(at(literal)), literal)
        }
    }

    @Test fun `one fake address among real ones still counts`() {
        val mixed = listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("198.18.0.9"))
        assertTrue(TunnelCheck.answeredFromBenchmarkSpace(mixed))
    }

    @Test fun `a v6 answer is not read as though it were four bytes`() {
        assertFalse(TunnelCheck.answeredFromBenchmarkSpace(at("2001:db8::c612:1")))
    }

    /** A resolver that answered with nothing has not answered from anywhere. */
    @Test fun `no addresses is not an accusation`() {
        assertFalse(TunnelCheck.answeredFromBenchmarkSpace(emptyList()))
    }
}
