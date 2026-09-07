package com.mydrop.vpn.data

import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.*
import org.junit.Test

class DesktopStoreTest {
    @Test fun successfulWriteIsImmediatelyDurable() {
        val directory=Files.createTempDirectory("yumi-store").toFile()
        val file=File(directory,"settings.json")
        val store=JsonStore(file,String.serializer(),"default",TestScope())
        store.update{"saved"}
        assertEquals("saved",JsonStore(file,String.serializer(),"default",TestScope()).value)
        file.delete();directory.delete()
    }
    @Test fun failedWriteDoesNotPublishUnsavedState() {
        val directory=Files.createTempDirectory("yumi-write-failure").toFile()
        val blocker=File(directory,"not-a-directory").apply{writeText("fixture")}
        val store=JsonStore(File(blocker,"settings.json"),String.serializer(),"old",TestScope())
        assertThrows(Exception::class.java){store.update{"new"}}
        assertEquals("old",store.value)
        blocker.delete();directory.delete()
    }
}
