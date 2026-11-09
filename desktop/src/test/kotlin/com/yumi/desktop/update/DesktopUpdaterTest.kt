package com.yumi.desktop.update

import com.yumi.desktop.model.DesktopSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopUpdaterTest {
    private val release = DesktopRelease("0.3.0", "", "", "", 1)
    private class Source(val release: DesktopRelease) : UpdateSource {
        var checks = 0
        var downloads = 0
        var failure = false
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun latest(): DesktopRelease? { checks++; if (failure) error("offline"); return release }
        override suspend fun download(release: DesktopRelease, progress: (Long, Long) -> Unit): VerifiedUpdate {
            downloads++; hold?.await(); progress(1, 1)
            return VerifiedUpdate(release, File("never-executed.exe"), "not-a-real-hash")
        }
    }

    @Test fun backgroundChecksAndDownloadsOnceThenPreservesReadyState() = runTest {
        val source = Source(release)
        val settings = MutableStateFlow(DesktopSettings())
        val updater = DesktopUpdater(backgroundScope, source, settings, "0.2.2", true, true, StandardTestDispatcher(testScheduler))
        advanceTimeBy(15_001); runCurrent()
        assertIs<DesktopUpdateState.Ready>(updater.state.value)
        assertEquals(1, source.checks); assertEquals(1, source.downloads)
        source.failure = true
        updater.check().join()
        assertIs<DesktopUpdateState.Ready>(updater.state.value)
        assertEquals(1, source.checks)
    }

    @Test fun automaticUpdatesCanBeDisabledButManualChecksStillWork() = runTest {
        val source = Source(release)
        val updater = DesktopUpdater(backgroundScope, source, MutableStateFlow(DesktopSettings(autoUpdate = false)), "0.2.2", true, true, StandardTestDispatcher(testScheduler))
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, source.checks)
        updater.check().join()
        assertIs<DesktopUpdateState.Available>(updater.state.value)
        assertEquals(0, source.downloads)
        updater.download().join()
        assertIs<DesktopUpdateState.Ready>(updater.state.value)
    }

    @Test fun portableBuildChecksButDoesNotDownloadAutomatically() = runTest {
        val source = Source(release)
        val updater = DesktopUpdater(backgroundScope, source, MutableStateFlow(DesktopSettings()), "0.2.2", false, false, StandardTestDispatcher(testScheduler))
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, source.checks)
        updater.check().join()
        assertIs<DesktopUpdateState.Available>(updater.state.value)
        assertEquals(0, source.downloads)
    }

    @Test fun equalOrOlderReleasesNeverDownload() = runTest {
        val source = Source(release)
        val updater = DesktopUpdater(backgroundScope, source, MutableStateFlow(DesktopSettings()), "0.3.0", true, false, StandardTestDispatcher(testScheduler))
        updater.check().join()
        assertIs<DesktopUpdateState.Current>(updater.state.value)
        assertEquals(0, source.downloads)
    }

    @Test fun cancelStillWorksAfterAnotherCheckAndAllowsRetry() = runTest {
        val source = Source(release).apply { hold = CompletableDeferred() }
        val updater = DesktopUpdater(backgroundScope, source, MutableStateFlow(DesktopSettings()), "0.2.2", true, false, StandardTestDispatcher(testScheduler))
        val checking = updater.check()
        runCurrent()
        assertIs<DesktopUpdateState.Downloading>(updater.state.value)
        updater.check().join()
        updater.cancelDownload()
        runCurrent()
        assertTrue(checking.isCancelled)
        assertIs<DesktopUpdateState.Available>(updater.state.value)
        source.hold?.complete(Unit)
        updater.download().join()
        assertIs<DesktopUpdateState.Ready>(updater.state.value)
    }

    @Test fun manualNetworkFailureIsVisibleButBackgroundFailureIsQuiet() = runTest {
        val source = Source(release).apply { failure = true }
        val updater = DesktopUpdater(backgroundScope, source, MutableStateFlow(DesktopSettings()), "0.2.2", true, false, StandardTestDispatcher(testScheduler))
        updater.check(manual = false).join()
        assertIs<DesktopUpdateState.Idle>(updater.state.value)
        updater.check().join()
        assertIs<DesktopUpdateState.Failed>(updater.state.value)
    }
}
