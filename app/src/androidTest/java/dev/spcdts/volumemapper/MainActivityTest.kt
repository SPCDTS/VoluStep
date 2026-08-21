package dev.spcdts.volumemapper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import dev.spcdts.volumemapper.ui.CurveEditorTestTags
import dev.spcdts.volumemapper.ui.VolumeMapperTestTags
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainNavigationAndCurveEditorAreReachable() {
        composeRule.onNodeWithText("音量映射器").assertIsDisplayed()
        composeRule.onNodeWithText("曲线").performClick()
        composeRule.onNodeWithText("x(t) → V 映射").assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS).assertIsDisplayed()

        // 先滚到同一个 LazyColumn item 底部，避免在矮屏幕上直接查询尚未组合的后续 item。
        composeRule.onNodeWithTag(CurveEditorTestTags.Y_SLIDER)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.X_SLIDER)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("控制点 2 的逻辑位置 x")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("控制点 2 的目标媒体音量 V")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag(VolumeMapperTestTags.CURVE_SCREEN_LIST)
            .performScrollToNode(hasTestTag(VolumeMapperTestTags.PRESET_SECTION))
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_SECTION).assertIsDisplayed()
        composeRule.onNodeWithText("线性").performClick().assertIsSelected()

        // 按稳定语义标记让 LazyColumn 组合屏外 item，不依赖可变的界面文案。
        composeRule.onNodeWithTag(VolumeMapperTestTags.CURVE_SCREEN_LIST)
            .performScrollToNode(hasTestTag(VolumeMapperTestTags.KEY_BEHAVIOUR_CARD))
        composeRule.onNodeWithTag(VolumeMapperTestTags.KEY_BEHAVIOUR_CARD)
            .assertIsDisplayed()
    }
}
