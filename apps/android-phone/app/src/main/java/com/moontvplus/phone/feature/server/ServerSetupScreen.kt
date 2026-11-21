package com.moontvplus.phone.feature.server

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
import androidx.compose.material.icons.outlined.Place
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

/** 服务器地址页：沿用网页版登录卡的样子（白卡 + 下划线输入 + 绿色按钮）。 */
@Composable
fun ServerSetupScreen(
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
    viewModel: ServerSetupViewModel = appViewModel {
        ServerSetupViewModel(it.graph.serverRepository, it.graph.settingsStore)
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WebCard {
                Text(
                    text = "MoonTVPlus",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = WebColors.Brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "填写你的站点地址，例如 https://tv.example.com",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                WebTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    placeholder = "服务器地址",
                    leadingIcon = Icons.Outlined.Place,
                    enabled = !state.probing,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )

                if (state.error != null) {
                    Spacer(Modifier.height(8.dp))
                    WebHint(state.error.orEmpty(), isError = true)
                }

                Spacer(Modifier.height(18.dp))
                WebPrimaryButton(
                    text = if (state.probing) "正在连接…" else "连接服务器",
                    onClick = viewModel::probe,
                    enabled = state.input.isNotBlank(),
                    loading = state.probing,
                )

                if (state.readyAddress != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.siteName?.takeIf { it.isNotBlank() } ?: "已连接站点",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "版本 ${state.siteVersion ?: "未知"} · 存储 ${state.storageType ?: "未知"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    WebPrimaryButton(
                        text = if (state.saving) "正在保存…" else "保存并继续",
                        onClick = { viewModel.save(onDone) },
                        enabled = true,
                        loading = state.saving,
                    )
                }

                if (state.input.startsWith("http://", ignoreCase = true)) {
                    Spacer(Modifier.height(10.dp))
                    WebHint("当前使用明文 HTTP，请仅在可信的局域网内使用")
                }

                if (state.readyAddress == null && state.input.isNotBlank() && !state.probing) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = viewModel::probe, modifier = Modifier.fillMaxWidth()) {
                        Text("重新检测", fontSize = 13.sp, color = WebColors.Brand)
                    }
                }

                if (onCancel != null) {
                    TextButton(
                        onClick = onCancel,
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("取消", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
