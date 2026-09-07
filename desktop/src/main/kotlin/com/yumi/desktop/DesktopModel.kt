package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.core.parse.*
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.data.*
import com.mydrop.vpn.pairing.*
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class DesktopModel(val backend: TunnelBackend = WindowsTunnelBackend(), val directory: File = DesktopPlatform.dataDirectory()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val message = MutableStateFlow<String?>(null)
    val settings = SettingsRepository(directory, scope)
    val profiles = ProfileRepository(directory, scope)
    val desktop = JsonStore(File(directory, "desktop.json"), DesktopSettings.serializer(), DesktopSettings(), scope)
    val strings = Strings { settings.value.language }
    val logs = LogRepository(strings)
    val status = MutableStateFlow(ServiceStatus(state = "unavailable"))
    val traffic = MutableStateFlow(TrafficStats.Zero)
    val busy = MutableStateFlow(false)
    val speed = MutableStateFlow(SpeedTestState())
    val localAddresses = MutableStateFlow(DesktopPlatform.localAddresses())
    val selectedAddress = MutableStateFlow(localAddresses.value.firstOrNull())
    val pairing = PairingReceiver({ selectedAddress.value?.address }, scope) { transfer ->
        val subscription = profiles.upsertSubscriptionSource(transfer.name, transfer.url, transfer.userAgentOverride, transfer.headers)
        refresh(subscription)
        val latest = profiles.state.value.subscriptions.first { it.id == subscription.id }
        PairingResult(true, if (latest.lastError == null) "accepted" else "saved_refresh_failed", latest.remoteTitle ?: latest.name)
    }
    private val commands = Mutex()
    private val subscriptions = Mutex()
    private var speedJob: Job? = null
    private val subscriptionService = SubscriptionService(logs, strings, identity = {
        DeviceIdentity(settings.value.deviceId, System.getProperty("os.name"), System.getProperty("os.version"), "Yumi Desktop")
    }, probeEndpoint = { status.value.probe() })

    init {
        if (settings.value.deviceId.isBlank()) settings.update {
            it.copy(deviceId = UUID.randomUUID().toString(), mtu = 1500, routingMode = RoutingMode.Global, autoFailover = false)
        }
        scope.launch {
            while (isActive) {
                commands.withLock { updateStatus(backend.request(ServiceRequest(command = "status"))) }
                delay(1000)
            }
        }
        scope.launch {
            // Once on launch, before any waiting. The window is opened to use the tunnel, and the
            // server list has been sitting untouched since it was last closed — an interval of six
            // hours meant the first connection of the day went through a list that may no longer
            // exist. The two-minute floor below is what keeps a restart loop from turning that
            // into a fetch per launch.
            if (settings.value.subscriptionAutoUpdate) refreshDue(startup = true)
            // A minute at a time rather than one long sleep for the whole interval: sleeping the
            // interval meant that changing it in settings did nothing until the sleep already in
            // flight had run out, which on the six-hour setting is most of a day.
            while (isActive) {
                delay(60_000L)
                if (settings.value.subscriptionAutoUpdate) refreshDue()
            }
        }
    }

    fun text(ru: String, en: String) = strings.text(ru, en)
    fun action(block: suspend () -> Unit) = scope.launch(Dispatchers.IO) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message.value = text("Не удалось выполнить действие. Проверьте данные и повторите.", "Operation failed. Check your input and retry.") }
    }
    private fun updateStatus(next: ServiceStatus) {
        val old = status.value
        traffic.value = TrafficStats(next.upload, next.download,
            if (next.connectedAt == old.connectedAt) (next.upload - old.upload).coerceAtLeast(0) else 0,
            if (next.connectedAt == old.connectedAt) (next.download - old.download).coerceAtLeast(0) else 0)
        status.value = next
    }
    fun toggleConnection() = action {
        commands.withLock {
            cancelSpeed()
            if (status.value.state in listOf("connected", "connecting")) {
                updateStatus(backend.request(ServiceRequest(command = "disconnect")))
            } else connectSelected()
        }
    }
    private suspend fun connectSelected() {
        val node = profiles.selectedNode()
        if (node == null) { message.value = text("Добавьте подписку и выберите сервер.", "Add a subscription and choose a server."); return }
        updateStatus(ServiceStatus(state = "connecting", nodeId = node.id))
        val request = ConnectRequestFactory.create(node, settings.value, desktop.value)
        updateStatus(backend.request(request))
    }
    fun selectNode(id: String) = action {
        commands.withLock {
            val reconnect = status.value.state == "connected"
            profiles.selectNode(id)
            if (reconnect) { cancelSpeed(); connectSelected() }
        }
    }
    fun changeSettings(transform: (AppSettings) -> AppSettings, reconnect: Boolean = false) = action {
        commands.withLock {
            settings.update(transform)
            if (reconnect && status.value.state == "connected") { cancelSpeed(); connectSelected() }
        }
    }
    fun changeDesktop(next: DesktopSettings, reconnect: Boolean = false) = action {
        commands.withLock {
            if (next.launchAtLogin != desktop.value.launchAtLogin) DesktopPlatform.setLaunchAtLogin(next.launchAtLogin)
            desktop.update { next }
            if (reconnect && status.value.state == "connected") { cancelSpeed(); connectSelected() }
        }
    }
    fun importText(raw: String) = action {
        val value = raw.trim()
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
            val transfer = SubscriptionTransfer(name = text("Подписка", "Subscription"), url = value)
            requireNotNull(transfer.validate())
            refresh(profiles.upsertSubscriptionSource(transfer.name, value, null, emptyMap()))
        } else {
            val parsed = SubscriptionParser.parse(value, "manual")
            require(parsed is SubscriptionBody.Nodes)
            val supported = parsed.nodes.filter { XrayConfigFactory.unsupported(it) == null }
            require(supported.isNotEmpty())
            profiles.addNodes(supported.map { it.copy(subscriptionId = null) })
        }
    }
    suspend fun refresh(subscription: Subscription) = subscriptions.withLock {
        busy.value = true
        try {
            when (val result = subscriptionService.fetch(subscription)) {
                is SubscriptionUpdate.Success -> if (profiles.state.value.subscriptions.any { it.id == subscription.id }) profiles.applySubscriptionUpdate(
                    subscription.id, result.nodes, result.subscription.userInfo, result.subscription.remoteTitle, result.subscription.webPageUrl)
                is SubscriptionUpdate.Failure -> profiles.recordSubscriptionError(subscription.id, text("Не удалось обновить подписку", "Subscription refresh failed"))
            }
        } finally { busy.value = false }
    }
    fun refreshAll() = action { profiles.state.value.subscriptions.filter { it.enabled }.forEach { refresh(it) } }

    /**
     * Registers the tunnel service to whoever is running this window.
     *
     * The service admits one account and it is picked when the service is installed — which, on a
     * machine where the elevation prompt is answered by a separate administrator, is not the
     * account that will ever open Yumi. Nothing in the old design could say so: the app was
     * refused, reported "service unavailable", and advised installing the service that was already
     * running. This is the way out of that, and it costs one elevation prompt.
     *
     * Nothing is reported on success. The status poll is running at one hertz and will say what
     * happened a second later, in the place the user is already looking.
     */
    fun repairService() = scope.launch(Dispatchers.IO) {
        when (DesktopPlatform.repairService()) {
            ServiceRepair.Done -> Unit
            ServiceRepair.Declined -> Unit
            ServiceRepair.Unavailable -> message.value = text(
                "Не удалось переназначить службу. Переустановите Yumi.",
                "The service could not be re-registered. Reinstall Yumi.",
            )
        }
    }

    /**
     * Everything enabled that has gone longer than the chosen interval without answering.
     *
     * Measured against each subscription's own `lastUpdatedEpochMillis`, which is persisted — so
     * closing and reopening the window four times an hour still amounts to one refresh per
     * interval rather than four.
     */
    private suspend fun refreshDue(startup: Boolean = false) {
        val due = if (startup) STARTUP_FLOOR_MILLIS
        else settings.value.subscriptionUpdateMinutes.coerceAtLeast(10) * 60_000L
        val now = System.currentTimeMillis()
        profiles.state.value.subscriptions
            .filter { it.enabled && now - (it.lastUpdatedEpochMillis ?: 0L) >= due }
            .forEach { refresh(it) }
    }
    fun removeSubscription(id: String) = action {
        commands.withLock {
            val active = profiles.allNodes.firstOrNull { it.id == status.value.nodeId }
            if (active?.subscriptionId == id && status.value.state in listOf("connecting", "connected")) {
                cancelSpeed(); updateStatus(backend.request(ServiceRequest(command = "disconnect")))
            }
            profiles.removeSubscription(id)
        }
    }
    fun ping() = action {
        busy.value = true
        try { LatencyTester().measureAll(profiles.nodes, settings.value.pingMode) { profiles.recordLatency(it) } }
        finally { busy.value = false }
    }
    fun startPairing() {
        localAddresses.value = DesktopPlatform.localAddresses()
        if (selectedAddress.value !in localAddresses.value) selectedAddress.value = localAddresses.value.firstOrNull()
        pairing.start("Yumi Desktop")
    }
    /**
     * Runs one measurement, keeping the trace the screen draws.
     *
     * The tester reports a rate per sample and no history; the phone's does the same and its
     * screen has [SpeedTestState.series] to draw from, so the samples are collected here as they
     * arrive and handed back on the state. A phase ending files its trace under that phase, which
     * is what lets the results panel show the shape each direction was measured at rather than
     * only the average it settled on.
     */
    fun startSpeed() {
        cancelSpeed()
        val current = status.value
        if (current.state == "connecting" || current.state == "disconnecting") return
        val probe = current.probe()
        speedJob = action {
            var live = emptyList<Long>()
            var download = emptyList<Long>()
            var upload = emptyList<Long>()
            var phase = SpeedPhase.Idle
            SpeedTester(logs, strings).measure(probe, profiles.allNodes.firstOrNull { it.id == current.nodeId }?.name).collect { next ->
                if (next.phase != phase) {
                    when (phase) {
                        SpeedPhase.Download -> download = live
                        SpeedPhase.Upload -> upload = live
                        else -> Unit
                    }
                    live = emptyList()
                    phase = next.phase
                }
                if (next.liveBytesPerSecond > 0) live = (live + next.liveBytesPerSecond).takeLast(240)
                speed.value = next.copy(series = live, downloadSeries = download, uploadSeries = upload)
            }
        }
    }
    fun cancelSpeed() { speedJob?.cancel(); speedJob = null; speed.value = SpeedTestState() }
    suspend fun close() {
        pairing.stop(); cancelSpeed()
        commands.withLock { if (status.value.state in listOf("connected","connecting")) backend.request(ServiceRequest(command = "disconnect")) }
        scope.cancel()
    }

    private companion object {
        /** How stale a list has to be for opening the window to be a reason to refresh it. */
        const val STARTUP_FLOOR_MILLIS = 2 * 60_000L
    }
}
