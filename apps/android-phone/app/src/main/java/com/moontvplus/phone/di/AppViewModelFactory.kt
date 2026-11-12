package com.moontvplus.phone.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moontvplus.phone.MoonTvApplication

/** 取当前应用的依赖容器。 */
fun appContainer(context: android.content.Context): AppContainer {
    val app = context.applicationContext as MoonTvApplication
    return app.container
}

/**
 * 替代 `hiltViewModel()`：用依赖容器手动构造 ViewModel。
 *
 * 手写装配的代价是每个页面要写一行构造，好处是彻底摆脱 Hilt 的 Android 绑定，
 * 后面把界面搬到 Compose Multiplatform 时这层不用再动。
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val context = LocalContext.current
    val container = appContainer(context)
    return viewModel(
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}
