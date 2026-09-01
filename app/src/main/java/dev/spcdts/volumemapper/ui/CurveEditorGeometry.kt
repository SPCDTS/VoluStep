package dev.spcdts.volumemapper.ui

/**
 * 绘图区几何的单一来源。
 *
 * 手势命中、Canvas 绘制和设备测试必须使用同一组留白；修改这里后，端点光晕与
 * 坐标轴仍会同步，测试也不会继续向旧坐标发送拖动事件。
 */
internal object CurveEditorGeometry {
    const val PLOT_LEFT_PADDING_DP = 56f
    const val PLOT_RIGHT_PADDING_DP = 18f
    const val PLOT_TOP_PADDING_DP = 18f
    const val PLOT_BOTTOM_PADDING_DP = 30f
}
