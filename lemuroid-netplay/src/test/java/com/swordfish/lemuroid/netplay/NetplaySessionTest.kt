package com.swordfish.lemuroid.netplay

import com.swordfish.lemuroid.netplay.protocol.MessageCodec
import com.swordfish.lemuroid.netplay.protocol.MismatchReason
import com.swordfish.lemuroid.netplay.protocol.NetplayMessage
import com.swordfish.lemuroid.netplay.protocol.PROTOCOL_VERSION
import com.swordfish.lemuroid.netplay.protocol.PeerRole
import com.swordfish.lemuroid.netplay.rom.GameFingerprint
import com.swordfish.lemuroid.netplay.session.CloseReason
import com.swordfish.lemuroid.netplay.session.NetplaySession
import com.swordfish.lemuroid.netplay.session.SessionError
import com.swordfish.lemuroid.netplay.session.SessionState
import com.swordfish.lemuroid.netplay.transport.Connection
import com.swordfish.lemuroid.netplay.transport.TcpClientProvider
import com.swordfish.lemuroid.netplay.transport.TcpHostProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class NetplaySessionTest {
    private val game = GameFingerprint("snes", 1_048_576L, "ab".repeat(32))

    // Short timings keep the tests that wait for a timeout fast.
    private val fastTiming =
        NetplaySession.Timing(
            handshakeTimeoutMs = 400,
            pingIntervalMs = 50,
            peerSilenceTimeoutMs = 400,
        )

    private class Recorder {
        private val states = CopyOnWriteArrayList<SessionState>()

        val listener: (SessionState) -> Unit = { states.add(it) }

        val seen: List<SessionState> get() = states.toList()

        fun await(
            timeoutMs: Long = 5_000,
            predicate: (SessionState) -> Boolean,
        ): SessionState {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                states.firstOrNull(predicate)?.let { return it }
                Thread.sleep(10)
            }
            throw AssertionError("Timed out waiting for a state. States seen: $states")
        }
    }

    /** A host and a guest talking to each other over the loopback interface. */
    private inner class SessionPair(
        hostGame: GameFingerprint = game,
        guestGame: GameFingerprint = game,
        timing: NetplaySession.Timing = NetplaySession.Timing(),
    ) {
        val hostStates = Recorder()
        val guestStates = Recorder()

        private val hostProvider = TcpHostProvider(0)
        val port = hostProvider.bind()

        val host = NetplaySession(hostProvider, PeerRole.HOST, "Host", hostGame, timing, hostStates.listener)
        val guest =
            NetplaySession(
                TcpClientProvider("127.0.0.1", port),
                PeerRole.GUEST,
                "Guest",
                guestGame,
                timing,
                guestStates.listener,
            )

        fun start() {
            host.start()
            guest.start()
        }

        fun close() {
            host.close()
            guest.close()
        }
    }

    private fun withPair(
        hostGame: GameFingerprint = game,
        guestGame: GameFingerprint = game,
        timing: NetplaySession.Timing = NetplaySession.Timing(),
        block: (SessionPair) -> Unit,
    ) {
        val pair = SessionPair(hostGame, guestGame, timing)
        try {
            pair.start()
            block(pair)
        } finally {
            pair.close()
        }
    }

    /** A hand-made guest, to test how a session reacts to peers that don't behave. */
    private fun rawConnection(port: Int): Connection = TcpClientProvider("127.0.0.1", port).open()

    private fun Connection.send(message: NetplayMessage) = MessageCodec.write(output, message)

    private fun Connection.readUntil(predicate: (NetplayMessage) -> Boolean): NetplayMessage {
        while (true) {
            val message = MessageCodec.read(input) ?: throw AssertionError("Connection closed unexpectedly")
            if (predicate(message)) return message
        }
    }

    /** Completes a correct handshake by hand and leaves the connection open. */
    private fun rawHandshake(port: Int): Connection {
        val connection = rawConnection(port)
        connection.send(NetplayMessage.Hello(PROTOCOL_VERSION, "Raw", PeerRole.GUEST))
        connection.send(NetplayMessage.GameInfo(game))
        connection.readUntil { it == NetplayMessage.Ready }
        connection.send(NetplayMessage.Ready)
        return connection
    }

    private fun startHostAlone(
        timing: NetplaySession.Timing,
        recorder: Recorder,
    ): Pair<NetplaySession, Int> {
        val provider = TcpHostProvider(0)
        val port = provider.bind()
        val session = NetplaySession(provider, PeerRole.HOST, "Host", game, timing, recorder.listener)
        session.start()
        return Pair(session, port)
    }

    @Test
    fun matchingGamesBecomeReadyOnBothSides() {
        withPair { pair ->
            val hostReady = pair.hostStates.await { it is SessionState.Ready } as SessionState.Ready
            val guestReady = pair.guestStates.await { it is SessionState.Ready } as SessionState.Ready

            assertEquals("Guest", hostReady.peerName)
            assertEquals("Host", guestReady.peerName)
        }
    }

    @Test
    fun latencyIsMeasuredOnceReady() {
        withPair { pair ->
            val host =
                pair.hostStates.await { it is SessionState.Ready && it.roundTripMillis != null } as SessionState.Ready
            val guest =
                pair.guestStates.await { it is SessionState.Ready && it.roundTripMillis != null } as SessionState.Ready

            assertTrue("Loopback latency should be tiny: ${host.roundTripMillis}", host.roundTripMillis!! < 1_000)
            assertTrue("Loopback latency should be tiny: ${guest.roundTripMillis}", guest.roundTripMillis!! < 1_000)
        }
    }

    @Test
    fun differentRomContentIsRejectedOnBothSidesAndNeverGetsReady() {
        val other = game.copy(sha256 = "cd".repeat(32))

        withPair(hostGame = game, guestGame = other) { pair ->
            val host = pair.hostStates.await { it is SessionState.Mismatch } as SessionState.Mismatch
            val guest = pair.guestStates.await { it is SessionState.Mismatch } as SessionState.Mismatch

            assertEquals(MismatchReason.ROM_CONTENT, host.reason)
            assertEquals(MismatchReason.ROM_CONTENT, guest.reason)
            assertEquals(other, host.peerGame)
            assertEquals(game, guest.peerGame)
            assertFalse(pair.hostStates.seen.any { it is SessionState.Ready })
            assertFalse(pair.guestStates.seen.any { it is SessionState.Ready })
        }
    }

    @Test
    fun differentConsolesAreRejected() {
        withPair(guestGame = game.copy(systemId = "nes")) { pair ->
            val host = pair.hostStates.await { it is SessionState.Mismatch } as SessionState.Mismatch
            val guest = pair.guestStates.await { it is SessionState.Mismatch } as SessionState.Mismatch

            assertEquals(MismatchReason.SYSTEM, host.reason)
            assertEquals(MismatchReason.SYSTEM, guest.reason)
        }
    }

    @Test
    fun otherProtocolVersionsAreRejectedAndTheOtherSideIsTold() {
        val recorder = Recorder()
        val (session, port) = startHostAlone(NetplaySession.Timing(), recorder)
        try {
            val raw = rawConnection(port)
            raw.send(NetplayMessage.Hello(PROTOCOL_VERSION + 1, "FromTheFuture", PeerRole.GUEST))

            val told = raw.readUntil { it is NetplayMessage.Mismatch } as NetplayMessage.Mismatch
            assertEquals(MismatchReason.PROTOCOL_VERSION, told.reason)

            val state = recorder.await { it is SessionState.Mismatch } as SessionState.Mismatch
            assertEquals(MismatchReason.PROTOCOL_VERSION, state.reason)
            assertFalse(state.detectedByPeer)
            assertNull(state.peerGame)
            raw.close()
        } finally {
            session.close()
        }
    }

    @Test
    fun leavingOnPurposeIsReportedAsPeerLeft() {
        withPair { pair ->
            pair.hostStates.await { it is SessionState.Ready }
            pair.guestStates.await { it is SessionState.Ready }

            pair.guest.close()

            val host = pair.hostStates.await { it.isTerminal }
            val guest = pair.guestStates.await { it.isTerminal }
            assertEquals(SessionState.Closed(CloseReason.PEER_LEFT), host)
            assertEquals(SessionState.Closed(CloseReason.LOCAL), guest)
        }
    }

    @Test
    fun droppingTheConnectionIsReportedAsConnectionLost() {
        val recorder = Recorder()
        val (session, port) = startHostAlone(NetplaySession.Timing(), recorder)
        try {
            val raw = rawHandshake(port)
            recorder.await { it is SessionState.Ready }

            raw.close() // No goodbye: like walking out of Wi-Fi range.

            val state = recorder.await { it.isTerminal } as SessionState.Failed
            assertEquals(SessionError.CONNECTION_LOST, state.error)
        } finally {
            session.close()
        }
    }

    @Test
    fun aPeerThatStopsAnsweringIsDropped() {
        val recorder = Recorder()
        val (session, port) = startHostAlone(fastTiming, recorder)
        try {
            val raw = rawHandshake(port) // Handshakes properly, then ignores every ping.
            recorder.await { it is SessionState.Ready }

            val state = recorder.await { it.isTerminal } as SessionState.Failed
            assertEquals(SessionError.CONNECTION_LOST, state.error)
            raw.close()
        } finally {
            session.close()
        }
    }

    @Test
    fun aPeerThatNeverCompletesTheHandshakeTimesOut() {
        val recorder = Recorder()
        val (session, port) = startHostAlone(fastTiming, recorder)
        try {
            val raw = rawConnection(port) // Connects, then stays silent.

            val state = recorder.await { it.isTerminal } as SessionState.Failed
            assertEquals(SessionError.TIMEOUT, state.error)
            raw.close()
        } finally {
            session.close()
        }
    }

    @Test
    fun garbageFromThePeerIsAProtocolError() {
        val recorder = Recorder()
        val (session, port) = startHostAlone(NetplaySession.Timing(), recorder)
        try {
            val raw = rawConnection(port)
            raw.output.write(ByteArray(16) { 0xFF.toByte() })
            raw.output.flush()

            val state = recorder.await { it.isTerminal } as SessionState.Failed
            assertEquals(SessionError.PROTOCOL_ERROR, state.error)
            raw.close()
        } finally {
            session.close()
        }
    }

    @Test
    fun messagesOutOfOrderAreAProtocolError() {
        val recorder = Recorder()
        val (session, port) = startHostAlone(NetplaySession.Timing(), recorder)
        try {
            val raw = rawConnection(port)
            raw.send(NetplayMessage.GameInfo(game)) // The hello is mandatory and comes first.

            val state = recorder.await { it.isTerminal } as SessionState.Failed
            assertEquals(SessionError.PROTOCOL_ERROR, state.error)
            raw.close()
        } finally {
            session.close()
        }
    }

    @Test
    fun cancellingAHostThatIsStillWaitingStopsItAndFreesThePort() {
        val recorder = Recorder()
        val (session, port) = startHostAlone(NetplaySession.Timing(), recorder)

        recorder.await { it is SessionState.Connecting }
        session.close()

        assertEquals(SessionState.Closed(CloseReason.LOCAL), recorder.await { it.isTerminal })

        // The port must be usable again, which proves the blocked accept() was really interrupted.
        val deadline = System.currentTimeMillis() + 3_000
        var rebound = false
        while (!rebound && System.currentTimeMillis() < deadline) {
            try {
                ServerSocket(port).close()
                rebound = true
            } catch (e: IOException) {
                Thread.sleep(20)
            }
        }
        assertTrue("The port was not released", rebound)
    }

    @Test
    fun aGuestThatCantReachTheHostFails() {
        val freePort = ServerSocket(0).use { it.localPort } // Nobody is listening there anymore.
        val recorder = Recorder()
        val session =
            NetplaySession(
                TcpClientProvider("127.0.0.1", freePort, connectTimeoutMs = 1_000),
                PeerRole.GUEST,
                "Guest",
                game,
                NetplaySession.Timing(),
                recorder.listener,
            )

        try {
            session.start()
            val state = recorder.await { it.isTerminal } as SessionState.Failed
            assertEquals(SessionError.CONNECT_FAILED, state.error)
        } finally {
            session.close()
        }
    }

    @Test
    fun theFailureReasonSurvivesAfterClosingTheSession() {
        val other = game.copy(sha256 = "cd".repeat(32))

        withPair(guestGame = other) { pair ->
            pair.hostStates.await { it is SessionState.Mismatch }
            pair.host.close()
            Thread.sleep(100)

            assertTrue(pair.host.currentState is SessionState.Mismatch)
            assertFalse(pair.hostStates.seen.any { it is SessionState.Closed })
        }
    }

    @Test
    fun aSessionCanOnlyBeStartedOnce() {
        val pair = SessionPair()
        try {
            pair.host.start()
            try {
                pair.host.start()
                fail("Expected an IllegalStateException")
            } catch (e: IllegalStateException) {
                // Expected.
            }
        } finally {
            pair.close()
        }
    }

    @Test
    fun aSessionClosedBeforeStartingNeverConnects() {
        val pair = SessionPair()
        pair.host.close()
        pair.host.start()
        Thread.sleep(200)

        assertEquals(listOf<SessionState>(SessionState.Closed(CloseReason.LOCAL)), pair.hostStates.seen)
        pair.close()
    }
}
