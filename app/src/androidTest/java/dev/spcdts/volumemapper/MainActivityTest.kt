package dev.spcdts.volumemapper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import dev.spcdts.volumemapper.data.SettingsRepository
import dev.spcdts.volumemapper.data.VolumeMapperSettings
import dev.spcdts.volumemapper.ui.CurveEditorTestTags
import dev.spcdts.volumemapper.ui.VolumeMapperTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var settingsRepository: SettingsRepository? = null
    private var originalSettings: VolumeMapperSettings? = null

    @Before
    fun captureSettings() {
        val graph =
            (composeRule.activity.application as VolumeMapperApplication).graph
        val repository = graph.settingsRepository
        composeRule.waitUntil(10_000L) { repository.hasLoadedInitialSettings }
        settingsRepository = repository
        originalSettings = repository.settings.value
    }

    @After
    fun restoreSettings() {
        val repository = settingsRepository ?: return
        val settings = originalSettings ?: return
        runBlocking { repository.replaceSettingsAndAwait(settings) }
    }

    @Test
    fun mainNavigationAndCurveEditorAreReachable() {
        composeRule.onNodeWithText("音量映射器").assertIsDisplayed()
        composeRule.onNodeWithText("曲线").performClick()
        composeRule.onNodeWithText("音量曲线").assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.CANVAS).assertIsDisplayed()

        // K 与 P 独立设置保持在主编辑区；更低频的精调控件默认折叠。
        composeRule.onNodeWithTag(CurveEditorTestTags.PRESS_COUNT_INPUT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT)
            .performScrollTo()
            .assertIsDisplayed()

        val originalPressCount = textFieldValue(CurveEditorTestTags.PRESS_COUNT_INPUT)
        val originalControlPointCount =
            textFieldValue(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT)
        val originalSelectedControlPoint =
            textValue(CurveEditorTestTags.SELECTED_CONTROL_POINT_VALUE)
        val replacementControlPointCount =
            if (originalControlPointCount == "2") "3" else "2"
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT)
            .performTextReplacement(replacementControlPointCount)
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT)
            .performImeAction()
        composeRule.waitUntil {
            textFieldValue(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT) ==
                replacementControlPointCount
        }
        assertEquals(
            "修改 P 不能改变 K",
            originalPressCount,
            textFieldValue(CurveEditorTestTags.PRESS_COUNT_INPUT),
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.UNDO)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil {
            textFieldValue(CurveEditorTestTags.CONTROL_POINT_COUNT_INPUT) ==
                originalControlPointCount
        }
        assertEquals(
            "撤销 P 变更应恢复原控制点选择",
            originalSelectedControlPoint,
            textValue(CurveEditorTestTags.SELECTED_CONTROL_POINT_VALUE),
        )
        assertEquals(
            "撤销 P 变更应无损恢复完整曲线",
            originalSettings?.outputMap,
            settingsRepository?.settings?.value?.outputMap,
        )
        composeRule.onNodeWithTag(CurveEditorTestTags.DETAILS_TOGGLE)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(CurveEditorTestTags.CONTROL_POINT_SELECTOR)
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

    private fun textFieldValue(tag: String): String =
        composeRule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    private fun textValue(tag: String): String =
        composeRule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }
}
