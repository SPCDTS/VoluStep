package dev.spcdts.volumemapper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
        composeRule.onNodeWithText("按键次数 → 音量 index").assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS).assertIsDisplayed()

        // 固定横轴的次数设置、状态选择和整数 index 精调都必须可由标准控件访问。
        composeRule.onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_INPUT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.STEP_SELECTOR)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.INDEX_INPUT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.UNDO)
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
