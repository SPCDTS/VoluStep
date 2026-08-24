package dev.spcdts.volumemapper.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * An immutable output map authored against an integer reference range.
 *
 * [basisSpan] is the reference route's `maxIndex - minIndex`. [pressCount] is the number of short
 * presses from minimum to maximum. [offsets] are independently configurable control-point values;
 * their x coordinates are implicit, uniform and include both endpoints. Integer control offsets
 * are strictly increasing, while the K + 1 runtime press states are sampled from their polyline.
 */
class StepVolumeMap(
    val basisSpan: Int,
    val pressCount: Int,
    offsets: List<Int>,
) {
    val offsets: List<Int> = offsets.toList()
    val controlPointCount: Int = this.offsets.size
    val controlSegmentCount: Int = this.offsets.lastIndex

    /** Source-compatible constructor for v1 maps, where every press state was a control point. */
    constructor(
        basisSpan: Int,
        offsets: List<Int>,
    ) : this(
        basisSpan = basisSpan,
        pressCount = offsets.lastIndex,
        offsets = offsets,
    )

    init {
        require(basisSpan > 0) { "basisSpan must be positive" }
        require(pressCount > 0) { "pressCount must be positive" }
        require(pressCount <= basisSpan) { "pressCount cannot exceed basisSpan" }
        require(controlPointCount >= 2) { "A step map needs at least two control points" }
        require(controlSegmentCount <= basisSpan) {
            "controlPointCount cannot exceed basisSpan + 1"
        }
        require(this.offsets.first() == 0) { "The first offset must be 0" }
        require(this.offsets.last() == basisSpan) { "The last offset must equal basisSpan" }
        require(this.offsets.zipWithNext().all { (left, right) -> right > left }) {
            "offsets must be strictly increasing"
        }
    }

    /** Returns the implicit normalized x coordinate for [controlPointIndex]. */
    fun normalizedXAt(controlPointIndex: Int): Double {
        require(controlPointIndex in 0..controlSegmentCount) {
            "controlPointIndex is outside this map"
        }
        return controlPointIndex.toDouble() / controlSegmentCount.toDouble()
    }

    /** Evaluates the authored curve in reference-range offset units. */
    fun evaluateOffset(normalizedX: Double): Double {
        require(normalizedX.isFinite()) { "normalizedX must be finite" }
        if (normalizedX <= 0.0) return 0.0
        if (normalizedX >= 1.0) return basisSpan.toDouble()

        val scaled = normalizedX * controlSegmentCount.toDouble()
        val leftIndex = floor(scaled).toInt().coerceIn(0, controlSegmentCount - 1)
        val fraction = scaled - leftIndex.toDouble()
        val left = offsets[leftIndex].toDouble()
        val right = offsets[leftIndex + 1].toDouble()
        return left + (right - left) * fraction
    }

    /** Moves one authored y value while keeping endpoints fixed and all offsets strict. */
    fun withOffset(controlPointIndex: Int, requestedOffset: Int): StepVolumeMap {
        require(controlPointIndex in 0..controlSegmentCount) {
            "controlPointIndex is outside this map"
        }
        if (controlPointIndex == 0 || controlPointIndex == controlSegmentCount) return this
        val constrained = requestedOffset.coerceIn(
            minimumValue = offsets[controlPointIndex - 1] + 1,
            maximumValue = offsets[controlPointIndex + 1] - 1,
        )
        if (constrained == offsets[controlPointIndex]) return this
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = pressCount,
            offsets = offsets.toMutableList().apply { this[controlPointIndex] = constrained },
        )
    }

    /**
     * Moves one authored value and minimally pushes neighbours that would otherwise be crossed.
     * This is useful for touch drawing, where a strict clamp can make a dense curve feel stuck.
     */
    fun withOffsetPushing(controlPointIndex: Int, requestedOffset: Int): StepVolumeMap {
        require(controlPointIndex in 0..controlSegmentCount) {
            "controlPointIndex is outside this map"
        }
        if (controlPointIndex == 0 || controlPointIndex == controlSegmentCount) return this
        val constrained = requestedOffset.coerceIn(
            minimumValue = controlPointIndex,
            maximumValue = basisSpan - (controlSegmentCount - controlPointIndex),
        )
        if (constrained == offsets[controlPointIndex]) return this

        val pushed = offsets.toMutableList()
        pushed[controlPointIndex] = constrained
        for (index in controlPointIndex - 1 downTo 1) {
            pushed[index] = minOf(pushed[index], pushed[index + 1] - 1)
        }
        for (index in controlPointIndex + 1 until controlSegmentCount) {
            pushed[index] = maxOf(pushed[index], pushed[index - 1] + 1)
        }
        return StepVolumeMap(basisSpan = basisSpan, pressCount = pressCount, offsets = pushed)
    }

    /** Changes only K; the authored control-point polyline remains exactly the same. */
    fun withPressCount(newPressCount: Int): StepVolumeMap {
        require(newPressCount > 0) { "newPressCount must be positive" }
        require(newPressCount <= basisSpan) { "newPressCount cannot exceed basisSpan" }
        if (newPressCount == pressCount) return this
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = newPressCount,
            offsets = offsets,
        )
    }

    /** Resamples the authored polyline to a different number of uniformly spaced control points. */
    fun resampleControlPoints(newControlPointCount: Int): StepVolumeMap {
        require(newControlPointCount >= 2) { "newControlPointCount must be at least 2" }
        require(newControlPointCount <= basisSpan + 1) {
            "newControlPointCount cannot exceed basisSpan + 1"
        }
        if (newControlPointCount == controlPointCount) return this
        val newSegmentCount = newControlPointCount - 1
        val targets = List(newControlPointCount) { pointIndex ->
            evaluateOffset(pointIndex.toDouble() / newSegmentCount.toDouble())
        }
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = pressCount,
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
        if (basisSpan == routeSpan) return this
        val previouslyBound = bind(range)
        val rebasedPressCount = minOf(pressCount, routeSpan)
        val rebasedControlPointCount = minOf(controlPointCount, routeSpan + 1)
        val rebasedSegmentCount = rebasedControlPointCount - 1
        val targets = List(rebasedControlPointCount) { pointIndex ->
            val normalizedX = pointIndex.toDouble() / rebasedSegmentCount.toDouble()
            evaluateOffset(normalizedX) * routeSpan.toDouble() / basisSpan.toDouble()
        }
        val shapePreservingCandidate = StepVolumeMap(
            basisSpan = routeSpan,
            pressCount = rebasedPressCount,
            offsets = StrictIntegerProjection.project(targets, routeSpan),
        )
        if (shapePreservingCandidate.bind(range).indices == previouslyBound.indices) {
            return shapePreservingCandidate
        }

        // Integer control-point projection can otherwise change the route's current K + 1 targets.
        // Fall back to one control point per bound state so opening the editor is behavior-preserving.
        return StepVolumeMap(
            basisSpan = routeSpan,
            pressCount = previouslyBound.effectivePressCount,
            offsets = previouslyBound.indices.map { it - range.minIndex },
        )
    }

    private fun sampleTargets(samplePressCount: Int, outputSpan: Int): List<Double> =
        List(samplePressCount + 1) { stepIndex ->
            val normalizedX = stepIndex.toDouble() / samplePressCount.toDouble()
            evaluateOffset(normalizedX) * outputSpan.toDouble() / basisSpan.toDouble()
        }

    override fun equals(other: Any?): Boolean =
        other is StepVolumeMap &&
            basisSpan == other.basisSpan &&
            pressCount == other.pressCount &&
            offsets == other.offsets

    override fun hashCode(): Int = 31 * (31 * basisSpan + pressCount) + offsets.hashCode()

    override fun toString(): String =
        "StepVolumeMap(basisSpan=$basisSpan, pressCount=$pressCount, offsets=$offsets)"

    companion object {
        /** Creates the closest strict integer projection of a linear curve. */
        fun linear(
            basisSpan: Int,
            pressCount: Int,
            controlPointCount: Int = pressCount + 1,
        ): StepVolumeMap {
            require(basisSpan > 0) { "basisSpan must be positive" }
            require(pressCount > 0) { "pressCount must be positive" }
            require(pressCount <= basisSpan) { "pressCount cannot exceed basisSpan" }
            require(controlPointCount in 2..basisSpan + 1) {
                "controlPointCount must be between 2 and basisSpan + 1"
            }
            val segmentCount = controlPointCount - 1
            val targets = List(controlPointCount) { pointIndex ->
                pointIndex.toDouble() * basisSpan.toDouble() / segmentCount.toDouble()
            }
            return StepVolumeMap(
                basisSpan = basisSpan,
                pressCount = pressCount,
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
            controlPointCount: Int = pressCount + 1,
        ): StepVolumeMap {
            require(basisSpan > 0) { "basisSpan must be positive" }
            require(pressCount > 0) { "pressCount must be positive" }
            require(pressCount <= basisSpan) { "pressCount cannot exceed basisSpan" }
            require(controlPointCount in 2..basisSpan + 1) {
                "controlPointCount must be between 2 and basisSpan + 1"
            }

            val segmentCount = controlPointCount - 1
            val sampled = List(controlPointCount) { pointIndex ->
                curve.evaluate(pointIndex.toDouble() / segmentCount.toDouble())
            }
            val floor = sampled.first()
            val cap = sampled.last()
            if (abs(cap - floor) <= FLAT_OUTPUT_EPSILON) {
                return linear(basisSpan, pressCount, controlPointCount)
            }

            val targets = sampled.map { level ->
                (level - floor) * basisSpan.toDouble() / (cap - floor)
            }
            return StepVolumeMap(
                basisSpan = basisSpan,
                pressCount = pressCount,
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
