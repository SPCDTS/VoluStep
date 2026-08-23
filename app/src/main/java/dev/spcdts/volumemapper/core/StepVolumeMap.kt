package dev.spcdts.volumemapper.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * An immutable output map authored against an integer reference range.
 *
 * [basisSpan] is the reference route's `maxIndex - minIndex`. The x coordinates are implicit and
 * uniformly spaced: [pressCount] is the number of short presses from minimum to maximum, while
 * [offsets] contains both endpoints and therefore has `pressCount + 1` entries. Integer offsets are
 * strictly increasing so every configured press has an observable reference-range effect.
 */
class StepVolumeMap(
    val basisSpan: Int,
    offsets: List<Int>,
) {
    val offsets: List<Int> = offsets.toList()
    val pressCount: Int = this.offsets.lastIndex

    init {
        require(basisSpan > 0) { "basisSpan must be positive" }
        require(this.offsets.size >= 2) { "A step map needs at least one press" }
        require(pressCount <= basisSpan) { "pressCount cannot exceed basisSpan" }
        require(this.offsets.first() == 0) { "The first offset must be 0" }
        require(this.offsets.last() == basisSpan) { "The last offset must equal basisSpan" }
        require(this.offsets.zipWithNext().all { (left, right) -> right > left }) {
            "offsets must be strictly increasing"
        }
    }

    /** Returns the implicit normalized x coordinate for [stepIndex]. */
    fun normalizedXAt(stepIndex: Int): Double {
        require(stepIndex in 0..pressCount) { "stepIndex is outside this map" }
        return stepIndex.toDouble() / pressCount.toDouble()
    }

    /** Evaluates the authored curve in reference-range offset units. */
    fun evaluateOffset(normalizedX: Double): Double {
        require(normalizedX.isFinite()) { "normalizedX must be finite" }
        if (normalizedX <= 0.0) return 0.0
        if (normalizedX >= 1.0) return basisSpan.toDouble()

        val scaled = normalizedX * pressCount.toDouble()
        val leftIndex = floor(scaled).toInt().coerceIn(0, pressCount - 1)
        val fraction = scaled - leftIndex.toDouble()
        val left = offsets[leftIndex].toDouble()
        val right = offsets[leftIndex + 1].toDouble()
        return left + (right - left) * fraction
    }

    /** Moves one authored y value while keeping endpoints fixed and all offsets strict. */
    fun withOffset(stepIndex: Int, requestedOffset: Int): StepVolumeMap {
        require(stepIndex in 0..pressCount) { "stepIndex is outside this map" }
        if (stepIndex == 0 || stepIndex == pressCount) return this
        val constrained = requestedOffset.coerceIn(
            minimumValue = offsets[stepIndex - 1] + 1,
            maximumValue = offsets[stepIndex + 1] - 1,
        )
        if (constrained == offsets[stepIndex]) return this
        return StepVolumeMap(
            basisSpan = basisSpan,
            offsets = offsets.toMutableList().apply { this[stepIndex] = constrained },
        )
    }

    /**
     * Moves one authored value and minimally pushes neighbours that would otherwise be crossed.
     * This is useful for touch drawing, where a strict clamp can make a dense curve feel stuck.
     */
    fun withOffsetPushing(stepIndex: Int, requestedOffset: Int): StepVolumeMap {
        require(stepIndex in 0..pressCount) { "stepIndex is outside this map" }
        if (stepIndex == 0 || stepIndex == pressCount) return this
        val constrained = requestedOffset.coerceIn(
            minimumValue = stepIndex,
            maximumValue = basisSpan - (pressCount - stepIndex),
        )
        if (constrained == offsets[stepIndex]) return this

        val pushed = offsets.toMutableList()
        pushed[stepIndex] = constrained
        for (index in stepIndex - 1 downTo 1) {
            pushed[index] = minOf(pushed[index], pushed[index + 1] - 1)
        }
        for (index in stepIndex + 1 until pressCount) {
            pushed[index] = maxOf(pushed[index], pushed[index - 1] + 1)
        }
        return StepVolumeMap(basisSpan = basisSpan, offsets = pushed)
    }

    /** Resamples the implicit curve to a different number of short presses on the same basis. */
    fun resample(newPressCount: Int): StepVolumeMap {
        require(newPressCount > 0) { "newPressCount must be positive" }
        require(newPressCount <= basisSpan) { "newPressCount cannot exceed basisSpan" }
        if (newPressCount == pressCount) return this
        val targets = sampleTargets(newPressCount, outputSpan = basisSpan)
        return StepVolumeMap(
            basisSpan = basisSpan,
            offsets = StrictIntegerProjection.project(targets, basisSpan),
        )
    }

    /**
     * Binds the authored offsets to [range].
     *
     * If the route has fewer integer intervals than configured presses, the curve is first
     * resampled to exactly that span. Otherwise all configured presses are retained. The integer
     * projection minimizes total squared error subject to fixed endpoints and strict monotonicity.
     */
    fun bind(range: RouteVolumeRange): BoundStepVolumeMap {
        val routeSpan = range.maxIndex - range.minIndex
        if (routeSpan == 0) {
            return BoundStepVolumeMap(
                source = this,
                range = range,
                indices = listOf(range.minIndex),
            )
        }

        val effectivePressCount = minOf(pressCount, routeSpan)
        val projectedOffsets = StrictIntegerProjection.project(
            targets = sampleTargets(effectivePressCount, outputSpan = routeSpan),
            span = routeSpan,
        )
        return BoundStepVolumeMap(
            source = this,
            range = range,
            indices = projectedOffsets.map { range.minIndex + it },
        )
    }

    /**
     * Makes [range] the new editable integer basis after binding this curve to it.
     * Fixed-volume ranges have no positive span and therefore cannot form an editable map.
     */
    fun rebase(range: RouteVolumeRange): StepVolumeMap {
        val routeSpan = range.maxIndex - range.minIndex
        require(routeSpan > 0) { "Cannot rebase onto a fixed-volume range" }
        val bound = bind(range)
        return StepVolumeMap(
            basisSpan = routeSpan,
            offsets = bound.indices.map { it - range.minIndex },
        )
    }

    private fun sampleTargets(samplePressCount: Int, outputSpan: Int): List<Double> =
        List(samplePressCount + 1) { stepIndex ->
            val normalizedX = stepIndex.toDouble() / samplePressCount.toDouble()
            evaluateOffset(normalizedX) * outputSpan.toDouble() / basisSpan.toDouble()
        }

    override fun equals(other: Any?): Boolean =
        other is StepVolumeMap && basisSpan == other.basisSpan && offsets == other.offsets

    override fun hashCode(): Int = 31 * basisSpan + offsets.hashCode()

    override fun toString(): String =
        "StepVolumeMap(basisSpan=$basisSpan, offsets=$offsets)"

    companion object {
        /** Creates the closest strict integer projection of a linear curve. */
        fun linear(basisSpan: Int, pressCount: Int): StepVolumeMap {
            require(basisSpan > 0) { "basisSpan must be positive" }
            require(pressCount > 0) { "pressCount must be positive" }
            require(pressCount <= basisSpan) { "pressCount cannot exceed basisSpan" }
            val targets = List(pressCount + 1) { stepIndex ->
                stepIndex.toDouble() * basisSpan.toDouble() / pressCount.toDouble()
            }
            return StepVolumeMap(
                basisSpan = basisSpan,
                offsets = StrictIntegerProjection.project(targets, basisSpan),
            )
        }

        /**
         * Samples a legacy continuous curve at uniform key positions and authors it on
         * [basisSpan]. A legacy non-zero floor/safety cap is affinely expanded to the required
         * 0..basisSpan endpoints. A completely flat legacy curve falls back to a linear shape.
         */
        fun fromCurve(
            curve: MappingCurve,
            basisSpan: Int,
            pressCount: Int,
        ): StepVolumeMap {
            require(basisSpan > 0) { "basisSpan must be positive" }
            require(pressCount > 0) { "pressCount must be positive" }
            require(pressCount <= basisSpan) { "pressCount cannot exceed basisSpan" }

            val sampled = List(pressCount + 1) { stepIndex ->
                curve.evaluate(stepIndex.toDouble() / pressCount.toDouble())
            }
            val floor = sampled.first()
            val cap = sampled.last()
            if (abs(cap - floor) <= FLAT_OUTPUT_EPSILON) return linear(basisSpan, pressCount)

            val targets = sampled.map { level ->
                (level - floor) * basisSpan.toDouble() / (cap - floor)
            }
            return StepVolumeMap(
                basisSpan = basisSpan,
                offsets = StrictIntegerProjection.project(targets, basisSpan),
            )
        }

        private const val FLAT_OUTPUT_EPSILON = 1e-12
    }
}

