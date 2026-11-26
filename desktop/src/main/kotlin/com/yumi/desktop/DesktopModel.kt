package com.yumi.desktop

import com.mydrop.vpn.core.model.*
import com.mydrop.vpn.core.net.sanitizeBillingUrl
import com.mydrop.vpn.core.net.sanitizePortalUrl
import com.mydrop.vpn.core.parse.*
import com.mydrop.vpn.core.xray.XrayConfigFactory
import com.mydrop.vpn.data.*
import com.mydrop.vpn.pairing.*
import com.mydrop.vpn.shared.R
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.*
import com.yumi.desktop.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class ConnectionHealth { Unchecked, Checking, Reachable, Unreachable }

class DesktopModel(
    val backend: TunnelBackend = WindowsTunnelBackend(),
    val directory: File = DesktopPlatform.dataDirectory(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val measureLatency: (suspend (ProxyNode, PingMode) -> LatencyResult)? = null,
    private val checkTunnel: suspend (ProbeEndpoint) -> Boolean = DefaultCheckTunnel,
    private val serviceInstaller: (suspend () -> ServiceRepair)? =
        if (backend is UnixTunnelBackend && DesktopPlatform.serviceExecutable() != null) ({ DesktopPlatform.repairService() }) else null,
) {
    val canInstallService get() = serviceInstaller != null
    val serviceInstalling = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val settings = SettingsRepository(directory, scope)
    val profiles = ProfileRepository(directory, scope)
    private val connection = JsonStore(File(directory, "active-connection.json"), ActiveConnection.serializer(), ActiveConnection(), scope)
    val activeNode = MutableStateFlow<ProxyNode?>(null)
    val activeLatency = MutableStateFlow<LatencyResult?>(null)
    val serverQuery = MutableStateFlow("")
    private var requestedNode: ProxyNode? = null
    val desktop = JsonStore(File(directory, "desktop.json"), DesktopSettings.serializer(), DesktopSettings(), scope)
    val updates = DesktopUpdater(
        scope, GitHubUpdates(File(directory, "updates"), HttpsUpdateTransport(AppVersion)), desktop.state, AppVersion,
        installed = DesktopPlatform.installedDirectory() != null,
        automatic = backend !is DemoBackend && System.getProperty("jpackage.app-path") != null,
    )
    val strings = Strings { settings.value.language }
    val logs = LogRepository(strings)
    val status = MutableStateFlow(ServiceStatus(state = "unavailable"))
    /** Status without 1 Hz byte/second-resolution ticks so screens that do not display them stay idle. */
    val uiStatus: Flow<ServiceStatus> = status
        .map(ServiceStatus::forUi)
        .distinctUntilChanged()
    /** A running core is not proof that its server can carry traffic. Never changes the selected server. */
    val connectionHealth = MutableStateFlow(ConnectionHealth.Unchecked)
    val switching = MutableStateFlow(false)
    /** The physical adapters the service can send traffic out of, for the settings screen. */
    val adapters = MutableStateFlow<List<NetworkAdapter>>(emptyList())
    val traffic = MutableStateFlow(TrafficStats.Zero)
    /** Session history belongs to the connection, including while choosing a server or in the tray. */
    val trafficHistory = MutableStateFlow<List<TrafficStats>>(emptyList())
    private var trafficEpoch: Long? = null
    private val busyCount = AtomicInteger(0)
    val busy = MutableStateFlow(false)
    val pinging = MutableStateFlow(false)
    val speed = MutableStateFlow(SpeedTestState())
    val localAddresses = MutableStateFlow<List<LocalAddress>>(emptyList())
    val selectedAddress = MutableStateFlow(localAddresses.value.firstOrNull())
    val pairing = PairingReceiver({ selectedAddress.value?.address }, scope) { transfer ->
        val subscription = profiles.upsertSubscriptionSource(transfer.name, transfer.url, transfer.userAgentOverride, transfer.headers)
        refresh(subscription)
        val latest = profiles.state.value.subscriptions.first { it.id == subscription.id }
        PairingResult(true, if (latest.lastError == null) "accepted" else "saved_refresh_failed", latest.remoteTitle ?: latest.name)
    }
    private val commands = Mutex()
    private val subscriptions = Mutex()
    private val speedLock = Any()
    private data class PingRequest(val sequence: Long = 0, val nodes: List<ProxyNode> = emptyList())
    private val pingRequests = MutableStateFlow(PingRequest())
    private var connectionProbe: Job? = null
    private var tunnelMonitor: Job? = null
    @Volatile private var speedJob: Job? = null
    @Volatile private var pairingStartJob: Job? = null
    private val statusJob: Job
    private val refreshJob: Job
    private val pingJob: Job
    private val startupRefresh: Job?
    @Volatile private var startupConnectPending = desktop.value.connectOnLaunch
    @Volatile private var settingsReconnectPending = false
    private val subscriptionService = SubscriptionService(logs, strings, identity = {
        DeviceIdentity(settings.value.deviceId, System.getProperty("os.name"), System.getProperty("os.version"), "Yumi Desktop")
    }, probeEndpoint = { status.value.probe() })
    val billingService = BillingService(logs, strings, probeEndpoint = { status.value.probe() })
    val billingSheet = MutableStateFlow<BillingDialogState?>(null)
    val serviceLogs = MutableStateFlow("")
    val serviceLogsRefreshing = MutableStateFlow(false)
    private val coreProber = CoreProber { nodes, currentSettings, timeoutMillis, concurrency, onResult ->
        val spec = ConnectRequestFactory.buildProbeSpec(nodes, currentSettings, desktop.value, timeoutMillis, concurrency)
        if (spec.targets.isEmpty()) return@CoreProber
        val ipcReply = runCatching { backend.request(ServiceRequest(command = "probe", probe = spec)) }.getOrNull()
        val ipcResults = ipcReply?.probeResults
        if (ipcResults != null) {
            ipcResults.forEach { item -> onResult(item.id, item.millis.takeIf { it > 0 }) }
            return@CoreProber
        }
        val exe = probeServiceExecutable() ?: return@CoreProber
        val payload = ProbeJson.encodeToString(ProbeSpec.serializer(), spec)
        val output = withContext(Dispatchers.IO) {
            val process = ProcessBuilder(exe.absolutePath, "--probe")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    process.destroyForcibly()
                }
            }
            try {
                process.outputStream.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (process.waitFor() == 0) stdout else ""
            } finally {
                cancelWatcher.cancel()
            }
        }
        if (output.isNotBlank()) {
            val parsed = runCatching {
                ProbeJson.decodeFromString(ListSerializer(ProbeResultItem.serializer()), output.trim())
            }.getOrNull()
            parsed?.forEach { item -> onResult(item.id, item.millis.takeIf { it > 0 }) }
        }
    }
    private val latencyTester = LatencyTester(
        timeoutProvider = { settings.value.probeTimeoutMillis },
        settingsProvider = { settings.value },
        coreProber = coreProber,
        isTunnelRunning = { status.value.state == "connected" },
    )
    private suspend fun probeSingle(node: ProxyNode, mode: PingMode): LatencyResult =
        measureLatency?.invoke(node, mode)
            ?: if (backend is DemoBackend) backend.measureLatency(node, mode)
            else latencyTester.measure(node, mode)

    private suspend fun probeTunnelHealth(probe: ProbeEndpoint): Int? =
        DefaultProbe.probe(probe, settings.value.probeUrl)

    init {
        pingJob = scope.launch {
            pingRequests.filter { it.sequence > 0 }.collectLatest { request ->
                pinging.value = true
                try { measureServers(request.nodes) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message.value = text("Не удалось измерить задержку.", "Could not measure latency.") }
                finally { pinging.value = false }
            }
        }
        if (settings.value.deviceId.isBlank()) settings.update {
            it.copy(deviceId = UUID.randomUUID().toString(), mtu = 1500, routingMode = RoutingMode.Global, autoFailover = false)
        }
        // The window never moves the tunnel between servers on its own, whatever a settings file
        // carried over from a phone says.
        if (settings.value.autoFailover) settings.update { it.copy(autoFailover = false) }
        // Applications used to be kept as full paths; the rules are process names now.
        val names = desktop.value.applications.mapNotNull { runCatching { RuleValidation.application(it) }.getOrNull() }.distinct()
        if (names != desktop.value.applications) desktop.update { it.copy(applications = names) }
        // Once on launch, before any waiting, so startup auto-connect uses an up-to-date server list.
        startupRefresh = if (settings.value.subscriptionAutoUpdate && settings.value.subscriptionUpdateOnStart) {
            scope.launch { refreshDue(startup = true) }
        } else null
        statusJob = scope.launch {
            while (isActive) {
                try {
                    commands.withLock {
                        updateStatus(backend.request(ServiceRequest(command = "status")))
                        if (settingsReconnectPending && status.value.state == "connected") {
                            settingsReconnectPending = false
                            cancelSpeed()
                            reconnectActive()
                        } else if (status.value.state in listOf("disconnected", "failed")) {
                            settingsReconnectPending = false
                        }
                    }
                    if (startupConnectPending && status.value.state == "disconnected") {
                        if (startupRefresh?.isActive == true) withTimeoutOrNull(4_000) { startupRefresh.join() }
                        commands.withLock { connectOnStartup() }
                    } else {
                        commands.withLock { connectOnStartup() }
                    }
                    sampleTraffic()
                    afterStatus()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep polling even if a transient IPC/config error occurred during status or reconnect.
                }
                delay(1000)
            }
        }
        refreshJob = scope.launch {
            startupRefresh?.join()
            // A minute at a time rather than one long sleep for the whole interval: sleeping the
            // interval meant that changing it in settings did nothing until the sleep already in
            // flight had run out, which on the six-hour setting is most of a day.
            while (isActive) {
                delay(60_000L)
                if (settings.value.subscriptionAutoUpdate) refreshDue()
            }
        }
    }

    /** Once per launch: the databases are fetched by themselves the first time they are missing. */
    private var geoAsked = false

    /**
     * What a fresh status sets in motion, beyond showing it.
     *
     * The databases are fetched without being asked the first time they turn out to be missing —
     * "Russian sites directly" is on by default and means nothing until they are there, and
     * nobody should have to find a button to make a default setting work. And when they arrive
     * while the tunnel is up, it is raised again once so that they apply now rather than at the
     * next connection.
     */
    private suspend fun afterStatus() {
        val current = status.value
        if (current.state == "unavailable") return
        if (!geoAsked && desktop.value.bypassRussia && !current.geoReady && !current.geoUpdating) {
            geoAsked = true
            commands.withLock { updateStatus(backend.request(ServiceRequest(command = "geo_update"))) }
        }
        val arrived = current.geoReady && !geoWasReady
        geoWasReady = current.geoReady
        if (arrived && geoSeen && current.state == "connected" && desktop.value.bypassRussia) {
            commands.withLock { if (status.value.state == "connected") { cancelSpeed(); reconnectActive() } }
        }
        geoSeen = true
    }
    private var geoWasReady = false
    private var geoSeen = false

    private suspend fun connectOnStartup() {
        if (!startupConnectPending || status.value.state == "unavailable") return
        startupConnectPending = false
        if (desktop.value.connectOnLaunch && status.value.state == "disconnected" && profiles.selectedNode() != null) {
            connectSelected()
        }
    }

    /** Fetches the routing databases again; the service reports progress on the status. */
    fun updateGeo() = action {
        commands.withLock { updateStatus(backend.request(ServiceRequest(command = "geo_update"))) }
    }

    /** Reads the adapters again: networks are plugged in and out while the window stays open. */
    fun refreshAdapters() = action {
        commands.withLock { adapters.value = backend.request(ServiceRequest(command = "adapters")).adapters }
    }

    /**
     * Takes the kill switch down without raising the tunnel: the way out of a blocked internet
     * when the server is the thing that is broken. The service lowers it on any disconnect.
     */
    fun releaseKillSwitch() = action {
        commands.withLock { cancelSpeed(); updateStatus(backend.request(ServiceRequest(command = "disconnect"))) }
    }

    /** The programs on this machine, for the split-tunnelling picker. Read off the UI thread. */
    suspend fun discoverApps(): List<DesktopApp> = withContext(Dispatchers.IO) { AppCatalog.discover() }.also { knownApps.value = it }

    /** The last list [discoverApps] found, for drawing chosen applications with their icons. */
    val knownApps = MutableStateFlow<List<DesktopApp>>(emptyList())

    fun text(ru: String, en: String) = strings.text(ru, en)
    fun action(block: suspend () -> Unit) = scope.launch(Dispatchers.IO) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message.value = text("Не удалось выполнить действие. Проверьте данные и повторите.", "Operation failed. Check your input and retry.") }
    }
    private fun updateStatus(next: ServiceStatus) {
        val old = status.value
        val saved = connection.value
        val node = if (next.state in listOf("connected", "connecting", "disconnecting")) {
            saved.node?.takeIf { saved.serviceNodeId == next.nodeId && saved.connectedAt == next.connectedAt }
                ?: requestedNode?.takeIf { it.id == next.nodeId }
                ?: profiles.allNodes.firstOrNull { it.id == next.nodeId }
                ?: profiles.nodeIdMigration[next.nodeId]?.let { id -> profiles.allNodes.firstOrNull { it.id == id } }
        } else null
        activeNode.value = node
        if (next.state == "connected" && node != null) {
            val acknowledged = ActiveConnection(next.nodeId, next.connectedAt, node)
            if (saved != acknowledged) connection.update { acknowledged }
        } else if (next.state in listOf("disconnected", "failed") && saved != ActiveConnection()) {
            connection.update { ActiveConnection() }
        }
        traffic.value = TrafficStats(next.upload, next.download,
            if (next.connectedAt == old.connectedAt) (next.upload - old.upload).coerceAtLeast(0) else 0,
            if (next.connectedAt == old.connectedAt) (next.download - old.download).coerceAtLeast(0) else 0)
        status.value = next
        if (next.state == "connected" && next.nodeId != null &&
            (old.state != "connected" || old.nodeId != next.nodeId || old.connectedAt != next.connectedAt || old.probe() != next.probe())) {
            connectionProbe?.cancel()
            tunnelMonitor?.cancel()
            activeLatency.value = null
            if (next.groupLatency > 0) activeLatency.value = LatencyResult(node?.id ?: next.nodeId.orEmpty(),
                next.groupLatency, System.currentTimeMillis())
            connectionHealth.value = ConnectionHealth.Checking
            if (node != null) connectionProbe = scope.launch {
                val result = try { probeSingle(node, settings.value.pingMode) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LatencyResult(node.id, 0, System.currentTimeMillis(), failed = true) }
                ensureActive()
                if (status.value.nodeId == next.nodeId && status.value.connectedAt == next.connectedAt && activeLatency.value == null) {
                    activeLatency.value = result
                }
                if (profiles.allNodes.any { it.id == result.nodeId }) profiles.recordLatency(result)
                // Demo/older backends have no loopback probe. Production monitors actual
                // traffic below; keep the endpoint latency measurement once per connection.
                if (next.probe() == null) publishHealth(next, !result.failed, result.millis)
            }
            next.probe()?.let { probe ->
                tunnelMonitor = scope.launch {
                    while (isActive) {
                        val rtt = try {
                            if (checkTunnel === DefaultCheckTunnel) {
                                probeTunnelHealth(probe)
                            } else {
                                val started = System.nanoTime()
                                if (checkTunnel(probe)) ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1) else null
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        ensureActive()
                        publishHealth(next, rtt != null, rtt ?: 0)
                        delay(CONNECTION_CHECK_MILLIS)
                    }
                }
            }
        } else if (next.state != "connected") {
            connectionProbe?.cancel()
            tunnelMonitor?.cancel()
            connectionHealth.value = ConnectionHealth.Unchecked
            activeLatency.value = null
        }
    }
    private fun sampleTraffic() {
        val current = status.value
        if (current.state == "connected") {
            if (trafficEpoch != current.connectedAt) trafficHistory.value = emptyList()
            trafficEpoch = current.connectedAt
            trafficHistory.update { (it + traffic.value).takeLast(48) }
        } else if (current.state != "connecting") {
            trafficEpoch = null
            trafficHistory.value = emptyList()
        }
    }
    private suspend fun publishHealth(connection: ServiceStatus, reachable: Boolean, millis: Int) = commands.withLock {
        val current = status.value
        if (current.state == "connected" && current.nodeId == connection.nodeId &&
            current.connectedAt == connection.connectedAt && current.probe() == connection.probe()) {
            connectionHealth.value = if (reachable) ConnectionHealth.Reachable else ConnectionHealth.Unreachable
            activeLatency.value = LatencyResult(activeNode.value?.id ?: current.nodeId.orEmpty(),
                if (reachable) millis.coerceAtLeast(1) else 0, System.currentTimeMillis(), failed = !reachable)
        }
    }
    fun toggleConnection() = action {
        commands.withLock {
            startupConnectPending = false
            cancelSpeed()
            if (status.value.state == "unavailable" && canInstallService && !prepareService()) return@withLock
            if (status.value.state in listOf("connected", "connecting")) {
                updateStatus(backend.request(ServiceRequest(command = "disconnect")))
            } else connectSelected()
        }
    }
    private suspend fun connectSelected() = connectNode(profiles.selectedNode())

    private suspend fun reconnectActive() {
        val node = activeNode.value
        if (node == null) {
            message.value = text("Активного сервера больше нет в подписке. Выберите сервер для переподключения.",
                "The active server is no longer in the subscription. Choose a server to reconnect.")
            return
        }
        connectNode(node)
    }

    private suspend fun connectNode(node: ProxyNode?) {
        if (node == null) { message.value = text("Добавьте подписку и выберите сервер.", "Add a subscription and choose a server."); return }
        settingsReconnectPending = false
        val request = ConnectRequestFactory.create(
            node = node,
            settings = settings.value,
            desktop = desktop.value,
            providerProfile = profiles.providerProfileFor(node),
            dnsOverride = profiles.selectedDnsProfile()?.url,
        )
        // Preserve geo/service metadata while the connect command is in flight. Dropping it
        // makes afterStatus mistake the next poll for newly downloaded databases and reconnect.
        val hotSwap = status.value.state == "connected"
        if (hotSwap) {
            switching.value = true
        } else updateStatus(status.value.copy(state = "connecting", nodeId = node.id, error = null, detail = null))
        try {
            requestedNode = node
            val reply = backend.request(request)
            updateStatus(reply)
            if (reply.error == "hot_swap_failed") {
                if (reply.nodeId != node.id) requestedNode = null
                val summary = text("Не удалось сменить сервер. Предыдущее соединение сохранено.", "Could not switch servers. The previous connection is still active.")
                val detail = reply.detail?.trim()?.takeIf { it.isNotEmpty() }
                message.value = if (detail != null) "$summary\n\n$detail" else summary
            }
        } finally { switching.value = false }
    }
    fun selectNode(id: String) = action {
        commands.withLock {
            if (profiles.nodes.none { it.id == id }) return@withLock
            val reconnect = status.value.state in listOf("connected", "connecting")
            profiles.selectNode(id)
            if (reconnect) { cancelSpeed(); connectSelected() }
        }
    }
    private fun currentReconnectKey(
        appSettings: AppSettings = settings.value,
        desktopSettings: DesktopSettings = desktop.value,
    ): ConnectReconnectKey = ConnectRequestFactory.reconnectKey(
        settings = appSettings,
        desktop = desktopSettings,
        dnsOverride = profiles.selectedDnsProfile()?.url,
        providerProfile = (activeNode.value ?: profiles.selectedNode())?.let(profiles::providerProfileFor),
    )

    fun changeSettings(transform: (AppSettings) -> AppSettings, reconnect: Boolean = false) = action {
        commands.withLock {
            val previous = currentReconnectKey()
            settings.update(transform)
            if (reconnect || previous != currentReconnectKey()) applyConnectionSettings()
        }
    }
    fun changeDesktop(next: DesktopSettings, reconnect: Boolean = false) = changeDesktop({ next }, reconnect)
    fun changeDesktop(transform: (DesktopSettings) -> DesktopSettings, reconnect: Boolean = false) = action {
        commands.withLock {
            val before = desktop.value
            val previous = currentReconnectKey(desktopSettings = before)
            val next = transform(before)
            if (next.launchAtLogin != before.launchAtLogin) DesktopPlatform.setLaunchAtLogin(next.launchAtLogin)
            val loweringShield = before.killSwitch && !next.killSwitch
            desktop.update { next }
            if (!next.connectOnLaunch) startupConnectPending = false
            if ((reconnect || previous != currentReconnectKey(desktopSettings = desktop.value)) &&
                status.value.state in listOf("connected", "connecting")) applyConnectionSettings()
            // Switching the kill switch off while it holds a dead tunnel means "give me my
            // internet back", not "next time": the block comes down now.
            else if (loweringShield && status.value.killSwitch && status.value.state != "connecting") {
                updateStatus(backend.request(ServiceRequest(command = "disconnect")))
            }
        }
    }
    private suspend fun applyConnectionSettings() {
        if (status.value.state == "connected") {
            settingsReconnectPending = false
            cancelSpeed()
            reconnectActive()
        } else if (status.value.state == "connecting") {
            // The worker is still starting with the previous snapshot. Apply the latest saved
            // settings as soon as it acknowledges, including several changes made during startup.
            settingsReconnectPending = true
        }
    }
    private suspend inline fun <T> withBusy(block: () -> T): T {
        if (busyCount.incrementAndGet() > 0) busy.value = true
        try { return block() }
        finally { if (busyCount.decrementAndGet() == 0) busy.value = false }
    }

    private suspend fun importDns(dnsProfiles: List<DnsProfile>) {
        val first = dnsProfiles.firstOrNull() ?: error("No DNS profile")
        commands.withLock {
            val previous = currentReconnectKey()
            profiles.addDnsProfiles(dnsProfiles)
            profiles.selectDnsProfile(first.id)
            settings.update { it.copy(remoteDns = first.url) }
            if (previous != currentReconnectKey() &&
                status.value.state in listOf("connected", "connecting")
            ) {
                applyConnectionSettings()
            }
        }
    }

    fun addDnsProfile(raw: String): Boolean {
        val parsed = DnsUriParser.parse(raw) ?: return false
        action { importDns(listOf(parsed)) }
        return true
    }

    fun selectDnsProfile(id: String?) = action {
        commands.withLock {
            val previous = currentReconnectKey()
            profiles.selectDnsProfile(id)
            if (previous != currentReconnectKey() &&
                status.value.state in listOf("connected", "connecting")
            ) {
                applyConnectionSettings()
            }
        }
    }

    fun removeDnsProfile(id: String) = action {
        commands.withLock {
            val previous = currentReconnectKey()
            profiles.removeDnsProfile(id)
            if (previous != currentReconnectKey() &&
                status.value.state in listOf("connected", "connecting")
            ) {
                applyConnectionSettings()
            }
        }
    }

    fun addCustomRule(ruleAction: CustomRules.Action, raw: String): Boolean {
        val normalized = CustomRules.normalize(raw) ?: return false
        changeSettings({ it.copy(customRules = it.customRules.adding(ruleAction, normalized)) })
        return true
    }

    fun removeCustomRule(ruleAction: CustomRules.Action, entry: String) {
        changeSettings({ it.copy(customRules = it.customRules.removing(ruleAction, entry)) })
    }

    fun importText(raw: String) = action {
        val value = raw.trim()
        require(value.isNotEmpty())
        when (val deepLink = DeepLinkParser.parse(value)) {
            is DeepLinkPayload.AddSubscription -> {
                val transfer = SubscriptionTransfer(
                    name = deepLink.name ?: text("Подписка", "Subscription"),
                    url = deepLink.url,
                )
                val validated = requireNotNull(transfer.validate())
                val subscription = profiles.upsertSubscriptionSource(validated.name, validated.url, null, emptyMap())
                refresh(subscription, notifyOnFailure = true)
            }
            is DeepLinkPayload.AddNodes -> {
                val supported = deepLink.nodes.mapNotNull(XrayConfigFactory::carriable)
                require(supported.isNotEmpty())
                profiles.addNodes(supported.map { it.copy(subscriptionId = null) })
            }
            is DeepLinkPayload.AddDns -> importDns(deepLink.profiles)
            is DeepLinkPayload.BillingReturn -> {
                val subs = profiles.state.value.subscriptions
                val targets = subs.firstOrNull { it.id == deepLink.subscriptionId }?.let(::listOf)
                    ?: subs.filter { it.enabled }
                targets.forEach { refresh(it) }
                if (targets.isNotEmpty()) message.value = strings.get(R.string.billing_renewed_ok)
            }
            is DeepLinkPayload.Unsupported -> {
                val parsed = SubscriptionParser.parse(value, "manual")
                if (parsed is SubscriptionBody.Nodes) {
                    val supported = parsed.nodes.mapNotNull(XrayConfigFactory::carriable)
                    require(supported.isNotEmpty())
                    profiles.addNodes(supported.map { it.copy(subscriptionId = null) })
                } else {
                    val dns = DnsUriParser.parseAll(value)
                    if (dns.isNotEmpty()) importDns(dns)
                    else error("Unsupported import payload")
                }
            }
        }
    }

    suspend fun refresh(subscription: Subscription, notifyOnFailure: Boolean = false) = subscriptions.withLock {
        withBusy {
            when (val result = subscriptionService.fetch(subscription)) {
                is SubscriptionUpdate.Success -> if (profiles.state.value.subscriptions.any { it.id == subscription.id }) {
                    profiles.applySubscriptionUpdate(
                        subscriptionId = subscription.id,
                        fetchedNodes = result.nodes,
                        userInfo = result.subscription.userInfo,
                        remoteTitle = result.subscription.remoteTitle,
                        webPageUrl = result.subscription.webPageUrl,
                        providerRouting = result.providerRouting,
                        billingManifestUrl = result.subscription.billingManifestUrl,
                        paymentUrl = result.subscription.paymentUrl,
                        supportUrl = result.subscription.supportUrl,
                    )
                }
                is SubscriptionUpdate.Failure -> {
                    val errorText = result.message.ifBlank { text("Не удалось обновить подписку", "Subscription refresh failed") }
                    profiles.recordSubscriptionError(subscription.id, errorText, result.metadata)
                    if (notifyOnFailure) message.value = errorText
                }
            }
        }
    }

    fun openSubscriptionBilling(subscriptionId: String) {
        val sub = profiles.state.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        when {
            sub.billingManifestUrl != null -> {
                billingSheet.value = BillingDialogState(subscription = sub, loading = true)
                action { loadBillingManifest(sub) }
            }
            sub.paymentUrl != null -> {
                val safe = sanitizeBillingUrl(sub.paymentUrl)
                if (safe == null) {
                    message.value = strings.get(R.string.billing_error_unsafe_url)
                } else if (!openExternalUrl(safe)) {
                    message.value = strings.get(R.string.billing_error_browser_missing)
                }
            }
            sub.webPageUrl != null -> openSubscriptionPortal(subscriptionId)
        }
    }

    fun openSubscriptionPortal(subscriptionId: String) {
        val sub = profiles.state.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        openExternalBillingUrl(sub.webPageUrl.orEmpty())
    }

    fun openSubscriptionSupport(subscriptionId: String) {
        val sub = profiles.state.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        openExternalBillingUrl(sub.supportUrl.orEmpty())
    }

    fun openExternalBillingUrl(url: String) {
        val safe = sanitizePortalUrl(url)
        if (safe == null) {
            message.value = strings.get(R.string.billing_error_unsafe_url)
            return
        }
        billingSheet.value = null
        if (!openExternalUrl(safe)) {
            message.value = strings.get(R.string.billing_error_browser_missing)
        }
    }

    fun retryBillingManifest() {
        val sub = billingSheet.value?.subscription ?: return
        billingSheet.value = BillingDialogState(subscription = sub, loading = true)
        action { loadBillingManifest(sub) }
    }

    fun dismissBillingSheet() {
        billingSheet.value = null
    }

    fun clearBillingCheckoutError() {
        billingSheet.update { it?.copy(checkoutError = null) }
    }

    fun submitBillingCheckout(
        planId: String?,
        addonId: String?,
        paymentMethod: String,
        promoCode: String?,
    ) {
        val current = billingSheet.value ?: return
        val endpoint = current.manifest?.checkout?.endpoint
        if (endpoint.isNullOrBlank()) {
            billingSheet.update {
                it?.copy(checkoutError = strings.get(R.string.billing_error_no_checkout))
            }
            return
        }
        billingSheet.update { it?.copy(submitting = true, checkoutError = null) }
        action {
            val request = BillingCheckoutRequest(
                planId = planId,
                addonId = addonId,
                paymentMethod = paymentMethod,
                promoCode = promoCode?.trim()?.takeIf { it.isNotEmpty() },
                returnUrl = "yumi://billing/return?sub_id=${current.subscription.id}",
            )
            when (
                val result = billingService.createCheckout(
                    endpoint = endpoint,
                    request = request,
                    acceptLanguage = Locale.getDefault().toLanguageTag(),
                )
            ) {
                is BillingResult.Success -> {
                    billingSheet.value = null
                    if (!openExternalUrl(result.value.paymentUrl)) {
                        message.value = strings.get(R.string.billing_error_browser_missing)
                    }
                }
                is BillingResult.Failure -> {
                    billingSheet.update {
                        if (it?.subscription?.id == current.subscription.id) {
                            it.copy(submitting = false, checkoutError = result.message)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadBillingManifest(sub: Subscription) {
        val url = sub.billingManifestUrl ?: return
        val result = billingService.fetchManifest(
            manifestUrl = url,
            acceptLanguage = Locale.getDefault().toLanguageTag(),
        )
        billingSheet.update { state ->
            if (state?.subscription?.id != sub.id) {
                state
            } else {
                when (result) {
                    is BillingResult.Success -> state.copy(loading = false, manifest = result.value, loadError = null)
                    is BillingResult.Failure -> state.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun refreshAll() = action {
        withBusy {
            profiles.state.value.subscriptions.filter { it.enabled }.forEach { refresh(it) }
        }
    }

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
    fun repairService() = action { commands.withLock { prepareService() } }

    private suspend fun prepareService(): Boolean {
        serviceInstalling.value = true
        try {
            when (serviceInstaller?.invoke() ?: DesktopPlatform.repairService()) {
                ServiceRepair.Done -> {
                    repeat(60) {
                        updateStatus(backend.request(ServiceRequest(command = "status")))
                        if (status.value.state != "unavailable") return true
                        delay(250)
                    }
                    message.value = text("Служба установлена, но не ответила. Повторите подключение.", "The service was installed but did not respond. Retry the connection.")
                }
                ServiceRepair.Declined -> Unit
                ServiceRepair.Unavailable -> message.value = text(
                    "Не удалось установить или запустить службу Yumi.",
                    "Could not install or start the Yumi service.",
                )
            }
            return false
        } finally { serviceInstalling.value = false }
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
    private suspend fun disconnectIfMatches(matches: (ProxyNode) -> Boolean) {
        val active = activeNode.value
        val saved = connection.value.node
        if (((active != null && matches(active)) || (saved != null && matches(saved))) &&
            (status.value.state in listOf("connecting", "connected", "disconnecting") || status.value.killSwitch)
        ) {
            cancelSpeed()
            updateStatus(backend.request(ServiceRequest(command = "disconnect")))
        }
    }

    fun removeSubscription(id: String) = action {
        commands.withLock {
            disconnectIfMatches { it.subscriptionId == id }
            profiles.removeSubscription(id)
            if (connection.value.node?.subscriptionId == id) {
                connection.update { ActiveConnection() }
            }
        }
    }

    fun setSubscriptionEnabled(id: String, enabled: Boolean) = action {
        commands.withLock {
            if (!enabled) {
                disconnectIfMatches { it.subscriptionId == id }
                if (connection.value.node?.subscriptionId == id) {
                    connection.update { ActiveConnection() }
                }
            }
            profiles.setSubscriptionEnabled(id, enabled)
        }
    }

    fun removeNode(id: String) = action {
        commands.withLock {
            disconnectIfMatches { it.id == id }
            profiles.removeNode(id)
            if (connection.value.node?.id == id) {
                connection.update { ActiveConnection() }
            }
        }
    }

    fun filteredServers(
        nodes: List<ProxyNode> = profiles.nodes,
        query: String = serverQuery.value,
        appSettings: AppSettings = settings.value,
        latencies: Map<String, LatencyResult> = profiles.state.value.latencies,
    ): List<ProxyNode> {
        val facetFiltered = ServerFilter.filterNodes(nodes, appSettings)
        val needle = query.trim()
        val queried = if (needle.isEmpty()) {
            facetFiltered
        } else {
            facetFiltered.filter {
                it.name.contains(needle, ignoreCase = true) ||
                    it.server.contains(needle, ignoreCase = true) ||
                    it.protocol.label.contains(needle, ignoreCase = true)
            }
        }
        return when (appSettings.serverSort) {
            ServerSort.Default -> queried
            ServerSort.Name -> queried.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ServerSort.Protocol -> queried.sortedBy { it.protocol.label }
            ServerSort.Latency -> queried.sortedBy { node ->
                val result = latencies[node.id]
                when {
                    result == null -> Int.MAX_VALUE - 1
                    result.failed -> Int.MAX_VALUE
                    else -> result.millis
                }
            }
        }
    }

    /** collectLatest cancels and joins the previous sweep before starting this one. */
    fun matchingServers(): List<ProxyNode> = filteredServers()

    fun searchServers(query: String) {
        serverQuery.value = query
        if (pinging.value) ping()
    }

    fun toggleProtocolFilter(protocol: Protocol) {
        settings.update { current ->
            val resolved = ServerFilter.resolveProtocols(profiles.nodes, current.serverFilterProtocols)
            val next = if (protocol in resolved) resolved - protocol else resolved + protocol
            current.copy(serverFilterProtocols = next)
        }
        if (pinging.value) ping()
    }

    fun toggleCountryFilter(code: String) {
        val clean = code.trim().uppercase()
        settings.update { current ->
            val resolved = ServerFilter.resolveCountries(profiles.nodes, current.serverFilterCountries)
            val next = if (clean in resolved) resolved - clean else resolved + clean
            current.copy(serverFilterCountries = next)
        }
        if (pinging.value) ping()
    }

    fun setCountryFilterMode(mode: CountryFilterMode) {
        settings.update { it.copy(serverFilterCountryMode = mode) }
        if (pinging.value) ping()
    }

    fun setServerSort(sort: ServerSort) {
        settings.update { it.copy(serverSort = sort) }
    }

    fun resetServerFilters() {
        val hadFacet = settings.value.serverFilterProtocols.isNotEmpty() || settings.value.serverFilterCountries.isNotEmpty()
        settings.update {
            it.copy(
                serverFilterProtocols = emptySet(),
                serverFilterCountries = emptySet(),
                serverFilterCountryMode = CountryFilterMode.Include,
                serverSort = ServerSort.Default,
            )
        }
        if (hadFacet && pinging.value) ping()
    }

    fun selectFastestServer() {
        val candidates = filteredServers(query = "")
        if (profiles.nodes.isNotEmpty() && candidates.isEmpty()) {
            message.value = text("По активным фильтрам серверы не найдены.", "No servers match the active filters.")
            return
        }
        val latencies = profiles.state.value.latencies
        val fastest = candidates.minByOrNull { node ->
            val lr = latencies[node.id]
            if (lr == null || lr.failed || lr.millis <= 0) Int.MAX_VALUE else lr.millis
        }
        val bestResult = fastest?.let { latencies[it.id] }
        if (fastest == null || bestResult == null || bestResult.failed || bestResult.millis <= 0) {
            message.value = strings.get(R.string.message_measure_first)
            return
        }
        selectNode(fastest.id)
    }

    fun pingNode(id: String) {
        val node = profiles.nodes.firstOrNull { it.id == id } ?: return
        pingRequests.update { PingRequest(it.sequence + 1, listOf(node)) }
    }

    fun ping() { val nodes = matchingServers(); pingRequests.update { PingRequest(it.sequence + 1, nodes) } }
    fun pingAll() { val nodes = profiles.nodes; pingRequests.update { PingRequest(it.sequence + 1, nodes) } }

    fun refreshServiceLogs(): Job {
        serviceLogsRefreshing.value = true
        return scope.launch(Dispatchers.IO) {
            try {
                val reply = runCatching { backend.request(ServiceRequest(command = "logs")) }.getOrNull()
                if (reply?.logs != null) {
                    serviceLogs.value = reply.logs.orEmpty()
                }
            } finally {
                serviceLogsRefreshing.value = false
            }
        }
    }

    private suspend fun measureServers(nodes: List<ProxyNode>) = coroutineScope {
        val pending = java.util.concurrent.ConcurrentLinkedQueue<LatencyResult>()
        fun flush() {
            val batch = buildList { while (true) add(pending.poll() ?: break) }
            if (batch.isNotEmpty()) {
                val ids = profiles.allNodes.mapTo(HashSet()) { it.id }
                profiles.recordLatencies(batch.filter { it.nodeId in ids })
            }
        }
        val writer = launch { while (isActive) { delay(250); flush() } }
        val mode = settings.value.pingMode
        // A bounded worker pool also avoids creating thousands of waiting coroutines.
        val remaining = java.util.concurrent.ConcurrentLinkedQueue(nodes)
        try {
            if (measureLatency == null && backend !is DemoBackend) {
                latencyTester.measureAll(nodes, mode) { result -> pending.add(result) }
            } else {
                List(minOf(16, nodes.size)) {
                    launch {
                        while (isActive) {
                            val node = remaining.poll() ?: break
                            val result = probeSingle(node, mode)
                            ensureActive()
                            pending.add(result)
                        }
                    }
                }.joinAll()
            }
            writer.cancelAndJoin()
            ensureActive()
            flush()
        } finally {
            writer.cancel()
            // Cancelled sweeps deliberately discard their unfinished batch.
        }
    }
    fun startPairing() {
        pairingStartJob?.cancel()
        pairingStartJob = action {
            val found = withContext(Dispatchers.IO) { DesktopPlatform.localAddresses() }
            localAddresses.value = found
            if (selectedAddress.value !in found) selectedAddress.value = found.firstOrNull()
            currentCoroutineContext().ensureActive()
            pairing.start("Yumi Desktop")
        }
    }
    fun stopPairing() {
        pairingStartJob?.cancel()
        pairingStartJob = null
        pairing.stop()
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
        synchronized(speedLock) {
            speedJob?.cancel()
            speed.value = SpeedTestState()
            val current = status.value
            if (current.state in listOf("connecting", "disconnecting")) {
                speedJob = null
                return
            }
            val probe = current.probe()
            speedJob = action {
                val myJob = currentCoroutineContext()[Job]
                var live = emptyList<Long>()
                var download = emptyList<Long>()
                var upload = emptyList<Long>()
                var phase = SpeedPhase.Idle
                SpeedTester(logs, strings).measure(probe, activeNode.value?.name).collect { next ->
                    currentCoroutineContext().ensureActive()
                    if (speedJob !== myJob || status.value.state in listOf("connecting", "disconnecting")) return@collect
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
                    synchronized(speedLock) {
                        if (speedJob === myJob) {
                            speed.value = next.copy(series = live, downloadSeries = download, uploadSeries = upload)
                        }
                    }
                }
            }
        }
    }
    fun cancelSpeed() {
        synchronized(speedLock) {
            speedJob?.cancel()
            speedJob = null
            speed.value = SpeedTestState()
        }
    }
    suspend fun close() {
        pairingStartJob?.cancel()
        runCatching { pairing.stop() }
        cancelSpeed()
        statusJob.cancel()
        refreshJob.cancel()
        pingJob.cancel()
        connectionProbe?.cancel()
        tunnelMonitor?.cancel()
        try {
            withTimeoutOrNull(5_000) {
                commands.withLock {
                    if (status.value.state in listOf("connected", "connecting", "disconnecting") || status.value.killSwitch) {
                        updateStatus(backend.request(ServiceRequest(command = "disconnect")))
                    }
                }
            }
        } finally {
            runCatching { connection.update { ActiveConnection() } }
            activeNode.value = null
            requestedNode = null
            scope.cancel()
        }
    }

    private companion object {
        const val CONNECTION_CHECK_MILLIS = 15_000L
        /** How stale a list has to be for opening the window to be a reason to refresh it. */
        const val STARTUP_FLOOR_MILLIS = 2 * 60_000L
        val ProbeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val DefaultProbe = TunnelHealthProbe()
        val DefaultCheckTunnel: suspend (ProbeEndpoint) -> Boolean = { DefaultProbe.reachable(it) }
    }
}

internal fun ServiceStatus.forUi(): ServiceStatus = copy(
    upload = 0,
    download = 0,
    geoUpdatedAt = if (geoUpdatedAt > 0) geoUpdatedAt / 60_000 * 60_000 else geoUpdatedAt,
)
