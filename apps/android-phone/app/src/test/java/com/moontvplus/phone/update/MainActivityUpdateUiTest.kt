package com.moontvplus.phone.update

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moontvplus.phone.MainActivity
import com.moontvplus.phone.MoonTvApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = MoonTvApplication::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityUpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realActivityOpensAboutBeforeLoginAndShowsBuildVersion() {
        compose.onNodeWithText("关于与更新").assertIsDisplayed().performClick()
        compose.onNodeWithText("关于 MoonTVPlus").assertIsDisplayed()
        compose.onNodeWithText("安卓客户端 ${com.moontvplus.phone.BuildConfig.VERSION_NAME}（${com.moontvplus.phone.BuildConfig.VERSION_CODE}）").assertIsDisplayed()
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        val view = (compose.onNode(isDialog()).fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val file = File("build/reports/update-ui/about-activity.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("关于与更新").assertIsDisplayed()
    }
}