/** One target selected from a route-bound map. */
data class BoundVolumeStep(
    val stepIndex: Int,
    val index: Int,
)

/** A [StepVolumeMap] projected onto one route's actual integer volume range. */
class BoundStepVolumeMap internal constructor(
    val source: StepVolumeMap,
    val range: RouteVolumeRange,
    indices: List<Int>,
) {
    val indices: List<Int> = indices.toList()
    val effectivePressCount: Int = this.indices.lastIndex

    init {
        require(this.indices.isNotEmpty()) { "indices cannot be empty" }
        require(effectivePressCount == minOf(source.pressCount, range.maxIndex - range.minIndex)) {
            "indices contain the wrong effective press count"
        }
        require(this.indices.first() == range.minIndex) { "The first index must equal minIndex" }
        require(this.indices.last() == range.maxIndex) { "The last index must equal maxIndex" }
        require(this.indices.all(range::contains)) { "Every index must be inside the route range" }
        require(this.indices.zipWithNext().all { (left, right) -> right > left }) {
            "indices must be strictly increasing"
        }
    }

    /** Finds the next configured step strictly above or below [currentIndex]. */
    fun nextStep(currentIndex: Int, direction: VolumeDirection): BoundVolumeStep? {
        require(range.contains(currentIndex)) { "currentIndex is outside the route range" }
        val stepIndex = when (direction) {
            VolumeDirection.UP -> indices.indexOfFirst { it > currentIndex }
            VolumeDirection.DOWN -> indices.indexOfLast { it < currentIndex }
        }
        return stepIndex.takeIf { it >= 0 }?.let { BoundVolumeStep(it, indices[it]) }
    }

    override fun equals(other: Any?): Boolean =
        other is BoundStepVolumeMap &&
            source == other.source &&
            range == other.range &&
            indices == other.indices

    override fun hashCode(): Int = 31 * (31 * source.hashCode() + range.hashCode()) + indices.hashCode()

    override fun toString(): String =
        "BoundStepVolumeMap(source=$source, range=$range, indices=$indices)"
}

