package com.swordfish.lemuroid.app.mobile.feature.netplay

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.netplay.protocol.PeerRole
import com.swordfish.lemuroid.netplay.rom.GameFingerprint
import com.swordfish.lemuroid.netplay.rom.RomFingerprint
import com.swordfish.lemuroid.netplay.session.NetplaySession
import com.swordfish.lemuroid.netplay.session.SessionState
import com.swordfish.lemuroid.netplay.transport.BluetoothClientProvider
import com.swordfish.lemuroid.netplay.transport.BluetoothHostProvider
import com.swordfish.lemuroid.netplay.transport.BluetoothSupport
import com.swordfish.lemuroid.netplay.transport.ConnectionProvider
import com.swordfish.lemuroid.netplay.transport.LocalAddress
import com.swordfish.lemuroid.netplay.transport.NetworkAddresses
import com.swordfish.lemuroid.netplay.transport.PairedDevice
import com.swordfish.lemuroid.netplay.transport.TcpClientProvider
import com.swordfish.lemuroid.netplay.transport.TcpHostProvider
import com.swordfish.lemuroid.netplay.transport.WifiSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream

class NetplayViewModel(
    private val appContext: Context,
    private val retrogradeDb: RetrogradeDatabase,
    private val lemuroidLibrary: LemuroidLibrary,
) : ViewModel() {
    class Factory(
        private val appContext: Context,
        private val retrogradeDb: RetrogradeDatabase,
        private val lemuroidLibrary: LemuroidLibrary,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NetplayViewModel(appContext, retrogradeDb, lemuroidLibrary) as T
        }
    }

    enum class Medium { WIFI, BLUETOOTH }

    sealed class GameCheck {
        data object None : GameCheck()

        data class Checking(val percent: Int) : GameCheck()

        data class Ready(val fingerprint: GameFingerprint) : GameCheck()

        data object Failed : GameCheck()
    }

    data class UiState(
        val games: List<Game> = emptyList(),
        val gamesLoaded: Boolean = false,
        val selectedGame: Game? = null,
        val gameCheck: GameCheck = GameCheck.None,
        val medium: Medium = Medium.WIFI,
        /** Null until a session is created. The [session] state is what the UI shows from then on. */
        val role: PeerRole? = null,
        val session: SessionState? = null,
        val hostAddresses: List<LocalAddress> = emptyList(),
        val hostPort: Int? = null,
        val pairedDevices: List<PairedDevice> = emptyList(),
        val suggestedAddress: String = "",
        @StringRes val message: Int? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null
    private val sessionLock = Any()
    private var session: NetplaySession? = null

    // Callbacks of a session we already left must not touch the screen anymore.
    @Volatile
    private var generation = 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val games = runCatching { retrogradeDb.gameDao().asyncSelectAll() }.getOrDefault(emptyList())
            _uiState.update { it.copy(games = games, gamesLoaded = true) }
        }
        refreshNetworkInfo()
    }

    fun refreshNetworkInfo() {
        val suggestion = WifiSupport.gatewayAddress(appContext).orEmpty()
        _uiState.update { it.copy(suggestedAddress = suggestion) }
    }

    fun refreshBluetooth() {
        val adapter = BluetoothSupport.adapter(appContext)
        val devices =
            if (adapter != null && BluetoothSupport.hasPermissions(appContext)) {
                BluetoothSupport.pairedDevices(adapter)
            } else {
                emptyList()
            }
        _uiState.update { it.copy(pairedDevices = devices) }
    }

    fun setMedium(medium: Medium) {
        _uiState.update { it.copy(medium = medium, message = null) }
        if (medium == Medium.BLUETOOTH) refreshBluetooth()
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun reportBluetoothPermissionDenied() {
        _uiState.update { it.copy(message = R.string.netplay_bluetooth_permission_needed) }
    }

    fun selectGame(game: Game) {
        checkJob?.cancel()
        _uiState.update { it.copy(selectedGame = game, gameCheck = GameCheck.Checking(0), message = null) }

        checkJob =
            viewModelScope.launch(Dispatchers.IO) {
                val result = runCatching { fingerprintOf(game) { isActive } }
                // A newer selection took over (or the screen is gone): this result is stale.
                if (!isActive || _uiState.value.selectedGame?.id != game.id) return@launch

                val check = result.fold({ GameCheck.Ready(it) }, { GameCheck.Failed })
                _uiState.update { it.copy(gameCheck = check) }
            }
    }

    fun clearGame() {
        checkJob?.cancel()
        _uiState.update { it.copy(selectedGame = null, gameCheck = GameCheck.None, message = null) }
    }

    /** Hashes exactly the files the emulator will load, so zipped and unzipped copies of a ROM match. */
    private fun fingerprintOf(
        game: Game,
        stillWanted: () -> Boolean,
    ): GameFingerprint {
        val dataFiles = retrogradeDb.dataFileDao().selectDataFilesForGame(game.id)
        val romFiles = lemuroidLibrary.getGameFiles(game, dataFiles, false)
        val files = (romFiles as? RomFiles.Standard)?.files ?: throw IOException("Unexpected ROM files: $romFiles")
        val total = files.sumOf { it.length() }.coerceAtLeast(1L)

        val streams = mutableListOf<InputStream>()
        try {
            files.forEach { streams.add(it.inputStream()) }

            var lastPercent = -1
            return RomFingerprint.computeAll(game.systemId, streams) { processed ->
                // Stops hashing a big file as soon as the player picks another game.
                if (!stillWanted()) throw IOException("Cancelled")

                val percent = (processed * 100 / total).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    _uiState.update { state ->
                        if (state.selectedGame?.id == game.id && state.gameCheck is GameCheck.Checking) {
                            state.copy(gameCheck = GameCheck.Checking(percent))
                        } else {
                            state
                        }
                    }
                }
            }
        } finally {
            streams.forEach { runCatching { it.close() } }
        }
    }

    fun startHost() {
        val fingerprint = readyFingerprint() ?: return

        when (_uiState.value.medium) {
            Medium.WIFI -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val provider = TcpHostProvider()
                    val port =
                        try {
                            provider.bind()
                        } catch (e: IOException) {
                            provider.close()
                            _uiState.update { it.copy(message = R.string.netplay_port_in_use) }
                            return@launch
                        }
                    begin(provider, PeerRole.HOST, fingerprint, port, NetworkAddresses.localAddresses())
                }
            }
            Medium.BLUETOOTH -> {
                val adapter = usableBluetoothAdapter() ?: return
                begin(BluetoothHostProvider(adapter), PeerRole.HOST, fingerprint)
            }
        }
    }

    fun joinWifi(addressText: String) {
        val fingerprint = readyFingerprint() ?: return
        val address = NetworkAddresses.parseHostAddress(addressText)
        if (address == null) {
            _uiState.update { it.copy(message = R.string.netplay_guest_address_invalid) }
            return
        }

        begin(TcpClientProvider(address.host, address.port), PeerRole.GUEST, fingerprint)
    }

    fun joinBluetooth(deviceAddress: String) {
        val fingerprint = readyFingerprint() ?: return
        val adapter = usableBluetoothAdapter() ?: return

        val device =
            try {
                adapter.getRemoteDevice(deviceAddress)
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(message = R.string.netplay_error_connect) }
                return
            }

        begin(BluetoothClientProvider(device), PeerRole.GUEST, fingerprint)
    }

    fun leave() {
        synchronized(sessionLock) {
            generation++
            session?.close()
            session = null
        }
        _uiState.update {
            it.copy(role = null, session = null, hostPort = null, hostAddresses = emptyList(), message = null)
        }
    }

    private fun readyFingerprint(): GameFingerprint? {
        return (_uiState.value.gameCheck as? GameCheck.Ready)?.fingerprint
    }

    /** Reports to the player why Bluetooth can't be used, and returns null in that case. */
    private fun usableBluetoothAdapter(): android.bluetooth.BluetoothAdapter? {
        val adapter = BluetoothSupport.adapter(appContext)

        @StringRes val problem =
            when {
                adapter == null -> R.string.netplay_bluetooth_unavailable
                !BluetoothSupport.hasPermissions(appContext) -> R.string.netplay_bluetooth_permission_needed
                !adapter.isEnabled -> R.string.netplay_bluetooth_disabled
                else -> null
            }

        if (problem != null) {
            _uiState.update { it.copy(message = problem) }
            return null
        }

        return adapter
    }

    private fun begin(
        provider: ConnectionProvider,
        role: PeerRole,
        fingerprint: GameFingerprint,
        hostPort: Int? = null,
        hostAddresses: List<LocalAddress> = emptyList(),
    ) {
        val created: NetplaySession
        synchronized(sessionLock) {
            session?.close()

            val currentGeneration = ++generation
            created =
                NetplaySession(
                    provider = provider,
                    role = role,
                    playerName = Build.MODEL ?: "Android",
                    localGame = fingerprint,
                ) { state ->
                    if (currentGeneration == generation) {
                        _uiState.update { it.copy(session = state) }
                    }
                }
            session = created
        }

        _uiState.update {
            it.copy(
                role = role,
                session = SessionState.Idle,
                hostPort = hostPort,
                hostAddresses = hostAddresses,
                message = null,
            )
        }
        created.start()
    }

    override fun onCleared() {
        checkJob?.cancel()
        synchronized(sessionLock) {
            generation++
            session?.close()
            session = null
        }
    }
}
