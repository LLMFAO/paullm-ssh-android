/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.ui.screens.console

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.DisconnectReason
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.sshlib.PortForwarder
import org.connectbot.terminal.ProgressState
import java.util.UUID
import javax.inject.Inject

data class ConsoleUiState(
    val bridges: List<TerminalBridge> = emptyList(),
    val currentBridgeIndex: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    // Add a revision counter to force recomposition when bridge state changes
    val revision: Int = 0,
    // Progress state from OSC 9;4 escape sequences
    val progressState: ProgressState? = null,
    val progressValue: Int = 0,
)

@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val hostId: UUID = EntityIds.parse(savedStateHandle.get<String>("hostId"))
    private var terminalManager: TerminalManager? = null
    private var pendingInitialHostId: UUID? = hostId.takeIf { it != EntityIds.NONE }
    private var selectedSessionUuid: UUID? = null

    private val progressJobs = mutableMapOf<UUID, Job>()
    private val networkStatusJobs = mutableMapOf<UUID, Job>()

    private val _uiState = MutableStateFlow(ConsoleUiState())
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    private val _networkStatusMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val networkStatusMessages: SharedFlow<String> = _networkStatusMessages.asSharedFlow()

    /** Save the captured session's font size only while that session and profile remain active. */
    suspend fun saveFontSize(bridge: TerminalBridge, profile: TerminalBridge.ActiveProfile, size: Int): Boolean {
        val state = _uiState.value
        if (state.bridges.getOrNull(state.currentBridgeIndex) !== bridge) return false
        return bridge.saveFontSize(profile, size)
    }

    fun connectionDetails(): ConnectionDetails? {
        val state = _uiState.value
        val bridge = state.bridges.getOrNull(state.currentBridgeIndex) ?: return null
        val transport = bridge.transport
        return ConnectionDetails(
            host = bridge.host,
            status = when {
                bridge.isDisconnected -> ConnectionStatus.DISCONNECTED
                bridge.isConnecting -> ConnectionStatus.CONNECTING
                else -> ConnectionStatus.CONNECTED
            },
            localAddress = transport?.getLocalIpAddress(),
            ssh = transport?.getSshConnectionInfo(),
            cryptoPolicies = transport?.getSshPolicyDetails().orEmpty(),
            portForwards = transport?.getPortForwards().orEmpty().map { forward ->
                val forwarder = forward.getIdentifier() as? PortForwarder
                PortForwardDetails(
                    nickname = forward.nickname,
                    type = forward.type,
                    sourceAddress = forward.sourceAddr,
                    configuredPort = forward.sourcePort,
                    destinationAddress = forward.destAddr,
                    destinationPort = forward.destPort,
                    boundPort = forwarder?.boundPort,
                    isActive = !bridge.isDisconnected && forwarder?.isActive == true,
                )
            },
        )
    }

    fun setTerminalManager(manager: TerminalManager) {
        if (terminalManager != manager) {
            terminalManager = manager

            viewModelScope.launch {
                manager.bridgesFlow.collect { bridges ->
                    updateBridges(bridges)
                    syncBridgeProgressSubscriptions(bridges)
                    syncBridgeNetworkStatusSubscriptions(bridges)
                }
            }

            viewModelScope.launch {
                manager.hostStatusChangedFlow.collect {
                    _uiState.update { it.copy(revision = it.revision + 1) }
                }
            }

            if (hostId != EntityIds.NONE) {
                viewModelScope.launch {
                    ensureBridgeExists()
                }
            }
        }
    }

    private fun syncBridgeProgressSubscriptions(bridges: List<TerminalBridge>) {
        syncBridgeJobs(bridges, progressJobs) { bridge ->
            viewModelScope.launch {
                bridge.progressState.collect { progressInfo ->
                    val currentBridge = _uiState.value.bridges.getOrNull(_uiState.value.currentBridgeIndex)
                    if (currentBridge == bridge) {
                        updateProgressState(progressInfo)
                    }
                }
            }
        }
    }

    private fun syncBridgeNetworkStatusSubscriptions(bridges: List<TerminalBridge>) {
        syncBridgeJobs(bridges, networkStatusJobs) { bridge ->
            viewModelScope.launch {
                bridge.networkStatusMessages.collect { message ->
                    val currentBridge = _uiState.value.bridges.getOrNull(_uiState.value.currentBridgeIndex)
                    if (currentBridge == bridge) {
                        _networkStatusMessages.emit(message)
                    }
                }
            }
        }
    }

    private fun syncBridgeJobs(
        bridges: List<TerminalBridge>,
        jobs: MutableMap<UUID, Job>,
        createJob: (TerminalBridge) -> Job,
    ) {
        val activeSessionIds = bridges.map { it.sessionUuid }.toSet()

        val removedIds = jobs.keys.filter { it !in activeSessionIds }
        removedIds.forEach { sessionId ->
            jobs.remove(sessionId)?.cancel()
        }

        bridges.forEach { bridge ->
            jobs.getOrPut(bridge.sessionUuid) {
                createJob(bridge)
            }
        }
    }

    private fun updateProgressState(progressInfo: TerminalBridge.ProgressInfo?) {
        _uiState.update {
            if (progressInfo == null || progressInfo.state == ProgressState.HIDDEN) {
                it.copy(progressState = null, progressValue = 0)
            } else {
                it.copy(
                    progressState = progressInfo.state,
                    progressValue = progressInfo.progress,
                )
            }
        }
    }

    private fun updateCurrentBridgeProgress(
        bridges: List<TerminalBridge> = _uiState.value.bridges,
        currentIndex: Int = _uiState.value.currentBridgeIndex,
    ) {
        val progressInfo = bridges.getOrNull(currentIndex)?.progressState?.value
        updateProgressState(progressInfo)
    }

    private fun findBridgeIndexByHost(bridges: List<TerminalBridge>, bridgeHostId: UUID?): Int {
        if (bridgeHostId == null) return -1
        return bridges.indexOfFirst { it.host.id == bridgeHostId }
    }

    private fun findBridgeIndexBySession(bridges: List<TerminalBridge>, sessionUuid: UUID?): Int {
        if (sessionUuid == null) return -1
        return bridges.indexOfFirst { it.sessionUuid == sessionUuid }
    }

    private fun stepBridgeSelection(offset: Int) {
        val bridges = _uiState.value.bridges
        if (bridges.isEmpty()) {
            return
        }

        val newIndex = (_uiState.value.currentBridgeIndex + offset).coerceIn(0, bridges.lastIndex)
        if (newIndex != _uiState.value.currentBridgeIndex) {
            selectBridge(newIndex)
        }
    }

    fun selectNextBridge() {
        stepBridgeSelection(1)
    }

    fun selectPreviousBridge() {
        stepBridgeSelection(-1)
    }

    private fun handleInitialSelectionError(message: String) {
        pendingInitialHostId = null
        _uiState.update {
            it.copy(
                isLoading = false,
                error = message,
            )
        }
    }

    private suspend fun ensureBridgeExists() {
        withContext(dispatchers.io) {
            try {
                val allBridges = terminalManager?.bridgesFlow?.value ?: emptyList()
                val existingBridge = allBridges.find { bridge ->
                    bridge.host.id == hostId
                }

                if (existingBridge == null) {
                    if (hostId == EntityIds.NONE) {
                        handleInitialSelectionError("Temporary connection not found")
                    } else {
                        val bridge = terminalManager?.openConnectionForHostId(hostId)
                        if (bridge == null) {
                            handleInitialSelectionError("Failed to open connection: host not found")
                        }
                    }
                }
            } catch (e: Exception) {
                handleInitialSelectionError(e.message ?: "Failed to create connection")
            }
        }
    }

    private fun updateBridges(allBridges: List<TerminalBridge>) {
        val currentState = _uiState.value
        val selectedIndex = findBridgeIndexBySession(allBridges, selectedSessionUuid)
        val requestedIndex = findBridgeIndexByHost(allBridges, pendingInitialHostId)
        val requestedBridgeAvailable = requestedIndex != -1

        val newIndex = when {
            requestedBridgeAvailable -> requestedIndex
            selectedIndex != -1 -> selectedIndex
            currentState.currentBridgeIndex >= allBridges.size -> (allBridges.size - 1).coerceAtLeast(0)
            else -> currentState.currentBridgeIndex
        }

        if (requestedBridgeAvailable) {
            pendingInitialHostId = null
            selectedSessionUuid = allBridges.getOrNull(newIndex)?.sessionUuid
        } else if (pendingInitialHostId == null) {
            selectedSessionUuid = allBridges.getOrNull(newIndex)?.sessionUuid
        }

        val waitingForRequestedHost = pendingInitialHostId != null

        _uiState.update {
            it.copy(
                bridges = allBridges,
                currentBridgeIndex = newIndex,
                isLoading = waitingForRequestedHost,
                error = null,
            )
        }

        updateCurrentBridgeProgress(allBridges, newIndex)
    }

    fun selectBridge(index: Int) {
        if (index in _uiState.value.bridges.indices) {
            selectedSessionUuid = _uiState.value.bridges[index].sessionUuid
            _uiState.update { it.copy(currentBridgeIndex = index) }
            updateCurrentBridgeProgress()
        }
    }

    fun openOrSwitchTmuxTab(host: Host, tmuxSessionName: String) {
        val manager = terminalManager ?: return
        val bridge = manager.openTmuxSession(host, tmuxSessionName)
        selectedSessionUuid = bridge.sessionUuid
        val currentBridges = manager.bridgesFlow.value
        val idx = currentBridges.indexOfFirst { it.sessionUuid == bridge.sessionUuid }
        if (idx >= 0) {
            _uiState.update {
                it.copy(
                    bridges = currentBridges,
                    currentBridgeIndex = idx,
                    revision = it.revision + 1,
                )
            }
            updateCurrentBridgeProgress(currentBridges, idx)
        }
    }

    fun openNewShellTab(host: Host) {
        val manager = terminalManager ?: return
        val bridge = manager.openAdditionalShell(host)
        selectedSessionUuid = bridge.sessionUuid
        val currentBridges = manager.bridgesFlow.value
        val idx = currentBridges.indexOfFirst { it.sessionUuid == bridge.sessionUuid }
        if (idx >= 0) {
            _uiState.update {
                it.copy(
                    bridges = currentBridges,
                    currentBridgeIndex = idx,
                    revision = it.revision + 1,
                )
            }
            updateCurrentBridgeProgress(currentBridges, idx)
        }
    }

    fun detachTab(bridge: TerminalBridge) {
        bridge.dispatchDisconnect(DisconnectReason.USER_REQUESTED)
    }

    /**
     * Refresh the UI state to trigger recomposition.
     * This is needed because bridge state (isSessionOpen, isDisconnected, etc.)
     * changes asynchronously but doesn't trigger Compose recomposition automatically.
     */
    fun refreshMenuState() {
        _uiState.update { it.copy(revision = it.revision + 1) }
    }

    /**
     * Request a reconnection for the given bridge.
     */
    fun reconnect(bridge: TerminalBridge) {
        terminalManager?.requestReconnect(bridge)
        _uiState.update { it.copy(revision = it.revision + 1) }
    }
}
