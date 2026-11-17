package com.yumi.desktop.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.IntByReference
import com.yumi.desktop.model.ServiceRequest
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopPlatformSecurityTest {
    @Test fun rejectsMalformedExpectedSha256BeforeInstalling() {
        val pkg = File.createTempFile("yumi-upd", ".deb").apply { writeText("payload"); deleteOnExit() }
        assertFalse(DesktopPlatform.installUpdate(pkg, expectedSha256 = "not-a-sha256"))
        assertFalse(DesktopPlatform.installUpdate(pkg, expectedSha256 = "a".repeat(63)))
        assertFalse(DesktopPlatform.installUpdate(pkg, expectedSha256 = "g".repeat(64)))
        assertFalse(DesktopPlatform.installUpdate(File(pkg.parentFile, "missing-${UUID.randomUUID()}.deb"), expectedSha256 = ""))
    }

    @Test fun formatsWindowsUpdateParametersWithRelaunchFlagAndTrimmedDir() {
        val dir = File("C:\\Program Files\\Yumi Desktop\\")
        val withRestart = DesktopPlatform.windowsUpdateParameters(dir, relaunch = true, pid = 4242L)
        assertTrue(withRestart.contains("/SILENT /NORESTART /YUMIUPDATE=1 /YUMIRESTART=1 /YUMIPID=4242"))
        assertTrue(withRestart.endsWith("/DIR=\"${dir.absolutePath.trimEnd('\\')}\""))

        val withoutRestart = DesktopPlatform.windowsUpdateParameters(dir, relaunch = false, pid = 4242L)
        assertFalse(withoutRestart.contains("/YUMIRESTART=1"))
        assertTrue(withoutRestart.contains("/SILENT /NORESTART /YUMIUPDATE=1 /YUMIPID=4242"))
    }

    @Test fun buildsSafeUnixUpdateScriptsForLinuxAndMac() {
        val deb = File("/tmp/Yumi's update.deb")
        val digest = "A".repeat(64)
        val linuxScript = DesktopPlatform.buildUnixUpdateScript(
            file = deb,
            expectedSha256 = digest,
            pid = 999L,
            owner = "1000",
            currentServicePath = "/opt/yumi/service/yumi-service",
            macOs = false,
        ) { pkg -> "dpkg -i $pkg" }
        assertTrue(linuxScript.contains("stage=$(mktemp -d /tmp/yumi-update.XXXXXX)"))
        assertTrue(linuxScript.contains("chmod 700 \"\$stage\""))
        assertTrue(linuxScript.contains("sha256sum -c -"))
        assertTrue(linuxScript.contains("exit 2"))
        assertTrue(linuxScript.contains("trap 'rm -rf \"\$stage\"' EXIT"))
        assertTrue(linuxScript.contains("while kill -0 999 2>/dev/null; do sleep 1; done"))
        assertTrue(linuxScript.contains("dpkg -i \"\$stage/update.deb\""))
        assertTrue(linuxScript.contains("'/opt/yumi/service/yumi-service' --install --owner 1000"))
        assertTrue(linuxScript.endsWith(">/dev/null 2>&1 </dev/null & exit 0"))

        val pkg = File("/tmp/Yumi.pkg")
        val macScript = DesktopPlatform.buildUnixUpdateScript(
            file = pkg,
            expectedSha256 = digest,
            pid = 123L,
            owner = "501",
            currentServicePath = "/Users/alice/Yumi.app/Contents/Resources/service/yumi-service",
            macOs = true,
        ) { staged -> "installer -pkg $staged -target /" }
        assertTrue(macScript.contains("shasum -a 256 -c -"))
        assertTrue(macScript.contains("installer -pkg \"\$stage/update.pkg\" -target /"))
        assertTrue(macScript.contains("'/Applications/Yumi.app/Contents/Resources/service/yumi-service'"))
        assertTrue(macScript.contains("'/Users/alice/Yumi.app/Contents/Resources/service/yumi-service'"))
    }

    @Test fun escapesXmlAndDesktopAutostartEntries() {
        val path = "/opt/Yumi & <Friends>/\"bin\"/'yumi'\$HOME`id`\\exe"
        val plist = DesktopPlatform.macLaunchAgentPlist(path)
        assertTrue(plist.contains("&amp; &lt;Friends&gt;/&quot;bin&quot;/&apos;yumi&apos;"))
        assertFalse(plist.contains("<Friends>"))

        val desktop = DesktopPlatform.linuxAutostartEntry("/opt/yumi/a\"b\$HOME`id`\\app")
        assertTrue(desktop.contains("Exec=\"/opt/yumi/a\\\"b\\\$HOME\\`id\\`\\\\app\" --tray"))
    }

    @Test fun diagnosesMissingUnixSocketCleanly() {
        val missing = Files.createTempDirectory("yumi-missing-sock-").resolve("nope.sock")
        assertEquals("service_missing", DesktopPlatform.diagnoseUnixSocket(missing))
    }

    @Test fun windowsLockedUpdateFilePreventsWriteAndDeleteWhileHashing() {
        assumeTrue(DesktopPlatform.isWindows)
        val file = File.createTempFile("yumi-win-upd-", ".exe").apply {
            writeText("installer-binary-content")
            deleteOnExit()
        }
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
            .uppercase()

        assertFalse(DesktopPlatform.withLockedWindowsUpdateFile(file, "0".repeat(64)) { true })

        val ran = AtomicBoolean(false)
        val ok = DesktopPlatform.withLockedWindowsUpdateFile(file, expected) {
            ran.set(true)
            // FILE_SHARE_READ only: concurrent writers or deleters must fail with sharing violation.
            val writeBlocked = runCatching {
                Files.newByteChannel(file.toPath(), StandardOpenOption.WRITE).use { }
            }.isFailure
            val deleteBlocked = runCatching { Files.delete(file.toPath()) }.isFailure
            assertTrue("Expected write sharing violation while update handle is locked", writeBlocked)
            assertTrue("Expected delete sharing violation while update handle is locked", deleteBlocked)
            true
        }
        assertTrue(ok)
        assertTrue(ran.get())
        assertTrue("Handle must be closed after action completes", file.delete())
    }

    @Test fun windowsRepairServiceWaitVerifiesWaitStatusAndExitCode() {
        assumeTrue(DesktopPlatform.isWindows)
        assertEquals(ServiceRepair.Unavailable, DesktopPlatform.waitForWindowsProcessSuccess(null, 50))

        val okProc = ProcessBuilder("cmd.exe", "/c", "exit", "0").start()
        val okHandle = Kernel32.INSTANCE.OpenProcess(0x100000 or 0x0400, false, okProc.pid().toInt())
        assertEquals(ServiceRepair.Done, DesktopPlatform.waitForWindowsProcessSuccess(okHandle, 5_000))

        val failProc = ProcessBuilder("cmd.exe", "/c", "exit", "1").start()
        val failHandle = Kernel32.INSTANCE.OpenProcess(0x100000 or 0x0400, false, failProc.pid().toInt())
        assertEquals(ServiceRepair.Unavailable, DesktopPlatform.waitForWindowsProcessSuccess(failHandle, 5_000))

        val sleepProc = ProcessBuilder("ping.exe", "-n", "10", "127.0.0.1").start()
        try {
            val sleepHandle = Kernel32.INSTANCE.OpenProcess(0x100000 or 0x0400, false, sleepProc.pid().toInt())
            assertEquals(ServiceRepair.Unavailable, DesktopPlatform.waitForWindowsProcessSuccess(sleepHandle, 50))
        } finally {
            sleepProc.destroyForcibly()
        }
    }

    @Test fun windowsNamedPipeRejectsNonSystemOwnerAndSupportsTimeoutAndCancellation() {
        assumeTrue(DesktopPlatform.isWindows)

        fun startPipeServer(
            onClient: (com.sun.jna.platform.win32.WinNT.HANDLE, String) -> Unit,
        ): Pair<String, Thread> {
            val pipeName = "\\\\.\\pipe\\yumi-test-${UUID.randomUUID()}"
            val ready = CountDownLatch(1)
            val pipe = Kernel32.INSTANCE.CreateNamedPipe(
                pipeName,
                WinBase.PIPE_ACCESS_DUPLEX,
                WinBase.PIPE_TYPE_BYTE or WinBase.PIPE_READMODE_BYTE or WinBase.PIPE_WAIT,
                1,
                65_536,
                65_536,
                0,
                null,
            )
            assertTrue("CreateNamedPipe failed (${Kernel32.INSTANCE.GetLastError()})", pipe != WinBase.INVALID_HANDLE_VALUE)
            val ownerSid = DesktopPlatform.readObjectOwnerSid(pipe)!!
            val worker = thread(isDaemon = true) {
                try {
                    ready.countDown()
                    if (Kernel32.INSTANCE.ConnectNamedPipe(pipe, null) ||
                        Kernel32.INSTANCE.GetLastError() == 535 /* ERROR_PIPE_CONNECTED */
                    ) {
                        onClient(pipe, ownerSid)
                    }
                } finally {
                    Kernel32.INSTANCE.DisconnectNamedPipe(pipe)
                    Kernel32.INSTANCE.CloseHandle(pipe)
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            return pipeName to worker
        }

        // 1. Pipe squatter owned by current user (not S-1-5-18) must be rejected before any request bytes are sent.
        val bytesReceivedFromUntrustedClient = AtomicBoolean(false)
        val (squatterPipe, squatterThread) = startPipeServer { pipe, _ ->
            val buf = ByteArray(16)
            val read = IntByReference()
            if (Kernel32.INSTANCE.ReadFile(pipe, buf, buf.size, read, null) && read.value > 0) {
                bytesReceivedFromUntrustedClient.set(true)
            }
        }
        runBlocking {
            val status = WindowsTunnelBackend(squatterPipe, 2_000L, DesktopPlatform.LOCAL_SYSTEM_SID)
                .request(ServiceRequest(command = "connect"))
            assertEquals("unavailable", status.state)
            assertEquals("service_denied", status.error)
        }
        squatterThread.join(2_000)
        assertFalse("Must never send credentials to non-SYSTEM pipe owner", bytesReceivedFromUntrustedClient.get())

        // Also check diagnoseWindowsPipe reports service_denied for wrong owner.
        val (diagPipe, diagThread) = startPipeServer { _, _ -> }
        assertEquals("service_denied", DesktopPlatform.diagnoseWindowsPipe(diagPipe, DesktopPlatform.LOCAL_SYSTEM_SID))
        diagThread.join(2_000)

        // 2. Trusted owner roundtrip with fragmented UTF-8 reply.
        fun readPipeExact(pipe: com.sun.jna.platform.win32.WinNT.HANDLE, count: Int): ByteArray {
            val out = ByteArray(count)
            var off = 0
            val read = IntByReference()
            val scratch = ByteArray(minOf(count, 4096))
            while (off < count) {
                assertTrue(Kernel32.INSTANCE.ReadFile(pipe, scratch, minOf(scratch.size, count - off), read, null))
                assertTrue(read.value > 0)
                System.arraycopy(scratch, 0, out, off, read.value)
                off += read.value
            }
            return out
        }

        var currentSid = ""
        val (okPipe, okThread) = startPipeServer { pipe, ownerSid ->
            currentSid = ownerSid
            val reqLen = ByteBuffer.wrap(readPipeExact(pipe, 4)).int
            readPipeExact(pipe, reqLen)
            val body = """{"version":1,"state":"connected","detail":"служба","future":true}""".toByteArray()
            val frame = ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
            val written = IntByReference()
            for (b in frame) {
                assertTrue(Kernel32.INSTANCE.WriteFile(pipe, byteArrayOf(b), 1, written, null))
            }
            // Wait until the client drains every byte before DisconnectNamedPipe discards buffered data.
            Kernel32.INSTANCE.FlushFileBuffers(pipe)
        }
        // Discover current user's SID from an ephemeral pipe before calling with expectedOwnerSid = currentSid.
        val probePipe = Kernel32.INSTANCE.CreateNamedPipe(
            "\\\\.\\pipe\\yumi-sid-${UUID.randomUUID()}",
            WinBase.PIPE_ACCESS_DUPLEX,
            WinBase.PIPE_TYPE_BYTE or WinBase.PIPE_READMODE_BYTE or WinBase.PIPE_WAIT,
            1, 1024, 1024, 0, null,
        )
        val selfSid = try { DesktopPlatform.readObjectOwnerSid(probePipe)!! } finally { Kernel32.INSTANCE.CloseHandle(probePipe) }
        runBlocking {
            val status = WindowsTunnelBackend(okPipe, 2_000L, selfSid).request(ServiceRequest(command = "status"))
            assertEquals("connected", status.state)
            assertEquals("служба", status.detail)
        }
        okThread.join(2_000)
        assertEquals(selfSid, currentSid)

        // 3. Hung pipe triggers 250ms timeout via CancelIoEx and closes handle cleanly without blocking.
        val timeoutClosed = CountDownLatch(1)
        val (hungPipe, hungThread) = startPipeServer { pipe, _ ->
            val buf = ByteArray(1024)
            val read = IntByReference()
            // Read request frame, then wait until client closes handle after timeout.
            Kernel32.INSTANCE.ReadFile(pipe, buf, buf.size, read, null)
            if (!Kernel32.INSTANCE.ReadFile(pipe, buf, buf.size, read, null)) {
                timeoutClosed.countDown()
            }
        }
        try {
            runBlocking {
                withTimeout(2_000) {
                    val status = WindowsTunnelBackend(hungPipe, 250L, selfSid).request(ServiceRequest(command = "status"))
                    assertEquals("unavailable", status.state)
                    assertEquals("service_unavailable", status.error)
                }
                assertTrue("Timed-out client handle must close promptly via CancelIoEx", timeoutClosed.await(2, TimeUnit.SECONDS))
            }
        } finally {
            hungThread.join(2_000)
        }

        // 4. Caller cancellation aborts in-flight ReadFile via CancelIoEx, closes handle, and rethrows CancellationException.
        val clientClosed = CountDownLatch(1)
        val readStarted = CountDownLatch(1)
        val (cancelPipe, cancelThread) = startPipeServer { pipe, _ ->
            val buf = ByteArray(1024)
            val read = IntByReference()
            Kernel32.INSTANCE.ReadFile(pipe, buf, buf.size, read, null)
            readStarted.countDown()
            // Blocks until client closes its pipe handle (ERROR_BROKEN_PIPE).
            if (!Kernel32.INSTANCE.ReadFile(pipe, buf, buf.size, read, null)) {
                clientClosed.countDown()
            }
        }
        try {
            runBlocking {
                var outcome: Any? = null
                var caught: Throwable? = null
                val job = launch(Dispatchers.Default) {
                    try {
                        outcome = WindowsTunnelBackend(cancelPipe, 15_000L, selfSid).request(ServiceRequest(command = "status"))
                    } catch (t: Throwable) {
                        caught = t
                        throw t
                    }
                }
                assertTrue(readStarted.await(2, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertNull("Cancelled pipe request must not return a status", outcome)
                assertTrue("Expected CancellationException, got $caught", caught is CancellationException)
                assertTrue("Client handle must close promptly after CancelIoEx", clientClosed.await(2, TimeUnit.SECONDS))
            }
        } finally {
            cancelThread.join(2_000)
        }
    }
}
