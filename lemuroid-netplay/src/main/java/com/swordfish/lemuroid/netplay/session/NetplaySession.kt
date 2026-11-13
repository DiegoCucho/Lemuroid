package com.swordfish.lemuroid.netplay.session

import com.swordfish.lemuroid.netplay.protocol.MessageCodec
import com.swordfish.lemuroid.netplay.protocol.MismatchReason
import com.swordfish.lemuroid.netplay.protocol.NetplayMessage
import com.swordfish.lemuroid.netplay.protocol.PROTOCOL_VERSION
import com.swordfish.lemuroid.netplay.protocol.PeerRole
import com.swordfish.lemuroid.netplay.protocol.ProtocolException
import com.swordfish.lemuroid.netplay.rom.GameFingerprint
import com.swordfish.lemuroid.netplay.transport.Connection
import com.swordfish.lemuroid.netplay.transport.ConnectionProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One netplay session with another player: connects, checks both run the same game and keeps measuring the latency
 * until somebody leaves. It's symmetrical: host and guest run exactly the same handshake, they only differ in how
 * the [ConnectionProvider] gets the connection.
 *
 * Everything blocking happens on its own threads, so it's safe to create and [close] it from the UI thread.
 * [onStateChanged] is called from those threads, always in order. It must be quick and must not call back into
 * this session, which makes `MutableStateFlow.value = state` a perfect fit.
 */
