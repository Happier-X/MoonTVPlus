package com.moontvplus.phone.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.moontvplus.ui.component.FilterBar
import com.moontvplus.ui.model.FilterCategory
import com.moontvplus.ui.model.FilterOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 320dp 窄屏回归：筛选浮层必须收在屏幕内，选项不能被弹出窗口挤到屏外。
 *
 * 1.9.16 的悬浮筛选面板在 320dp 下按 maxWidth - 24dp 收窄；
 * 这条测试锁住宽度与选项可点，避免以后改回固定 300dp 后窄屏溢出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h800dp-xxhdpi")
class FilterBarSmallWidthTest {

    @get:Rule
    val rule = createComposeRule()

    private val categories = listOf(
        FilterCategory("type", "类型", listOf(FilterOption("全部", "all"), FilterOption("电影", "movie"))),
        FilterCategory("region", "地区", listOf(FilterOption("全部", "all"), FilterOption("华语", "chinese"))),
        FilterCategory("year", "年代", listOf(FilterOption("全部", "all"), FilterOption("2020年代", "2020s"))),
    )

    @Test
    fun filterPanelStaysInside320dpScreen() {
        var picked: Pair<String, String>? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    FilterBar(
                        categories = categories,
                        values = emptyMap(),
                        onChange = { key, value -> picked = key to value },
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }

        rule.onNodeWithText("年代").performClick()
        rule.waitForIdle()

        val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes()
        assertEquals("筛选面板应作为独立浮层窗口存在", 2, roots.size)
        val pageWidth = roots.maxOf { it.size.width }
        val panelWidth = roots.minOf { it.size.width }
        assertEquals("320dp @ xxhdpi 的主窗口宽度", 960, pageWidth)
        assertTrue(
            "浮层宽 ${panelWidth}px 超出了 320dp 屏幕的可用宽度",
            panelWidth <= 296 * 3,
        )

        listOf("全部", "2020年代").forEach { label ->
            rule.onAllNodesWithText(label).fetchSemanticsNodes().forEach { node ->
                assertTrue(
                    "选项「$label」超出浮层边界",
                    node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= panelWidth,
                )
            }
            rule.onNodeWithText(label).assertIsDisplayed()
        }

        rule.onNodeWithText("2020年代").performClick()
        rule.runOnIdle {
            assertEquals("year" to "2020s", picked)
            assertEquals("选中后浮层应关闭", 1, rule.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        }
    }
}
