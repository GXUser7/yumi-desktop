package com.mydrop.vpn.pairing

import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DesktopReceiverTest {
    @Test fun encryptedFramesRoundTripOverLoopback(): Unit = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var received: SubscriptionTransfer?=null
        val receiver=PairingReceiver({InetAddress.getByName("127.0.0.1") as Inet4Address},scope){received=it;PairingResult(true,"accepted",it.name)}
        try {
            receiver.start("Desktop fixture")
            val waiting=withTimeout(3000){receiver.state.first{it is PairingReceiverState.Waiting}} as PairingReceiverState.Waiting
            val transfer=SubscriptionTransfer(name="Fixture",url="https://provider.example/sub/fixture")
            val(request,key)=PairingCrypto.request(waiting.invite,transfer)
            withContext(Dispatchers.IO){Socket("127.0.0.1",waiting.invite.port).use{socket->
                socket.soTimeout=3000
                val bytes=PairingCrypto.json.encodeToString(request).toByteArray()
                val output=DataOutputStream(socket.getOutputStream());output.writeInt(bytes.size)
                // Fragmented writes exercise framing independently of packet boundaries.
                bytes.asList().chunked(7).forEach{output.write(it.toByteArray());output.flush()}
                val input=DataInputStream(socket.getInputStream());val length=input.readInt()
                val reply=ByteArray(length);input.readFully(reply)
                val envelope=PairingCrypto.json.decodeFromString<ReplyEnvelope>(reply.decodeToString())
                assertEquals("accepted",PairingCrypto.openReply(envelope,key,waiting.invite.sessionId).status)
            }}
            assertEquals(transfer,received)
            withTimeout(3000){receiver.state.first{it is PairingReceiverState.Complete}}
        }finally{receiver.stop();scope.cancel()}
    }

    @Test fun stopClosesAnIncompleteClientAndNewSessionSurvives() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var calls=0
        val receiver=PairingReceiver({InetAddress.getByName("127.0.0.1") as Inet4Address},scope){calls++;PairingResult(true,"accepted")}
        try {
            receiver.start("First")
            val first=withTimeout(3000){receiver.state.first{it is PairingReceiverState.Waiting}} as PairingReceiverState.Waiting
            val socket=Socket("127.0.0.1",first.invite.port);socket.soTimeout=1000
            socket.getOutputStream().write(byteArrayOf(0,0));delay(100)
            receiver.stop();receiver.start("Second")
            val next=withTimeout(3000){receiver.state.first{it is PairingReceiverState.Waiting}} as PairingReceiverState.Waiting
            assertNotEquals(first.invite.sessionId,next.invite.sessionId)
            withContext(Dispatchers.IO){assertEquals(-1,socket.getInputStream().read())};socket.close()
            assertEquals(0,calls);assertTrue(receiver.state.value is PairingReceiverState.Waiting)
        }finally{receiver.stop();scope.cancel()}
    }
}
