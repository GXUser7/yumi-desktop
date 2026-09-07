package com.yumi.desktop.model

import org.junit.Assert.*
import org.junit.Test

class RuleValidationTest {
    @Test fun normalizesDomainsAndApplications() {
        assertEquals("example.com",RuleValidation.domain("*.Example.COM."))
        assertEquals("C:/Program Files/App/app.exe",RuleValidation.application("C:\\Program Files\\App\\app.exe"))
        assertEquals("2001:db8::/32",RuleValidation.network("2001:db8::/32"))
    }
    @Test fun rejectsInvalidRules() {
        for(value in listOf("https://example.com/path","-bad.example","example..com")) assertThrows(Exception::class.java){RuleValidation.domain(value)}
        for(value in listOf("192.0.2.1/33","2001:db8::/129","example.com")) assertThrows(Exception::class.java){RuleValidation.network(value)}
        assertThrows(Exception::class.java){RuleValidation.application("app.exe")}
    }
}
