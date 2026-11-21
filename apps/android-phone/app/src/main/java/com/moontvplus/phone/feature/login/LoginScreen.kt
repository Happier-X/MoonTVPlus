package com.moontvplus.phone.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.di.appViewModel
import com.moontvplus.ui.component.WebCard
import com.moontvplus.ui.component.WebHint
import com.moontvplus.ui.component.WebPrimaryButton
import com.moontvplus.ui.component.WebTextField
import com.moontvplus.ui.theme.WebColors

/**
 * 登录页：与网页版 `/login` 一致 ——
 * 居中白卡（圆角 16、阴影），站点名 24sp 品牌绿，下划线输入框，绿色 48dp 按钮。
 */
@Composable
fun LoginScreen(
    reason: String?,
    onLoggedIn: (AuthSession) -> Unit,
    onChangeServer: () -> Unit,
    viewModel: LoginViewModel = appViewModel {
        LoginViewModel(it.graph.authRepository, it.graph.settingsStore, it.graph.serverProvider)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 输入错误优先，其次是外部原因（如会话过期）
    val hint = state.error ?: reason

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
                WebCard {
                    Text(
                        text = state.siteName?.takeIf { it.isNotBlank() } ?: "MoonTVPlus",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = WebColors.Brand,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))

                    if (state.askUsername) {
                        WebTextField(
                            value = state.username,
                            onValueChange = viewModel::onUsernameChange,
                            placeholder = "输入用户名",
                            leadingIcon = Icons.Outlined.Person,
                            enabled = !state.submitting,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    WebTextField(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChange,
                        placeholder = "输入访问密码",
                        leadingIcon = Icons.Outlined.Lock,
                        password = true,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (state.canSubmit) viewModel.login(onLoggedIn) },
                        ),
                    )

                    if (state.requireTurnstile) {
                        Spacer(Modifier.height(12.dp))
                        val siteKey = state.turnstileSiteKey
                        val origin = state.origin
                        if (siteKey != null && origin != null) {
                            TurnstileGate(
                                siteKey = siteKey,
                                origin = origin,
                                resetKey = state.turnstileReset,
                                onToken = viewModel::onTurnstileToken,
                            )
                        } else {
                            WebHint("站点要求人机验证，但未取到验证配置，请重新连接服务器", isError = true)
                        }
                    }

                    if (state.hasSavedCredentials && hint == null && !state.submitting) {
                        Spacer(Modifier.height(8.dp))
                        WebHint("已保留上次登录账号，${if (state.requireTurnstile) "完成人机验证后" else ""}可直接登录")
                    }

                    if (hint != null) {
                        Spacer(Modifier.height(10.dp))
                        WebHint(hint, isError = true)
                    }

                    Spacer(Modifier.height(18.dp))
                    WebPrimaryButton(
                        text = if (state.submitting) "正在登录…" else "登录",
                        onClick = { viewModel.login(onLoggedIn) },
                        enabled = state.canSubmit,
                        loading = state.submitting,
                    )

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onChangeServer,
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "更换服务器",
                            fontSize = 13.sp,
                            color = WebColors.Brand,
                        )
                    }
                }

            Text(
                text = "MoonTVPlus",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}
