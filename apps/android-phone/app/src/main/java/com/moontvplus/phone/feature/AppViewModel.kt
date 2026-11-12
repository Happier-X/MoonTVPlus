package com.moontvplus.phone.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.datastore.AppSettings
import com.moontvplus.phone.core.datastore.SettingsStore
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.network.SessionManager
import com.moontvplus.phone.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val serverProvider: ServerProvider,
    private val sessions: SessionManager,
    private val authRepository: AuthRepository,
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
            val snapshot = settingsStore.snapshot()
            val address = ServerAddress.parseStored(snapshot.serverAddress)
            serverProvider.set(address)

            if (address == null) {
                _state.value = AppUiState.NeedServer
                return@launch
            }

            sessions.load()
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