/**
 * Exact O(K * span) dynamic program for least-squares strict monotonic integer projection.
 * Equal-cost paths consistently prefer the quieter (lower-index) predecessor.
 */
internal object StrictIntegerProjection {
    fun project(targets: List<Double>, span: Int): List<Int> {
        require(span >= 0) { "span cannot be negative" }
        require(targets.isNotEmpty()) { "targets cannot be empty" }
        val pressCount = targets.lastIndex
        require(pressCount <= span) { "pressCount cannot exceed span" }
        require(targets.all { it.isFinite() }) { "targets must be finite" }
        if (pressCount == 0) {
            require(span == 0) { "A zero-press projection requires a zero span" }
            return listOf(0)
        }

        var previousCosts = DoubleArray(span + 1) { Double.POSITIVE_INFINITY }
        previousCosts[0] = square(targets[0])
        val predecessors = Array(pressCount + 1) { IntArray(span + 1) { NO_PREDECESSOR } }

        for (stepIndex in 1..pressCount) {
            val prefixCosts = DoubleArray(span + 1) { Double.POSITIVE_INFINITY }
            val prefixIndices = IntArray(span + 1) { NO_PREDECESSOR }
            var bestCost = Double.POSITIVE_INFINITY
            var bestIndex = NO_PREDECESSOR
            for (index in 0..span) {
                val candidateCost = previousCosts[index]
                if (candidateCost < bestCost) {
                    bestCost = candidateCost
                    bestIndex = index
                }
                prefixCosts[index] = bestCost
                prefixIndices[index] = bestIndex
            }

            val currentCosts = DoubleArray(span + 1) { Double.POSITIVE_INFINITY }
            val minimum = if (stepIndex == pressCount) span else stepIndex
            val maximum = if (stepIndex == pressCount) {
                span
            } else {
                span - (pressCount - stepIndex)
            }
            for (index in minimum..maximum) {
                val predecessor = prefixIndices[index - 1]
                if (predecessor == NO_PREDECESSOR) continue
                currentCosts[index] = prefixCosts[index - 1] + square(index - targets[stepIndex])
                predecessors[stepIndex][index] = predecessor
            }
            previousCosts = currentCosts
        }

        check(previousCosts[span].isFinite()) { "No valid strict projection exists" }
        val result = IntArray(pressCount + 1)
        var cursor = span
        for (stepIndex in pressCount downTo 1) {
            result[stepIndex] = cursor
            cursor = predecessors[stepIndex][cursor]
            check(cursor != NO_PREDECESSOR) { "Projection predecessor chain is incomplete" }
        }
        result[0] = 0
        return result.toList()
    }

    private fun square(value: Double): Double = value * value

    private const val NO_PREDECESSOR = -1
}
