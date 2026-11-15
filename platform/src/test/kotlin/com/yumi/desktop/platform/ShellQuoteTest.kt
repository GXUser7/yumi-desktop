package com.yumi.desktop.platform

import org.junit.Test
import org.junit.Assert.assertEquals

class ShellQuoteTest {
    @Test fun pathsStayLiteralIncludingSpacesQuotesDollarSignsAndBackticks() {
        assertEquals("'/Applications/My Yumi.app/service'", DesktopPlatform.shellQuote("/Applications/My Yumi.app/service"))
        assertEquals("'a'\\''b\$HOME`command`'", DesktopPlatform.shellQuote("a'b\$HOME`command`"))
    }
}
