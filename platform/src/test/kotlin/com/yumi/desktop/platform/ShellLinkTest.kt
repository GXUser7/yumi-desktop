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

    @Test fun rejectsTruncatedAndOverflowingOffsetsWithoutThrowing() {
        fun tempLnk(bytes: ByteArray): File =
            File.createTempFile("yumi-malformed", ".lnk").apply { writeBytes(bytes); deleteOnExit() }

        // HAS_ID_LIST set on an exact 0x4C-byte header (truncated IDList length).
        val truncatedIdList = ByteBuffer.allocate(0x4C).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x4C)
            putInt(0x14, 0x1 or 0x2)
        }.array()
        assertNull(ShellLink.target(tempLnk(truncatedIdList)))

        // Malformed LinkInfo with signed Int overflow in LocalBasePathOffset / LocalBasePathOffsetUnicode.
        for (badOffset in listOf(Int.MAX_VALUE, Int.MIN_VALUE, -16, 0x08)) {
            val buf = ByteBuffer.allocate(0x4C + 0x40).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0, 0x4C)
            buf.putInt(0x14, 0x2)
            val info = 0x4C
            buf.putInt(info, 0x40)
            buf.putInt(info + 4, 0x24)
            buf.putInt(info + 8, 1)
            buf.putInt(info + 0x10, badOffset)
            buf.putInt(info + 0x1C, badOffset)
            assertNull("Expected null for offset=$badOffset", ShellLink.target(tempLnk(buf.array())))
        }

        // LinkInfoSize exceeding remaining file bytes or overflowing signed Int.
        for (badSize in listOf(0x100, Int.MAX_VALUE, -1)) {
            val buf = ByteBuffer.allocate(0x4C + 0x24).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0, 0x4C)
            buf.putInt(0x14, 0x2)
            val info = 0x4C
            buf.putInt(info, badSize)
            buf.putInt(info + 4, 0x1C)
            buf.putInt(info + 8, 1)
            buf.putInt(info + 0x10, 0x1C)
            assertNull("Expected null for LinkInfoSize=$badSize", ShellLink.target(tempLnk(buf.array())))
        }

        // HeaderSize >= 0x24 when LinkInfoSize < 0x24 must not read past LinkInfo.
        val shortLinkInfo = ByteBuffer.allocate(0x4C + 0x20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x4C)
            putInt(0x14, 0x2)
            putInt(0x4C, 0x20)
            putInt(0x4C + 4, 0x24)
            putInt(0x4C + 8, 1)
        }.array()
        assertNull(ShellLink.target(tempLnk(shortLinkInfo)))
    }

    @Test fun fallsBackToAnsiWhenUnicodeOffsetIsZeroInExtendedHeader() {
        val path = "C:\\Tools\\app.exe"
        val ansi = (path + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val infoHeader = 0x24
        val info = ByteBuffer.allocate(infoHeader + ansi.size).order(ByteOrder.LITTLE_ENDIAN)
        info.putInt(0, infoHeader + ansi.size)
        info.putInt(4, infoHeader)
        info.putInt(8, 1)
        info.putInt(0x10, infoHeader)
        info.putInt(0x1C, 0)
        info.position(infoHeader)
        info.put(ansi)

        val header = ByteBuffer.allocate(0x4C).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, 0x4C)
        header.putInt(0x14, 0x2)

        val file = File.createTempFile("yumi-fallback", ".lnk").apply {
            writeBytes(header.array() + info.array())
            deleteOnExit()
        }
        assertEquals(path, ShellLink.target(file))
    }
}
