package com.moontvplus.phone.update

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.moontvplus.phone.core.ui.theme.MoonTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Render actual Compose dialogs with Android's native graphics; these are not device installation tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun versionNotesProgressRetryAndLatestStatesRenderAndRemainScrollable() {
        val updater = AppUpdater(RuntimeEnvironment.getApplication())
        val update = ClientUpdate("1.9.5", "新增应用内更新，改善连接稳定性。\n安装前请保存正在播放的进度。", ReleaseAsset(
            "moontvplus-phone-1.9.5-release-signed.apk",
            "https://github.com/Happier-X/MoonTVPlus/releases/download/client-v1.9.5/moontvplus-phone-1.9.5-release-signed.apk", 16 * 1024 * 1024,
        ))
        val state = mutableStateOf(UpdateUiState(UpdateStage.AVAILABLE, visible = true, update = update))
        val dark = mutableStateOf(false)
        compose.setContent { MoonTvTheme(darkTheme = dark.value) { UpdateDialog(state.value, updater) {} } }
        compose.onNodeWithText("当前版本 ${com.moontvplus.phone.BuildConfig.VERSION_NAME}（${com.moontvplus.phone.BuildConfig.VERSION_CODE}）").assertIsDisplayed()
        compose.onNodeWithText("新版本 1.9.5").assertIsDisplayed()
        compose.onNodeWithText("下载更新").assertIsDisplayed()
        screenshot("available-light")
        compose.runOnIdle { dark.value = true }
        screenshot("available-dark")
        compose.runOnIdle { state.value = state.value.copy(stage = UpdateStage.DOWNLOADING, received = 8 * 1024 * 1024, total = update.apk.size) }
        compose.onNodeWithText("已下载 8.0 / 16.0 MB（50%）").assertIsDisplayed()
        compose.onNodeWithText("取消下载").assertIsDisplayed()
        screenshot("downloading")
        compose.runOnIdle { state.value = state.value.copy(stage = UpdateStage.ERROR, message = "网络连接失败，请检查网络后重试。") }
        compose.onNodeWithText("重新下载").assertIsDisplayed()
        screenshot("retry")
        compose.runOnIdle { state.value = state.value.copy(stage = UpdateStage.READY, message = "下载与校验完成，可安装更新。") }
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        screenshot("ready")
        compose.runOnIdle { state.value = UpdateUiState(UpdateStage.LATEST, visible = true, message = "没有发现适用于此设备的更高版本正式安装包。") }
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        screenshot("latest")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/reports/update-ui").apply { mkdirs() }
        val root = compose.onNode(isDialog()).fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest
        val view = root.view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