class NetplaySession(
    private val provider: ConnectionProvider,
    private val role: PeerRole,
    private val playerName: String,
    private val localGame: GameFingerprint,
    private val timing: Timing = Timing(),
    private val onStateChanged: (SessionState) -> Unit,
) {
    data class Timing(
        /** How long the other side has to complete the handshake once connected. */
        val handshakeTimeoutMs: Long = 10_000,
        val pingIntervalMs: Long = 1_000,
        /** Without any message for this long, a ready session is considered dead. */
        val peerSilenceTimeoutMs: Long = 5_000,
    )

    private val stateLock = Any()
    private val writeLock = Any()

    @Volatile
    private var state: SessionState = SessionState.Idle

    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var closeRequested = false

    @Volatile
    private var handshakeStartedNanos = 0L

    @Volatile
    private var lastReceivedNanos = 0L

    private var started = false

    val currentState: SessionState get() = state

    fun start() {
        synchronized(stateLock) {
            check(!started) { "Session already started" }
            started = true
            if (closeRequested) return
        }
        thread(name = "netplay-session", isDaemon = true) { run() }
    }

    /** Leaves the session. Safe to call any time, from any thread, as many times as needed. */
    fun close() {
        synchronized(stateLock) {
            if (closeRequested) return
            closeRequested = true
        }

        transition { SessionState.Closed(CloseReason.LOCAL) }

        // Telling the other side and closing sockets is network work: not something to do on the caller thread.
        thread(name = "netplay-close", isDaemon = true) {
            runCatching { send(NetplayMessage.Bye) }
            closeConnection()
        }
    }

    private fun run() {
        transition { SessionState.Connecting }

        val opened =
            try {
                provider.open()
            } catch (e: Exception) {
                // Not only IOException: a transport may also fail because of, say, a revoked permission.
                if (!closeRequested) fail(SessionError.CONNECT_FAILED, e.message)
                return
            }

        connection = opened
        if (closeRequested) {
            closeConnection()
            return
        }

        handshakeStartedNanos = System.nanoTime()
        lastReceivedNanos = handshakeStartedNanos
        transition { SessionState.Handshaking(opened.description) }

        val watchdog = thread(name = "netplay-watchdog", isDaemon = true) { watchdogLoop() }
        try {
            send(NetplayMessage.Hello(PROTOCOL_VERSION, playerName, role))
            send(NetplayMessage.GameInfo(localGame))
            readLoop(opened)
        } catch (e: ProtocolException) {
            fail(SessionError.PROTOCOL_ERROR, e.message)
        } catch (e: IOException) {
            fail(SessionError.CONNECTION_LOST, e.message)
        } finally {
            watchdog.interrupt()
            closeConnection()
        }
    }

    private fun readLoop(opened: Connection) {
        var peerName: String? = null
        var peerGame: GameFingerprint? = null
        var sentReady = false
        var peerReady = false
        var announcedReady = false

        fun becomeReadyIfBothAgree() {
            val name = peerName
            if (name != null && sentReady && peerReady && !announcedReady) {
                announcedReady = true
                transition { SessionState.Ready(name, null) }
                // Measure the latency right away instead of waiting for the first tick of the watchdog.
                send(NetplayMessage.Ping(0, System.nanoTime()))
            }
        }

        while (!state.isTerminal) {
            val message = MessageCodec.read(opened.input)
            if (message == null) {
                fail(SessionError.CONNECTION_LOST, "The other player closed the connection")
                return
            }
            lastReceivedNanos = System.nanoTime()

            when (message) {
                is NetplayMessage.Hello -> {
                    if (peerName != null) throw ProtocolException("Duplicate hello")
                    if (message.protocolVersion != PROTOCOL_VERSION) {
                        reject(MismatchReason.PROTOCOL_VERSION, null)
                        return
                    }
                    peerName = message.playerName
                }
                is NetplayMessage.GameInfo -> {
                    if (peerName == null) throw ProtocolException("Game info before hello")
                    if (peerGame != null) throw ProtocolException("Duplicate game info")

                    peerGame = message.fingerprint
                    val reason = localGame.incompatibilityWith(message.fingerprint)
                    if (reason != null) {
                        reject(reason, message.fingerprint)
                        return
                    }

                    send(NetplayMessage.Ready)
                    sentReady = true
                    becomeReadyIfBothAgree()
                }
                NetplayMessage.Ready -> {
                    if (peerGame == null) throw ProtocolException("Ready before game info")
                    peerReady = true
                    becomeReadyIfBothAgree()
                }
                is NetplayMessage.Mismatch -> {
                    transition { SessionState.Mismatch(message.reason, detectedByPeer = true, peerGame) }
                }
                is NetplayMessage.Ping -> send(NetplayMessage.Pong(message.sequence, message.sentAtNanos))
                is NetplayMessage.Pong -> {
                    val roundTripMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - message.sentAtNanos)
                    transition { current ->
                        if (current is SessionState.Ready) current.copy(roundTripMillis = roundTripMillis) else null
                    }
                }
                NetplayMessage.Bye -> transition { SessionState.Closed(CloseReason.PEER_LEFT) }
            }
        }
    }

    private fun reject(
        reason: MismatchReason,
        peerGame: GameFingerprint?,
    ) {
        runCatching { send(NetplayMessage.Mismatch(reason)) }
        transition { SessionState.Mismatch(reason, detectedByPeer = false, peerGame) }
    }

    /** Pings the other side while the session is ready, and gives up on peers that stay silent. */
    private fun watchdogLoop() {
        var sequence = 1
        try {
            while (!state.isTerminal) {
                Thread.sleep(timing.pingIntervalMs)

                val now = System.nanoTime()
                when (state) {
                    is SessionState.Handshaking -> {
                        if (elapsedMillis(handshakeStartedNanos, now) > timing.handshakeTimeoutMs) {
                            fail(SessionError.TIMEOUT, "The other player didn't complete the handshake")
                            return
                        }
                    }
                    is SessionState.Ready -> {
                        if (elapsedMillis(lastReceivedNanos, now) > timing.peerSilenceTimeoutMs) {
                            fail(SessionError.CONNECTION_LOST, "The other player stopped answering")
                            return
                        }
                        send(NetplayMessage.Ping(sequence++, now))
                    }
                    else -> Unit
                }
            }
        } catch (e: InterruptedException) {
            // The session finished: nothing else to watch.
        } catch (e: IOException) {
            fail(SessionError.CONNECTION_LOST, e.message)
        }
    }

    private fun elapsedMillis(
        from: Long,
        to: Long,
    ) = TimeUnit.NANOSECONDS.toMillis(to - from)

    @Throws(IOException::class)
    private fun send(message: NetplayMessage) {
        val target = connection ?: throw IOException("Not connected")
        synchronized(writeLock) {
            MessageCodec.write(target.output, message)
        }
    }

    private fun fail(
        error: SessionError,
        detail: String?,
    ) {
        transition { SessionState.Failed(error, detail) }
        // Closing the socket also wakes up the thread blocked reading from it.
        closeConnection()
    }

    private fun closeConnection() {
        runCatching { connection?.close() }
        runCatching { provider.close() }
    }

    /**
     * Moves to the state returned by [compute] (null to stay where we are). Terminal states are final, so a late
     * "connection lost" can't hide the reason why a session ended. Listener calls happen under the lock to keep
     * them in order.
     */
    private fun transition(compute: (SessionState) -> SessionState?) {
        synchronized(stateLock) {
            val current = state
            if (current.isTerminal) return

            val next = compute(current) ?: return
            if (next == current) return

            state = next
            onStateChanged(next)
        }
    }
}
