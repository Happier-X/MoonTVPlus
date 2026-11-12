package com.moontvplus.phone.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.common.ApiResult
import com.moontvplus.phone.core.common.userMessage
import com.moontvplus.phone.core.datastore.SettingsStore
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsStore: SettingsStore,
    serverProvider: ServerProvider,
) : ViewModel() {

    data class UiState(
        val storageType: String? = null,
        val siteName: String? = null,
        val origin: String? = null,
        val username: String = "",
        val password: String = "",
        val requireTurnstile: Boolean = false,
        val turnstileSiteKey: String? = null,
        val turnstileToken: String? = null,
        /** 自增后重建 Turnstile WebView，等价网页端的 turnstile.reset() */
        val turnstileReset: Int = 0,
        val submitting: Boolean = false,
        val error: String? = null,
    ) {
        val askUsername: Boolean
            get() = !storageType.equals("localstorage", ignoreCase = true)

        val canSubmit: Boolean
            get() = !submitting &&
                password.isNotEmpty() &&
                (!askUsername || username.isNotBlank()) &&
                (!requireTurnstile || turnstileToken != null)
    }

    private val _state = MutableStateFlow(UiState(origin = serverProvider.current()?.origin))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.snapshot()
            _state.value = _state.value.copy(
                storageType = settings.storageType,
                siteName = settings.siteName,
                origin = serverProvider.current()?.origin,
                requireTurnstile = settings.loginRequireTurnstile,
                turnstileSiteKey = settings.turnstileSiteKey,
            )
        }
    }

    fun onUsernameChange(value: String) {
        _state.value = _state.value.copy(username = value, error = null)
    }

    fun onPasswordChange(value: String) {
        _state.value = _state.value.copy(password = value, error = null)
    }

    fun onTurnstileToken(token: String?) {
        _state.value = _state.value.copy(turnstileToken = token)
    }

    private fun resetTurnstile() {
        _state.value = _state.value.copy(
            turnstileToken = null,
            turnstileReset = _state.value.turnstileReset + 1,
        )
    }

    fun login(onSuccess: (AuthSession) -> Unit) {
        val current = _state.value
        if (!current.canSubmit) {
            if (current.requireTurnstile && current.turnstileToken == null) {
                _state.value = current.copy(error = "请完成人机验证")
            }
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, error = null)
            val result = authRepository.login(
                username = current.username,
                password = current.password,
                turnstileToken = current.turnstileToken,
            )
            when (result) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(submitting = false, error = null)
                    onSuccess(result.value)
                }
                is ApiResult.Err -> {
                    _state.value = _state.value.copy(
                        submitting = false,
                        error = result.error.userMessage(),
                    )
                    if (current.requireTurnstile) resetTurnstile()
                }
            }
        }
    }
}
