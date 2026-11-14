package com.swordfish.lemuroid.app.mobile.feature.netplay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.netplay.NetplayViewModel.GameCheck
import com.swordfish.lemuroid.app.mobile.feature.netplay.NetplayViewModel.Medium
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.netplay.protocol.MismatchReason
import com.swordfish.lemuroid.netplay.protocol.PeerRole
import com.swordfish.lemuroid.netplay.session.CloseReason
import com.swordfish.lemuroid.netplay.session.SessionError
import com.swordfish.lemuroid.netplay.session.SessionState
import com.swordfish.lemuroid.netplay.transport.BluetoothSupport
import com.swordfish.lemuroid.netplay.transport.DEFAULT_TCP_PORT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetplayScreen(
    modifier: Modifier = Modifier,
    viewModel: NetplayViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var joining by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var pendingBluetoothAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = pendingBluetoothAction
            pendingBluetoothAction = null
            if (results.values.all { it }) {
                viewModel.refreshBluetooth()
                action?.invoke()
            } else {
                viewModel.reportBluetoothPermissionDenied()
            }
        }

    fun withBluetoothPermission(action: () -> Unit) {
        if (BluetoothSupport.hasPermissions(context)) {
            action()
        } else {
            pendingBluetoothAction = action
            permissionLauncher.launch(BluetoothSupport.requiredPermissions())
        }
    }

    val session = state.session
    val gameCheck = state.gameCheck

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.message?.let { message ->
            item(key = "message") {
                Text(
                    text = stringResource(message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item(key = "title-game") { SectionTitle(R.string.netplay_step_game) }

        val selectedGame = state.selectedGame
        if (selectedGame == null) {
            item(key = "search") {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(R.string.netplay_search_games)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val visibleGames =
                state.games.filter { filter.isBlank() || it.title.contains(filter.trim(), ignoreCase = true) }

            if (state.gamesLoaded && visibleGames.isEmpty()) {
                item(key = "no-games") { Text(stringResource(R.string.netplay_no_games)) }
            }

            items(visibleGames, key = { it.id }) { game ->
                GameRow(game = game, onClick = { viewModel.selectGame(game) })
            }
        } else {
            item(key = "selected-game") {
                SelectedGameCard(
                    game = selectedGame,
                    check = gameCheck,
                    canChange = session == null,
                    onChange = viewModel::clearGame,
                )
            }
        }

        if (gameCheck is GameCheck.Ready) {
            item(key = "title-connect") { SectionTitle(R.string.netplay_step_connect) }

            if (session == null) {
                item(key = "medium") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.medium == Medium.WIFI,
                            onClick = { viewModel.setMedium(Medium.WIFI) },
                            label = { Text(stringResource(R.string.netplay_medium_wifi)) },
                        )
                        FilterChip(
                            selected = state.medium == Medium.BLUETOOTH,
                            onClick = { viewModel.setMedium(Medium.BLUETOOTH) },
                            label = { Text(stringResource(R.string.netplay_medium_bluetooth)) },
                        )
                    }
                }

                if (!joining) {
                    item(key = "role-buttons") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (state.medium == Medium.BLUETOOTH) {
                                        withBluetoothPermission { viewModel.startHost() }
                                    } else {
                                        viewModel.startHost()
                                    }
                                },
                            ) { Text(stringResource(R.string.netplay_create_game)) }
                            OutlinedButton(
                                onClick = {
                                    joining = true
                                    viewModel.refreshNetworkInfo()
                                    if (state.medium == Medium.BLUETOOTH) {
                                        withBluetoothPermission { viewModel.refreshBluetooth() }
                                    }
                                },
                            ) { Text(stringResource(R.string.netplay_join_game)) }
                        }
                    }
                } else if (state.medium == Medium.WIFI) {
                    item(key = "join-wifi") {
                        JoinWifi(
                            suggestedAddress = state.suggestedAddress,
                            invalidAddress = state.message == R.string.netplay_guest_address_invalid,
                            onConnect = viewModel::joinWifi,
                            onBack = { joining = false },
                        )
                    }
                } else {
                    item(key = "join-bluetooth-title") {
                        Text(
                            text = stringResource(R.string.netplay_paired_devices),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (state.pairedDevices.isEmpty()) {
                        item(key = "join-bluetooth-empty") {
                            Text(stringResource(R.string.netplay_no_paired_devices))
                        }
                    }
                    items(state.pairedDevices, key = { "bt-" + it.address }) { device ->
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        withBluetoothPermission { viewModel.joinBluetooth(device.address) }
                                    },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                Text(device.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item(key = "join-bluetooth-back") {
                        TextButton(onClick = { joining = false }) { Text(stringResource(R.string.netplay_cancel)) }
                    }
                }
            }
        }

        if (session != null) {
            item(key = "session") {
                SessionCard(
                    state = state,
                    onLeave = {
                        joining = false
                        viewModel.leave()
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(
    @StringRes title: Int,
) {
    Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun GameRow(
    game: Game,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(game.title, style = MaterialTheme.typography.bodyLarge)
            Text(game.systemId.uppercase(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SelectedGameCard(
    game: Game,
    check: GameCheck,
    canChange: Boolean,
    onChange: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(game.title, style = MaterialTheme.typography.titleMedium)
            Text(game.systemId.uppercase(), style = MaterialTheme.typography.bodySmall)

            when (check) {
                is GameCheck.Checking -> {
                    Text(stringResource(R.string.netplay_checking_game, check.percent))
                    LinearProgressIndicator(progress = check.percent / 100f, modifier = Modifier.fillMaxWidth())
                }
                is GameCheck.Ready -> {
                    Text(stringResource(R.string.netplay_game_ready))
                    Text(
                        text = stringResource(R.string.netplay_both_need_same_game),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                GameCheck.Failed ->
                    Text(
                        text = stringResource(R.string.netplay_game_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                GameCheck.None -> Unit
            }

            TextButton(onClick = onChange, enabled = canChange) {
                Text(stringResource(R.string.netplay_change_game))
            }
        }
    }
}

@Composable
private fun JoinWifi(
    suggestedAddress: String,
    invalidAddress: Boolean,
    onConnect: (String) -> Unit,
    onBack: () -> Unit,
) {
    var address by rememberSaveable(suggestedAddress) { mutableStateOf(suggestedAddress) }
    val invalidMessage: (@Composable () -> Unit)? =
        if (invalidAddress) {
            { Text(stringResource(R.string.netplay_guest_address_invalid)) }
        } else {
            null
        }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.netplay_guest_address)) },
            placeholder = { Text("192.168.43.1") },
            isError = invalidAddress,
            supportingText = invalidMessage,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onConnect(address) }) { Text(stringResource(R.string.netplay_connect)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.netplay_cancel)) }
        }
    }
}

@Composable
private fun SessionCard(
    state: NetplayViewModel.UiState,
    onLeave: () -> Unit,
) {
    val session = state.session ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (session) {
                SessionState.Idle, SessionState.Connecting -> WaitingContent(state)
                is SessionState.Handshaking -> {
                    Text(stringResource(R.string.netplay_state_handshaking, session.remote))
                    CircularProgressIndicator()
                }
                is SessionState.Ready -> {
                    Text(
                        text = stringResource(R.string.netplay_state_ready, session.peerName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    session.roundTripMillis?.let {
                        Text(stringResource(R.string.netplay_state_latency, it))
                    }
                    Text(
                        text = stringResource(R.string.netplay_state_ready_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is SessionState.Mismatch -> ErrorText(mismatchMessage(session.reason))
                is SessionState.Failed -> ErrorText(errorMessage(session.error))
                is SessionState.Closed ->
                    if (session.reason == CloseReason.PEER_LEFT) {
                        Text(stringResource(R.string.netplay_peer_left))
                    }
            }

            if (session.isTerminal) {
                Button(onClick = onLeave) { Text(stringResource(R.string.netplay_try_again)) }
            } else {
                OutlinedButton(onClick = onLeave) {
                    val label = if (session is SessionState.Ready) R.string.netplay_leave else R.string.netplay_cancel
                    Text(stringResource(label))
                }
            }
        }
    }
}

@Composable
private fun WaitingContent(state: NetplayViewModel.UiState) {
    if (state.role == PeerRole.HOST) {
        Text(stringResource(R.string.netplay_state_waiting_host), style = MaterialTheme.typography.titleMedium)

        if (state.medium == Medium.WIFI) {
            Text(stringResource(R.string.netplay_host_hint), style = MaterialTheme.typography.bodySmall)
            if (state.hostAddresses.isNotEmpty()) {
                Text(stringResource(R.string.netplay_host_addresses))
                state.hostAddresses.forEach { local ->
                    val port = state.hostPort
                    val text = if (port == null || port == DEFAULT_TCP_PORT) local.address else "${local.address}:$port"
                    Text(text, style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            Text(
                text = stringResource(R.string.netplay_host_bluetooth_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        Text(stringResource(R.string.netplay_state_connecting), style = MaterialTheme.typography.titleMedium)
    }

    CircularProgressIndicator()
}

@Composable
private fun ErrorText(
    @StringRes message: Int,
) {
    Text(text = stringResource(message), color = MaterialTheme.colorScheme.error)
}

@StringRes
private fun mismatchMessage(reason: MismatchReason): Int {
    return when (reason) {
        MismatchReason.PROTOCOL_VERSION -> R.string.netplay_mismatch_version
        MismatchReason.SYSTEM -> R.string.netplay_mismatch_system
        MismatchReason.ROM_CONTENT -> R.string.netplay_mismatch_rom
    }
}

@StringRes
private fun errorMessage(error: SessionError): Int {
    return when (error) {
        SessionError.CONNECT_FAILED -> R.string.netplay_error_connect
        SessionError.TIMEOUT -> R.string.netplay_error_timeout
        SessionError.CONNECTION_LOST -> R.string.netplay_error_lost
        SessionError.PROTOCOL_ERROR -> R.string.netplay_error_protocol
    }
}
