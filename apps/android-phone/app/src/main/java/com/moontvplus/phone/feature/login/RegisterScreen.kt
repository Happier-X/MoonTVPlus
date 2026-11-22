package com.moontvplus.phone.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Info
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
import com.moontvplus.phone.di.appViewModel
import com.moontvplus.ui.component.WebCard
import com.moontvplus.ui.component.WebHint
import com.moontvplus.ui.component.WebPrimaryButton
import com.moontvplus.ui.component.WebTextField
import com.moontvplus.ui.theme.WebColors

/**
 * 原生注册页：与网页版 /register 一致 ——
 * 用户名/密码/确认密码/邀请码（按站点配置）+ Turnstile（按站点配置）。
 */
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onRegistered: (String) -> Unit,
    viewModel: RegisterViewModel = appViewModel {
        RegisterViewModel(it.graph.authRepository, it.graph.settingsStore, it.graph.serverProvider)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WebCard {
                Text(
                    text = "创建账号",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = WebColors.Brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                WebTextField(
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    placeholder = "用户名（字母/数字/下划线，3-20 位）",
                    leadingIcon = Icons.Outlined.Person,
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                WebTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    placeholder = "密码（至少 6 位）",
                    leadingIcon = Icons.Outlined.Lock,
                    password = true,
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                WebTextField(
                    value = state.confirmPassword,
                    onValueChange = viewModel::onConfirmChange,
                    placeholder = "确认密码",
                    leadingIcon = Icons.Outlined.Lock,
                    password = true,
                    enabled = !state.submitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                )
                if (state.requireInviteCode) {
                    Spacer(Modifier.height(6.dp))
                    WebTextField(
                        value = state.inviteCode,
                        onValueChange = viewModel::onInviteChange,
                        placeholder = "邀请码",
                        leadingIcon = Icons.Outlined.Info,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }

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

                state.error?.let { error ->
                    Spacer(Modifier.height(10.dp))
                    WebHint(error, isError = true)
                }

                Spacer(Modifier.height(16.dp))
                WebPrimaryButton(
                    text = if (state.submitting) "正在注册…" else "注册",
                    onClick = { viewModel.register(onRegistered) },
                    enabled = state.canSubmit,
                    loading = state.submitting,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = onBack,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("返回登录", fontSize = 13.sp, color = WebColors.Brand)
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
