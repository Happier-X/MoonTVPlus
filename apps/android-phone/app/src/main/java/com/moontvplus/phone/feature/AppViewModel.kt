package com.moontvplus.phone.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.storage.AppSettings
import com.moontvplus.phone.core.storage.SettingsStore
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.network.SessionManager
import com.moontvplus.phone.core.platform.LegacySessionBridge
import com.moontvplus.phone.data.repository.AuthRepository
import com.moontvplus.phone.data.repository.ServerProbeResult
import com.moontvplus.phone.data.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppUiState {
    data object Loading : AppUiState
    data object NeedServer : AppUiState
    data class NeedLogin(val reason: String? = null) : AppUiState
    data class LoggedIn(
        val session: AuthSession,
        val siteName: String?,
        val serverVersion: String?,
    ) : AppUiState
}

/**
 * 启动决策：没有服务器地址 → 设置页；没有会话 → 登录页；否则进首页。
 */
class AppViewModel(
    private val settingsStore: SettingsStore,
    private val serverProvider: ServerProvider,
    private val sessions: SessionManager,
    private val authRepository: AuthRepository,
    private val serverRepository: ServerRepository,
    private val legacyMigration: LegacySessionBridge,
    /** 启动时把设置从磁盘读进内存，只做一次 */
    private val initialize: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val _editingServer = MutableStateFlow(false)
    val editingServer: StateFlow<Boolean> = _editingServer.asStateFlow()

    private var addressBeforeEdit: String? = null

    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    init {
        boot()
        // token 刷新失败时 SessionManager 会清空会话，这里跟着退回登录页
        viewModelScope.launch {
            sessions.session.collect { session ->
                if (session == null && _state.value is AppUiState.LoggedIn) {
                    _state.value = AppUiState.NeedLogin("登录已过期，请重新登录")
                }
            }
        }
    }

    fun boot() {
        viewModelScope.launch {
            _state.value = AppUiState.Loading
            initialize()
            legacyMigration.migrateServerAddress(settingsStore)

            var snapshot = settingsStore.snapshot()
            var address = ServerAddress.parseStored(snapshot.serverAddress)
            serverProvider.set(address)

            if (address == null) {
                _state.value = AppUiState.NeedServer
                return@launch
            }

            // 旧壳升级上来时只有地址、没有站点信息，补探测一次拿到存储类型（决定登录方式）
            if (snapshot.storageType.isNullOrBlank()) {
                val probed = serverRepository.probe(address.display())
                if (probed is ServerProbeResult.Success) {
                    serverRepository.save(probed.address, probed.config)
                    snapshot = settingsStore.snapshot()
                    address = probed.address
                }
            }

            sessions.load()
            if (sessions.session.value == null) {
                legacyMigration.restoreSessionOnce(address, settingsStore)?.let { sessions.save(it) }
            }

            val session = sessions.session.value
            if (session == null) {
                _state.value = AppUiState.NeedLogin()
                return@launch
            }

            val fresh = sessions.ensureFresh()
            _state.value = if (fresh) {
                AppUiState.LoggedIn(
                    session = sessions.session.value ?: session,
                    siteName = snapshot.siteName,
                    serverVersion = snapshot.serverVersion,
                )
            } else {
                AppUiState.NeedLogin("登录已过期，请重新登录")
            }
        }
    }

    /** 打开服务器地址编辑页（保留当前地址作为预填）。 */
    fun openServerEditor() {
        viewModelScope.launch {
            addressBeforeEdit = settingsStore.snapshot().serverAddress
            _editingServer.value = true
        }
    }

    fun closeServerEditor() {
        addressBeforeEdit = null
        _editingServer.value = false
    }

    fun onServerSaved() {
        viewModelScope.launch {
            _editingServer.value = false
            val saved = settingsStore.snapshot().serverAddress
            // 换成别的站点就不能再带着旧站点的 token
            if (addressBeforeEdit != null && addressBeforeEdit != saved) {
                sessions.clear()
            }
            addressBeforeEdit = null
            boot()
        }
    }

    fun onLoggedIn(session: AuthSession) {
        viewModelScope.launch {
            val snapshot = settingsStore.snapshot()
            _state.value = AppUiState.LoggedIn(
                session = session,
                siteName = snapshot.siteName,
                serverVersion = snapshot.serverVersion,
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _state.value = AppUiState.NeedLogin()
        }
    }

    /** 换站点：保存新地址时再清会话，避免把 token 发到别的服务器。 */
    fun changeServer() = openServerEditor()

    fun sessionExpired(reason: String = "登录已过期，请重新登录") {
        _state.value = AppUiState.NeedLogin(reason)
    }
}
