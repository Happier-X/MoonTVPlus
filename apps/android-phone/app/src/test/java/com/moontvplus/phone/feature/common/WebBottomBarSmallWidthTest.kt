package com.moontvplus.phone.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.moontvplus.ui.model.MainTab
import com.moontvplus.ui.shell.WebBottomBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网页版底栏每项固定 20vw。320dp 下 7 项（含直播入口）时每项 64dp，
 * 多出的项靠横滑，而不是被等分挤成 45dp 后截断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h800dp-xxhdpi")
class WebBottomBarSmallWidthTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun sevenTabsKeepTwentyPercentWidth() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    WebBottomBar(selected = MainTab.HOME, onSelect = {}, tabs = MainTab.entries)
                }
            }
        }

        MainTab.entries.forEach { tab ->
            rule.onNodeWithTag("bottom-tab-${tab.name}", useUnmergedTree = true)
                .assertWidthIsEqualTo(64.dp)
        }
    }
}
