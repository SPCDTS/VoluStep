package dev.spcdts.volumemapper.core

/**
 * 用户保存的媒体音量快捷值。
 *
 * 数值是 Android 媒体音量的实际 index，而非百分比或相对于曲线基准的 offset。构造时会
 * 排序并去重，使内存状态与持久化格式始终保持稳定；负数不属于有效的媒体音量 index。
 */
class FixedVolumePresets(indices: Iterable<Int> = emptyList()) {
    val indices: List<Int> = indices
        .map { index ->
            require(index >= 0) { "A fixed volume index cannot be negative" }
            index
        }
        .distinct()
        .sorted()

    override fun equals(other: Any?): Boolean =
        this === other || (other is FixedVolumePresets && indices == other.indices)

    override fun hashCode(): Int = indices.hashCode()

    override fun toString(): String = "FixedVolumePresets(indices=$indices)"
}
