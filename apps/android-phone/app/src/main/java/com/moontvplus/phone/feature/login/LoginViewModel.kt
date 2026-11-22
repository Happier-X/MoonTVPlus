package com.moontvplus.phone.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.common.ApiResult
import com.moontvplus.phone.core.common.userMessage
import com.moontvplus.phone.core.storage.CredentialVault
import com.moontvplus.phone.core.storage.SettingsStore
import com.moontvplus.phone.core.network.SessionManager
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.model.QrCreateResponse
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.data.repository.AuthRepository
import com.moontvplus.phone.data.repository.QrPollResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 2_000L

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val settingsStore: SettingsStore,
    serverProvider: ServerProvider,
    private val credentialVault: CredentialVault,
    private val sessions: SessionManager,
) : ViewModel() {

    data class UiState(
        val storageType: String? = null,
        val siteName: String? = null,
        val origin: String? = null,
        val username: String = "",
        val password: String = "",
        val requireTurnstile: Boolean = false,
        val turnstileSiteKey: String? = null,
        /** 登录页「记住密码」；勾选时凭据只进 Keystore 加密存储。 */
        val rememberPassword: Boolean = false,
        val enableRegistration: Boolean = false,
        val requireRegistrationInviteCode: Boolean = false,
        val registrationRequireTurnstile: Boolean = false,
        val turnstileToken: String? = null,
        /** 自增后重建 Turnstile WebView，等价网页端的 turnstile.reset() */
        val turnstileReset: Int = 0,
        val submitting: Boolean = false,
        val error: String? = null,
        val tab: LoginTab = LoginTab.PASSWORD,
        /** 扫码登录：二维码内容与状态（pending / scanned / confirmed / expired / cancelled） */
        val qr: QrCreateResponse? = null,
        val qrStatus: String = "idle",
        val qrMessage: String? = null,
        val qrCountdown: Int = 0,
    ) {
        val askUsername: Boolean
            get() = !storageType.equals("localstorage", ignoreCase = true)

        val canSubmit: Boolean
            get() = !submitting &&
                password.isNotEmpty() &&
                (!askUsername || username.isNotBlank()) &&
                (!requireTurnstile || turnstileToken != null)

        val hasSavedCredentials: Boolean
            get() = username.isNotBlank() && password.isNotEmpty()
    }

    private var qrPolling: Job? = null
    private var qrCountdownJob: Job? = null
    private var onQrLoggedIn: ((AuthSession) -> Unit)? = null

    private val _state = MutableStateFlow(UiState(origin = serverProvider.current()?.origin))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.snapshot()
            val remembered = credentialVault.load()
            _state.value = _state.value.copy(
                storageType = settings.storageType,
                siteName = settings.siteName,
                origin = serverProvider.current()?.origin,
                username = remembered?.username.orEmpty(),
                password = remembered?.password.orEmpty(),
                rememberPassword = remembered != null,
                requireTurnstile = settings.loginRequireTurnstile,
                turnstileSiteKey = settings.turnstileSiteKey,
                enableRegistration = settings.enableRegistration,
                requireRegistrationInviteCode = settings.requireRegistrationInviteCode,
                registrationRequireTurnstile = settings.registrationRequireTurnstile,
            )
        }
    }

    /** 取消勾选立即清除已保存的密码；重新勾选等下次登录成功再写入。 */
    fun setRememberPassword(enabled: Boolean) {
        _state.value = _state.value.copy(rememberPassword = enabled)
        if (!enabled) viewModelScope.launch { credentialVault.clear() }
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

    fun selectTab(tab: LoginTab) {
        if (_state.value.tab == tab) return
        _state.value = _state.value.copy(tab = tab, error = null)
        if (tab == LoginTab.QR && _state.value.qr == null) startQrLogin()
    }

    /**
     * 扫码登录：申请二维码后轮询状态。
     *
     * 手机（已登录的网页）扫码并在手机上确认，界面在 `confirmed` 时拿到登录态。
     */
    fun startQrLogin(onSuccess: ((AuthSession) -> Unit)? = null) {
        if (onSuccess != null) onQrLoggedIn = onSuccess
        qrPolling?.cancel()
        qrCountdownJob?.cancel()
        _state.value = _state.value.copy(qrStatus = "creating", qrMessage = null, qrCountdown = 0)

        viewModelScope.launch {
            when (val result = authRepository.createQrCode()) {
                is ApiResult.Ok -> {
                    _state.value = _state.value.copy(
                        qr = result.value,
                        qrStatus = "pending",
                        qrMessage = null,
                    )
                    startCountdown(result.value.expiresAt)
                    startPolling(result.value.token)
                }

                is ApiResult.Err -> _state.value = _state.value.copy(
                    qrStatus = "error",
                    qrMessage = result.error.userMessage(),
                )
            }
        }
    }

    private fun startPolling(token: String) {
        qrPolling = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                when (val result = authRepository.pollQrCode(token)) {
                    is ApiResult.Ok -> handlePoll(result.value)
                    is ApiResult.Err -> _state.value = _state.value.copy(
                        qrMessage = result.error.userMessage(),
                    )
                }
                if (_state.value.qrStatus == "confirmed") break
            }
        }
    }

    private fun handlePoll(poll: QrPollResult) {
        when (poll.status) {
            "confirmed" -> {
                val session = poll.session ?: return
                qrPolling?.cancel()
                qrCountdownJob?.cancel()
                _state.value = _state.value.copy(qrStatus = "confirmed", qrMessage = "登录成功，正在进入")
                onQrLoggedIn?.invoke(session)
            }

            "scanned" -> _state.value = _state.value.copy(
                qrStatus = "scanned",
                qrMessage = "已扫码，请在手机上确认",
            )

            "cancelled" -> {
                qrPolling?.cancel()
                qrCountdownJob?.cancel()
                _state.value = _state.value.copy(qrStatus = "cancelled", qrMessage = "已取消，请刷新二维码")
            }

            "expired", "used" -> {
                qrPolling?.cancel()
                qrCountdownJob?.cancel()
                _state.value = _state.value.copy(qrStatus = "expired", qrMessage = "二维码已过期，请刷新")
            }

            else -> Unit
        }
    }

    /** 与网页一致：二维码 120 秒有效，倒计时结束自动换一张。 */
    private fun startCountdown(expiresAt: Long) {
        qrCountdownJob?.cancel()
        qrCountdownJob = viewModelScope.launch {
            while (true) {
                val left = ((expiresAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                _state.value = _state.value.copy(qrCountdown = left)
                if (left <= 0) {
                    if (_state.value.qrStatus == "pending" || _state.value.qrStatus == "scanned") {
                        startQrLogin()
                    }
                    break
                }
                delay(1_000)
            }
        }
    }

    override fun onCleared() {
        qrPolling?.cancel()
        qrCountdownJob?.cancel()
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
                    if (current.rememberPassword) {
                        credentialVault.save(current.username, current.password)
                        sessions.save(
                            result.value.copy(
                                savedUsername = current.username.trim().takeIf { it.isNotEmpty() },
                                savedPassword = current.password,
                            )
                        )
                    } else {
                        credentialVault.clear()
                    }
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

/** 登录页两个 Tab：账号密码 / 扫码登录。 */
enum class LoginTab(val label: String) {
    PASSWORD("账号登录"),
    QR("扫码登录"),
}
