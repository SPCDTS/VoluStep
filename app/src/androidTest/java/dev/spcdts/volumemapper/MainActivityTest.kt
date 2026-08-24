package dev.spcdts.volumemapper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
        composeRule.onNodeWithText("音量曲线").assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS).assertIsDisplayed()

        // 次数设置保持在主编辑区；更低频的精调控件默认折叠。
        composeRule.onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_INPUT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.DETAILS_TOGGLE)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(CurveEditorTestTags.STEP_SELECTOR)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.INDEX_INPUT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.UNDO)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.REDO)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag(VolumeMapperTestTags.CURVE_SCREEN_LIST)
            .performScrollToNode(hasTestTag(VolumeMapperTestTags.PRESET_SECTION))
        composeRule.onNodeWithTag(VolumeMapperTestTags.PRESET_SECTION).assertIsDisplayed()

        // 按稳定语义标记让 LazyColumn 组合屏外 item，不依赖可变的界面文案。
        composeRule.onNodeWithTag(VolumeMapperTestTags.CURVE_SCREEN_LIST)
            .performScrollToNode(hasTestTag(VolumeMapperTestTags.KEY_BEHAVIOUR_CARD))
        composeRule.onNodeWithTag(VolumeMapperTestTags.KEY_BEHAVIOUR_CARD)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(VolumeMapperTestTags.KEY_BEHAVIOUR_TOGGLE)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("显示系统音量浮层")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
