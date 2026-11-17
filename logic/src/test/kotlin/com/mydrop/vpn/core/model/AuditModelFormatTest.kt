package com.mydrop.vpn.core.model

import com.mydrop.vpn.core.format.daysUntil
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditModelFormatTest {

    @Test
    fun `daysUntil avoids Long multiplication overflow on distant expiry timestamps`() {
        val nowMillis = 1_700_000_000_000L
        val distant = Long.MAX_VALUE / 100
        val days = daysUntil(distant, nowMillis)
        assertNotNull(days)
        assertTrue("Expected huge positive days without overflow, got $days", days!! > 1_000_000L)

        val maxDays = daysUntil(Long.MAX_VALUE, nowMillis)
        assertTrue(maxDays!! > 1_000_000L)
    }

    @Test
    fun `SubscriptionUserInfo usedBytes clamps instead of overflowing Long`() {
        val info = SubscriptionUserInfo(
            uploadBytes = Long.MAX_VALUE - 10L,
            downloadBytes = 100L,
            totalBytes = Long.MAX_VALUE,
        )
        assertEquals(Long.MAX_VALUE, info.usedBytes)
    }

    @Test
    fun `CoreChatter and CoreGenerations remain thread-safe under concurrent calls`() {
        val chatter = CoreChatter()
        val generations = CoreGenerations()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map { worker ->
                pool.submit {
                    val base = 1_700_000_000_000L
                    repeat(500) { i ->
                        val t = base + i * 10L
                        chatter.admit(t)
                        generations.observe("INFO[${100 + worker * 50}] msg", t)
                        if (i % 50 == 0) {
                            generations.liveCores(t)
                            generations.dueReport(t)
                        }
                    }
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
