package com.yumi.desktop.model

import org.junit.Assert.*
import org.junit.Test

class RuleValidationTest {
    @Test fun normalizesDomainsAndApplications() {
        assertEquals("example.com",RuleValidation.domain("*.Example.COM."))
        assertEquals("2001:db8::/32",RuleValidation.network("2001:db8::/32"))
    }
    @Test fun applicationsAreProcessNames() {
        // Older settings held whole paths; they come back as the name the core matches on.
        assertEquals("app.exe",RuleValidation.application("C:\\Program Files\\App\\app.exe"))
        assertEquals("app.exe",RuleValidation.application("C:/Program Files/App/app.exe"))
        assertEquals("Telegram.exe",RuleValidation.application("Telegram.exe"))
        assertEquals("telegram.exe",RuleValidation.application("  telegram "))
        assertEquals("Discord.exe",RuleValidation.application("\"C:\\Users\\me\\AppData\\Local\\Discord\\app-1.0.9\\Discord.exe\""))
    }
    @Test fun rejectsInvalidRules() {
        for(value in listOf("https://example.com/path","-bad.example","example..com")) assertThrows(Exception::class.java){RuleValidation.domain(value)}
        for(value in listOf("192.0.2.1/33","2001:db8::/129","example.com")) assertThrows(Exception::class.java){RuleValidation.network(value)}
        for(value in listOf("","   ","..","app?.exe","C:\\Apps\\","a\nb.exe")) assertThrows(Exception::class.java){RuleValidation.application(value)}
    }
    @Test fun requestCarriesKillSwitchAndDatabases() {
        val options = TunnelOptions(killSwitch = true, bypassRussia = false, applications = listOf("chrome.exe"))
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(TunnelOptions.serializer(), options)
        assertTrue("\"killSwitch\":true" in json)
        assertTrue("\"bypassRussia\":false" in json)
    }
}
