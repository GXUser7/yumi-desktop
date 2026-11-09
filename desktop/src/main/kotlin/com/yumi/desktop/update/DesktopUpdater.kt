package com.yumi.desktop.update

import com.mydrop.vpn.core.model.Version
import com.yumi.desktop.model.DesktopSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

sealed interface DesktopUpdateState {
    data object Idle : DesktopUpdateState
    data object Checking : DesktopUpdateState
    data class Current(val checkedAt: Long) : DesktopUpdateState
    data class Available(val release: DesktopRelease) : DesktopUpdateState
    data class Downloading(val release: DesktopRelease, val downloaded: Long, val total: Long) : DesktopUpdateState
    data class Ready(val update: VerifiedUpdate) : DesktopUpdateState
    data class Failed(val release: DesktopRelease? = null, val integrity: Boolean = false) : DesktopUpdateState
}

class DesktopUpdater(
    private val scope: CoroutineScope,
    private val source: UpdateSource,
    private val settings: StateFlow<DesktopSettings>,
    private val currentVersion: String,
    val installed: Boolean,
    automatic: Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow<DesktopUpdateState>(DesktopUpdateState.Idle)
    val state = mutable.asStateFlow()
    private val operation = Mutex()
    @Volatile private var job: Job? = null

    init {
        if (automatic) scope.launch {
            settings.map { it.autoUpdate }.distinctUntilChanged().collectLatest { enabled ->
                if (enabled) {
                    delay(15_000)
                    while (isActive) { check(manual = false).join(); delay(4 * 60 * 60_000L) }
                }
            }
        }
    }

    fun check(manual: Boolean = true): Job = scope.launch(dispatcher) {
        if (!operation.tryLock()) return@launch
        if (mutable.value is DesktopUpdateState.Ready) { operation.unlock(); return@launch }
        job = currentCoroutineContext()[Job]
        val previous = mutable.value
        try {
            mutable.value = DesktopUpdateState.Checking
            val latest = source.latest()
            if (latest == null || !Version.isNewer(latest.version, currentVersion)) {
                mutable.value = DesktopUpdateState.Current(System.currentTimeMillis())
            } else {
                mutable.value = DesktopUpdateState.Available(latest)
                if (installed && settings.value.autoUpdate) downloadRelease(latest)
            }
        } catch (cancelled: CancellationException) {
            if (mutable.value is DesktopUpdateState.Checking) mutable.value = previous
            throw cancelled
        } catch (error: Exception) {
            val release = (mutable.value as? DesktopUpdateState.Downloading)?.release
            mutable.value = if (manual) DesktopUpdateState.Failed(release, error is UpdateIntegrityException) else release?.let(DesktopUpdateState::Available) ?: previous
        } finally { job = null; operation.unlock() }
    }

    fun download(): Job = scope.launch(dispatcher) {
        if (!operation.tryLock()) return@launch
        job = currentCoroutineContext()[Job]
        try {
            val release = when (val current = mutable.value) {
                is DesktopUpdateState.Available -> current.release
                is DesktopUpdateState.Failed -> current.release
                else -> null
            } ?: return@launch
            try { downloadRelease(release) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = DesktopUpdateState.Failed(release, error is UpdateIntegrityException) }
        } finally { job = null; operation.unlock() }
    }

    private suspend fun downloadRelease(release: DesktopRelease) {
        mutable.value = DesktopUpdateState.Downloading(release, 0, release.size)
        var lastProgress = 0L
        try {
            val downloaded = source.download(release) { done, total ->
                val now = System.nanoTime()
                // Large downloads must not recompose the settings page once per network buffer.
                if (now - lastProgress >= 100_000_000L || done == total) {
                    lastProgress = now
                    mutable.value = DesktopUpdateState.Downloading(release, done, total)
                }
            }
            mutable.value = DesktopUpdateState.Ready(downloaded)
        } catch (cancelled: CancellationException) {
            mutable.value = DesktopUpdateState.Available(release)
            throw cancelled
        }
    }

    fun cancelDownload() { if (mutable.value is DesktopUpdateState.Downloading) job?.cancel() }

    /** Recheck the downloaded file before handing it to the Windows installer. */
    suspend fun readyToInstall(): VerifiedUpdate? = withContext(dispatcher) {
        val ready = (mutable.value as? DesktopUpdateState.Ready)?.update ?: return@withContext null
        if (runCatching { ready.verify() }.getOrDefault(false)) ready else { mutable.value = DesktopUpdateState.Failed(ready.release, integrity = true); null }
    }
}
