package com.moontvplus.phone.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moontvplus.phone.di.appViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moontvplus.phone.core.model.AuthSession

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
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = state.siteName?.takeIf { it.isNotBlank() } ?: "MoonTVPlus",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.askUsername) "请使用站点账号登录" else "请输入站点访问密码",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        if (state.askUsername) {
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") },
                singleLine = true,
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            singleLine = true,
            enabled = !state.submitting,
            visualTransformation = PasswordVisualTransformation(),
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
                Text(
                    text = "站点要求人机验证，但未取到验证配置，请重新连接服务器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (hint != null) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = hint.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.login(onLoggedIn) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSubmit,
        ) {
            if (state.submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
                Text("正在登录…")
            } else {
                Text("登录")
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onChangeServer, enabled = !state.submitting) {
            Text("更换服务器")
        }
    }
}
