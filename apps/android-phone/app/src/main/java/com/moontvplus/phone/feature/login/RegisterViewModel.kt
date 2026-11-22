package com.moontvplus.phone.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.common.ApiResult
import com.moontvplus.phone.core.common.userMessage
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.storage.SettingsStore
import com.moontvplus.phone.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 原生注册页：字段与校验对齐网页版 /register。 */
class RegisterViewModel(
    private val authRepository: AuthRepository,
    private val settingsStore: SettingsStore,
    serverProvider: ServerProvider,
) : ViewModel() {

    data class UiState(
        val username: String = "",
        val password: String = "",
        val confirmPassword: String = "",
        val inviteCode: String = "",
        val requireInviteCode: Boolean = false,
        val requireTurnstile: Boolean = false,
        val turnstileSiteKey: String? = null,
        val origin: String? = null,
        val turnstileToken: String? = null,
        val turnstileReset: Int = 0,
        val submitting: Boolean = false,
        val error: String? = null,
    ) {
        val canSubmit: Boolean
            get() = !submitting &&
                username.isNotBlank() &&
                password.isNotBlank() &&
                confirmPassword.isNotBlank() &&
                (!requireInviteCode || inviteCode.isNotBlank()) &&
                (!requireTurnstile || turnstileToken != null)
    }

    private val _state = MutableStateFlow(UiState(origin = serverProvider.current()?.origin))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.snapshot()
            _state.value = _state.value.copy(
                requireInviteCode = settings.requireRegistrationInviteCode,
                requireTurnstile = settings.registrationRequireTurnstile,
                turnstileSiteKey = settings.turnstileSiteKey,
                origin = serverProvider.current()?.origin,
            )
        }
    }

    fun onUsernameChange(value: String) = update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = update { it.copy(password = value, error = null) }
    fun onConfirmChange(value: String) = update { it.copy(confirmPassword = value, error = null) }
    fun onInviteChange(value: String) = update { it.copy(inviteCode = value, error = null) }
    fun onTurnstileToken(token: String?) = update { it.copy(turnstileToken = token) }

    private fun update(block: (UiState) -> UiState) {
        _state.value = block(_state.value)
    }

    fun register(onRegistered: (String) -> Unit) {
        val current = _state.value
        val username = current.username.trim()
        val validation = when {
            !Regex("^[a-zA-Z0-9_]{3,20}$").matches(username) -> "用户名只能包含字母、数字、下划线，长度3-20位"
            current.password.length < 6 -> "密码长度至少为6位"
            current.password != current.confirmPassword -> "两次输入的密码不一致"
            current.requireInviteCode && current.inviteCode.isBlank() -> "请输入邀请码"
            current.requireTurnstile && current.turnstileToken == null -> "请完成人机验证"
            else -> null
        }
        if (validation != null) {
            update { it.copy(error = validation) }
            return
        }
        viewModelScope.launch {
            update { it.copy(submitting = true, error = null) }
            when (val result = authRepository.register(
                username = username,
                password = current.password,
                inviteCode = current.inviteCode.takeIf { current.requireInviteCode },
                turnstileToken = current.turnstileToken,
            )) {
                is ApiResult.Ok -> {
                    update { it.copy(submitting = false) }
                    onRegistered(result.value)
                }
                is ApiResult.Err -> update {
                    it.copy(
                        submitting = false,
                        error = result.error.userMessage(),
                        turnstileReset = if (current.requireTurnstile) it.turnstileReset + 1 else it.turnstileReset,
                        turnstileToken = null,
                    )
                }
            }
        }
    }
}
