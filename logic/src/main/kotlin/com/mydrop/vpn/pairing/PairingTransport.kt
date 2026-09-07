package com.mydrop.vpn.pairing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString

sealed interface PairingReceiverState {
    data object Idle : PairingReceiverState
    data class Waiting(val invite: PairingInvite, val expiresAtMillis: Long) : PairingReceiverState
    data object Receiving : PairingReceiverState
    data class Complete(val result: PairingResult) : PairingReceiverState
    data class Failed(val message: String) : PairingReceiverState
}

/** One-screen, one-use receiver. Closing the QR screen closes its listening socket. */
class PairingReceiver(
    private val addressProvider: () -> Inet4Address?,
    private val scope: CoroutineScope,
    private val onTransfer: suspend (SubscriptionTransfer) -> PairingResult,
) {
    private val _state = MutableStateFlow<PairingReceiverState>(PairingReceiverState.Idle)
    val state: StateFlow<PairingReceiverState> = _state.asStateFlow()

    @Volatile private var generation = 0L
    private var job: Job? = null
    private var socket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null

    fun start(deviceName: String) {
        stop()
        val mine = generation
        fun publish(state: PairingReceiverState) { if (generation == mine) _state.value = state }
        job = scope.launch(Dispatchers.IO) {
            val address = addressProvider()
            if (address == null) {
                publish(PairingReceiverState.Failed("No local Wi-Fi or Ethernet address"))
                return@launch
            }
            val keys = PairingCrypto.keyPair()
            val sessionId = PairingCrypto.sessionId()
            val started = System.currentTimeMillis()
            val expires = started + PAIRING_LIFETIME_MILLIS
            val session = PairingSessionGuard(sessionId, expires)
            val server = try { ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(address, 0), 1)
                soTimeout = 1_000
            } } catch (_: Exception) { publish(PairingReceiverState.Failed("Cannot open local receiver")); return@launch }
            if (generation != mine) { server.close(); return@launch }
            socket = server
            val invite = PairingInvite(
                version = VERSION,
                host = address.hostAddress.orEmpty(),
                port = server.localPort,
                sessionId = sessionId,
                receiverPublicKey = PairingCrypto.publicKey(keys.public),
                deviceName = deviceName.take(96),
            )
            publish(PairingReceiverState.Waiting(invite, expires))

            try {
                while (isActive && session.isCurrent(sessionId)) {
                    val client = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: SocketException) {
                        // How stop() reaches this thread. accept() blocks with no suspension
                        // point for a cancel to land on, so closing the socket under it is the
                        // only way to wake it, and the wake arrives as an exception rather than
                        // as cancellation. Uncaught it went to the thread's default handler and
                        // took the whole app down: leaving the pairing screen was enough, which
                        // is every single time somebody finishes with it.
                        //
                        // A close while the job is still active is a real fault instead - the
                        // Wi-Fi interface going away, say - and the screen offers a retry.
                        if (isActive) publish(PairingReceiverState.Failed("Pairing socket closed"))
                        return@launch
                    }
                    client.use {
                        activeClient = client
                        if (!isActive || generation != mine) return@launch
                        it.soTimeout = 8_000
                        val output = DataOutputStream(it.getOutputStream())
                        val outcome = runCatching {
                            val bytes = DataInputStream(it.getInputStream()).readFrame()
                            val envelope = PairingCrypto.json.decodeFromString<RequestEnvelope>(bytes.decodeToString())
                            require(session.isCurrent(envelope.sessionId)) { "Expired or invalid pairing session" }
                            val (transfer, key) = PairingCrypto.openRequest(envelope, keys)
                            val valid = requireNotNull(transfer.validate())
                            check(isActive && generation == mine)
                            check(session.claim()) { "Pairing session was already used" }
                            publish(PairingReceiverState.Receiving)
                            val result = onTransfer(valid)
                            val reply = PairingCrypto.reply(result, key, sessionId)
                            output.writeFrame(PairingCrypto.json.encodeToString(reply).toByteArray())
                            output.flush()
                            result
                        }
                        outcome.onSuccess { publish(PairingReceiverState.Complete(it)) }
                        outcome.onFailure {
                            publish(PairingReceiverState.Failed("Transfer failed"))
                        }
                        if (session.isUsed) break
                    }
                }
                if (!session.isUsed && _state.value !is PairingReceiverState.Failed) {
                    publish(PairingReceiverState.Failed("Pairing code expired"))
                }
            } finally {
                server.close()
                if (generation == mine) activeClient = null
                if (socket === server) socket = null
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        runCatching { activeClient?.close() }
        activeClient = null
        socket = null
        _state.value = PairingReceiverState.Idle
    }
}

/** Enforces the QR's session id, five-minute lifetime and exactly-once consumption. */
internal class PairingSessionGuard(
    private val sessionId: String,
    private val expiresAtMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val used = java.util.concurrent.atomic.AtomicBoolean(false)

    val isUsed: Boolean get() = used.get()

    fun isCurrent(candidateSessionId: String): Boolean =
        candidateSessionId == sessionId && now() < expiresAtMillis && !isUsed

    fun claim(): Boolean = now() < expiresAtMillis && used.compareAndSet(false, true)
}

/** Sender used by the existing phone QR scanner after the user chooses a subscription. */
class PairingClient {
    suspend fun send(invite: PairingInvite, transfer: SubscriptionTransfer): PairingResult =
        withContext(Dispatchers.IO) {
            requireNotNull(transfer.validate())
            require(isPrivateIpv4(invite.host)) { "The TV address is not on a private local network" }
            val (request, key) = PairingCrypto.request(invite, transfer)
            val bytes = PairingCrypto.json.encodeToString(request).toByteArray()
            require(bytes.size <= MAX_FRAME_BYTES)

            var lastError: Throwable? = null
            for (factory in listOf(SocketFactory.getDefault())) {
                val result = runCatching {
                    withTimeout(10_000) {
                        val socket = factory.createSocket() as Socket
                        socket.use {
                            it.connect(InetSocketAddress(invite.host, invite.port), 5_000)
                            it.soTimeout = 8_000
                            val output = DataOutputStream(it.getOutputStream())
                            output.writeFrame(bytes)
                            output.flush()
                            val replyBytes = DataInputStream(it.getInputStream()).readFrame()
                            val reply = PairingCrypto.json.decodeFromString<ReplyEnvelope>(replyBytes.decodeToString())
                            PairingCrypto.openReply(reply, key, invite.sessionId)
                        }
                    }
                }
                result.getOrNull()?.let { return@withContext it }
                lastError = result.exceptionOrNull()
            }
            throw lastError ?: IllegalStateException("TV is not reachable")
        }
}

private fun DataInputStream.readFrame(): ByteArray {
    val size = readInt()
    require(size in 1..MAX_FRAME_BYTES) { "Invalid pairing frame size" }
    return ByteArray(size).also(::readFully)
}

private fun DataOutputStream.writeFrame(bytes: ByteArray) {
    require(bytes.size in 1..MAX_FRAME_BYTES)
    writeInt(bytes.size)
    write(bytes)
}

private fun isPrivateIpv4(host: String): Boolean = runCatching {
    val address = InetAddress.getByName(host)
    address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress
}.getOrDefault(false)
