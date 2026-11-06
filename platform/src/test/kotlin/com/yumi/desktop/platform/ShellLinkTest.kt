package com.yumi.desktop.platform

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellLinkTest {
    /** A shortcut cut down to what [ShellLink] reads: the header, an ID list and the link info. */
    private fun shortcut(path: String, unicode: Boolean): File {
        val idList = ByteArray(6)
        val text = if (unicode) (path + "\u0000").toByteArray(Charsets.UTF_16LE) else (path + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val infoHeader = if (unicode) 0x24 else 0x1C
        val info = ByteBuffer.allocate(infoHeader + text.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        info.putInt(0, infoHeader + text.size + 2)
        info.putInt(4, infoHeader)
        info.putInt(8, 1)
        info.putInt(0x10, if (unicode) infoHeader + text.size else infoHeader)
        if (unicode) info.putInt(0x1C, infoHeader)
        info.position(infoHeader); info.put(text)
        val header = ByteBuffer.allocate(0x4C).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, 0x4C)
        header.putInt(0x14, 0x1 or 0x2)
        val file = File.createTempFile("yumi", ".lnk")
        file.writeBytes(header.array() + byteArrayOf(idList.size.toByte(), 0) + idList + info.array())
        file.deleteOnExit()
        return file
    }

    @Test fun readsTheLocalPath() {
        assertEquals("C:\\Program Files\\App\\app.exe", ShellLink.target(shortcut("C:\\Program Files\\App\\app.exe", unicode = false)))
        assertEquals("C:\\Программы\\Телеграм\\Telegram.exe", ShellLink.target(shortcut("C:\\Программы\\Телеграм\\Telegram.exe", unicode = true)))
    }

    @Test fun refusesWhatIsNotAShortcut() {
        val file = File.createTempFile("yumi", ".lnk").apply { writeBytes(ByteArray(200)); deleteOnExit() }
        assertNull(ShellLink.target(file))
    }

    @Test fun namesReadLikePrograms() {
        assertEquals("Telegram", AppCatalog.prettify("telegram.exe"))
        assertEquals("Obs64", AppCatalog.prettify("obs64.exe"))
        assertEquals("Steam Webhelper", AppCatalog.prettify("steam_webhelper.exe"))
    }
}
