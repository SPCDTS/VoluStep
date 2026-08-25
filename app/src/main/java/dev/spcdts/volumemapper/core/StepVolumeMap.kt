package dev.spcdts.volumemapper.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An immutable output map authored against an integer reference range.
 *
 * [basisSpan] is the reference route's `maxIndex - minIndex`. [pressCount] is the number of short
 * presses from minimum to maximum. Authored control points use integer [pressPositions] on the
 * `0..pressCount` x grid and integer [offsets] on the `0..basisSpan` y grid. Both axes are strictly
 * ordered between fixed endpoints.
 */
class StepVolumeMap(
    val basisSpan: Int,
    val pressCount: Int,
    pressPositions: List<Int>,
    offsets: List<Int>,
) {
    val pressPositions: List<Int> = pressPositions.toList()

    /** Read-only compatibility view. Integer [pressPositions] remain the canonical x state. */
    val normalizedXs: List<Double> = this.pressPositions.map { position ->
        position.toDouble() / pressCount.toDouble()
    }
    val offsets: List<Int> = offsets.toList()
    val controlPointCount: Int = this.offsets.size
    val controlSegmentCount: Int = this.offsets.lastIndex

    /**
     * Source-compatible bridge for callers that still supply normalized x coordinates. Values are
     * projected onto the strict integer press grid before this instance is created.
     */
    @Deprecated(
        message = "Use integer pressPositions",
        replaceWith = ReplaceWith("StepVolumeMap(basisSpan, pressCount, pressPositions, offsets)"),
    )
    constructor(
        basisSpan: Int,
        pressCount: Int,
        normalizedXs: List<Double>,
        offsets: List<Int>,
        @Suppress("UNUSED_PARAMETER") normalizedCompatibility: Unit = Unit,
    ) : this(
        basisSpan = basisSpan,
        pressCount = pressCount,
        pressPositions = projectNormalizedPressPositions(normalizedXs, pressCount),
        offsets = offsets,
    )

    /** Source-compatible constructor for v2 maps, whose control points were uniformly spaced. */
    constructor(
        basisSpan: Int,
        pressCount: Int,
        offsets: List<Int>,
    ) : this(
        basisSpan = basisSpan,
        pressCount = pressCount,
        pressPositions = uniformPressPositions(offsets.size, pressCount),
        offsets = offsets,
    )

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
        require(this.pressPositions.size == controlPointCount) {
            "pressPositions and offsets must have the same size"
        }
        require(controlSegmentCount <= pressCount) {
            "controlPointCount cannot exceed pressCount + 1"
        }
        require(this.pressPositions.first() == 0) { "The first press position must be 0" }
        require(this.pressPositions.last() == pressCount) {
            "The last press position must equal pressCount"
        }
        require(this.pressPositions.all { it in 0..pressCount }) {
            "press positions must be inside 0..pressCount"
        }
        require(this.pressPositions.zipWithNext().all { (left, right) -> right > left }) {
            "press positions must be strictly increasing"
        }
        require(this.offsets.first() == 0) { "The first offset must be 0" }
        require(this.offsets.last() == basisSpan) { "The last offset must equal basisSpan" }
        require(this.offsets.zipWithNext().all { (left, right) -> right > left }) {
            "offsets must be strictly increasing"
        }
    }

    /** Returns the authored integer x coordinate for [controlPointIndex]. */
    fun pressPositionAt(controlPointIndex: Int): Int {
        require(controlPointIndex in 0..controlSegmentCount) {
            "controlPointIndex is outside this map"
        }
        return pressPositions[controlPointIndex]
    }

    /** Returns the normalized rendering coordinate derived from [pressPositionAt]. */
    fun normalizedXAt(controlPointIndex: Int): Double {
        require(controlPointIndex in 0..controlSegmentCount) {
            "controlPointIndex is outside this map"
        }
        return normalizedXs[controlPointIndex]
    }

    /** Evaluates the authored curve in reference-range offset units. */
    fun evaluateOffset(normalizedX: Double): Double {
        require(normalizedX.isFinite()) { "normalizedX must be finite" }
        if (normalizedX <= 0.0) return 0.0
        if (normalizedX >= 1.0) return basisSpan.toDouble()

        val rightIndex = firstControlPointAtOrAfter(normalizedX)
        val leftIndex = rightIndex - 1
        val leftX = normalizedXAt(leftIndex)
        val rightX = normalizedXAt(rightIndex)
        val fraction = (normalizedX - leftX) / (rightX - leftX)
        val left = offsets[leftIndex].toDouble()
        val right = offsets[rightIndex].toDouble()
        return left + (right - left) * fraction
    }

    /** Finds the authored control point nearest to [normalizedX]. */
    fun closestControlPointIndex(normalizedX: Double): Int {
        require(normalizedX.isFinite()) { "normalizedX must be finite" }
        val constrained = normalizedX.coerceIn(0.0, 1.0)
        return normalizedXs.indices.minBy { index -> abs(normalizedXs[index] - constrained) }
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
            pressPositions = pressPositions,
            offsets = offsets.toMutableList().apply { this[controlPointIndex] = constrained },
        )
    }

    /** Moves one interior control point horizontally on the strict integer press grid. */
    fun withPressPosition(controlPointIndex: Int, requestedPressPosition: Int): StepVolumeMap {
        require(controlPointIndex in 0..controlSegmentCount) {
            "controlPointIndex is outside this map"
        }
        if (controlPointIndex == 0 || controlPointIndex == controlSegmentCount) return this
        val constrained = requestedPressPosition.coerceIn(
            pressPositions[controlPointIndex - 1] + 1,
            pressPositions[controlPointIndex + 1] - 1,
        )
        if (constrained == pressPositions[controlPointIndex]) return this
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = pressCount,
            pressPositions = pressPositions.toMutableList().apply {
                this[controlPointIndex] = constrained
            },
            offsets = offsets,
        )
    }

    /** Compatibility mutator that immediately quantizes onto [pressPositions]. */
    @Deprecated("Use withPressPosition")
    fun withNormalizedX(controlPointIndex: Int, requestedNormalizedX: Double): StepVolumeMap {
        require(requestedNormalizedX.isFinite()) { "requestedNormalizedX must be finite" }
        return withPressPosition(
            controlPointIndex,
            (requestedNormalizedX * pressCount.toDouble()).roundToInt(),
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
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = pressCount,
            pressPositions = pressPositions,
            offsets = pushed,
        )
    }

    /** Moves an interior control point on both integer axes; y crossings minimally push neighbours. */
    fun moveControlPointPushing(
        controlPointIndex: Int,
        requestedPressPosition: Int,
        requestedOffset: Int,
    ): StepVolumeMap {
        require(controlPointIndex in 0..controlSegmentCount) {
            "controlPointIndex is outside this map"
        }
        if (controlPointIndex == 0 || controlPointIndex == controlSegmentCount) return this

        val constrainedPressPosition = requestedPressPosition.coerceIn(
            pressPositions[controlPointIndex - 1] + 1,
            pressPositions[controlPointIndex + 1] - 1,
        )
        val constrainedOffset = requestedOffset.coerceIn(
            minimumValue = controlPointIndex,
            maximumValue = basisSpan - (controlSegmentCount - controlPointIndex),
        )
        if (
            constrainedPressPosition == pressPositions[controlPointIndex] &&
            constrainedOffset == offsets[controlPointIndex]
        ) {
            return this
        }

        val movedPressPositions = pressPositions.toMutableList().apply {
            this[controlPointIndex] = constrainedPressPosition
        }
        val pushedOffsets = offsets.toMutableList()
        pushedOffsets[controlPointIndex] = constrainedOffset
        for (index in controlPointIndex - 1 downTo 1) {
            pushedOffsets[index] = minOf(pushedOffsets[index], pushedOffsets[index + 1] - 1)
        }
        for (index in controlPointIndex + 1 until controlSegmentCount) {
            pushedOffsets[index] = maxOf(pushedOffsets[index], pushedOffsets[index - 1] + 1)
        }
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = pressCount,
            pressPositions = movedPressPositions,
            offsets = pushedOffsets,
        )
    }

    /** Compatibility bridge for callers that still pass normalized x. */
    @Deprecated("Use requestedPressPosition")
    fun moveControlPointPushing(
        controlPointIndex: Int,
        requestedNormalizedX: Double,
        requestedOffset: Int,
        @Suppress("UNUSED_PARAMETER") normalizedCompatibility: Unit = Unit,
    ): StepVolumeMap {
        require(requestedNormalizedX.isFinite()) { "requestedNormalizedX must be finite" }
        return moveControlPointPushing(
            controlPointIndex = controlPointIndex,
            requestedPressPosition =
                (requestedNormalizedX * pressCount.toDouble()).roundToInt(),
            requestedOffset = requestedOffset,
        )
    }

    /** Changes K and strictly re-projects every authored x onto the new integer press grid. */
    fun withPressCount(newPressCount: Int): StepVolumeMap {
        require(newPressCount > 0) { "newPressCount must be positive" }
        require(newPressCount <= basisSpan) { "newPressCount cannot exceed basisSpan" }
        require(newPressCount >= controlSegmentCount) {
            "newPressCount cannot be smaller than controlPointCount - 1"
        }
        if (newPressCount == pressCount) return this
        val projectedPressPositions = StrictIntegerProjection.project(
            targets = pressPositions.map { position ->
                position.toDouble() * newPressCount.toDouble() / pressCount.toDouble()
            },
            span = newPressCount,
        )
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = newPressCount,
            pressPositions = projectedPressPositions,
            offsets = offsets,
        )
    }

    /**
     * Changes P without changing K. New points retain all existing x coordinates and normally
     * split a line segment exactly; removals pick the interior point with the smallest integrated
     * shape error.
     */
    fun resampleControlPoints(newControlPointCount: Int): StepVolumeMap {
        require(newControlPointCount >= 2) { "newControlPointCount must be at least 2" }
        require(newControlPointCount <= pressCount + 1) {
            "newControlPointCount cannot exceed pressCount + 1"
        }
        if (newControlPointCount == controlPointCount) return this

        var result = this
        while (result.controlPointCount < newControlPointCount) {
            result = result.insertControlPoint()
        }
        while (result.controlPointCount > newControlPointCount) {
            result = result.removeLeastSignificantControlPoint()
        }
        return result
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
        val rebasedControlPointCount = minOf(controlPointCount, rebasedPressCount + 1)
        val shapeSource = if (rebasedControlPointCount == controlPointCount) {
            this
        } else {
            resampleControlPoints(rebasedControlPointCount)
        }
        val targets = shapeSource.offsets.map { offset ->
            offset.toDouble() * routeSpan.toDouble() / basisSpan.toDouble()
        }
        val shapePreservingCandidate = StepVolumeMap(
            basisSpan = routeSpan,
            pressCount = rebasedPressCount,
            pressPositions = StrictIntegerProjection.project(
                targets = shapeSource.pressPositions.map { position ->
                    position.toDouble() * rebasedPressCount.toDouble() /
                        shapeSource.pressCount.toDouble()
                },
                span = rebasedPressCount,
            ),
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

    private fun firstControlPointAtOrAfter(normalizedX: Double): Int {
        var low = 1
        var high = controlSegmentCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (normalizedXs[middle] >= normalizedX) high = middle else low = middle + 1
        }
        return low
    }

    private fun insertControlPoint(): StepVolumeMap {
        var bestSegment = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (segment in 0 until controlSegmentCount) {
            val pressGap = pressPositions[segment + 1] - pressPositions[segment]
            val offsetGap = offsets[segment + 1] - offsets[segment]
            if (pressGap < 2) continue
            val score = pressGap.toDouble() * maxOf(1, offsetGap).toDouble()
            if (score > bestScore + CONTROL_X_EPSILON) {
                bestSegment = segment
                bestScore = score
            }
        }
        check(bestSegment >= 0) {
            "No horizontal room exists despite controlPointCount <= pressCount"
        }
        val leftPressPosition = pressPositions[bestSegment]
        val rightPressPosition = pressPositions[bestSegment + 1]
        val insertedPressPosition = (
            leftPressPosition + (rightPressPosition - leftPressPosition) / 2
            ).coerceIn(leftPressPosition + 1, rightPressPosition - 1)
        val fraction = (insertedPressPosition - leftPressPosition).toDouble() /
            (rightPressPosition - leftPressPosition).toDouble()
        val insertedOffsetTarget = offsets[bestSegment] +
            (offsets[bestSegment + 1] - offsets[bestSegment]) * fraction
        val insertedPressPositions = pressPositions.toMutableList().apply {
            add(bestSegment + 1, insertedPressPosition)
        }
        val offsetTargets = offsets.map(Int::toDouble).toMutableList().apply {
            add(bestSegment + 1, insertedOffsetTarget)
        }
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = pressCount,
            pressPositions = insertedPressPositions,
            offsets = StrictIntegerProjection.project(offsetTargets, basisSpan),
        )
    }

    private fun removeLeastSignificantControlPoint(): StepVolumeMap {
        check(controlPointCount > 2)
        var removalIndex = 1
        var smallestError = Double.POSITIVE_INFINITY
        for (index in 1 until controlSegmentCount) {
            val leftPressPosition = pressPositions[index - 1]
            val rightPressPosition = pressPositions[index + 1]
            val fraction = (pressPositions[index] - leftPressPosition).toDouble() /
                (rightPressPosition - leftPressPosition).toDouble()
            val interpolatedOffset = offsets[index - 1] +
                (offsets[index + 1] - offsets[index - 1]) * fraction
            val verticalError = offsets[index] - interpolatedOffset
            val integratedError = verticalError * verticalError *
                (rightPressPosition - leftPressPosition).toDouble()
            if (integratedError < smallestError - CONTROL_X_EPSILON) {
                removalIndex = index
                smallestError = integratedError
            }
        }
        return StepVolumeMap(
            basisSpan = basisSpan,
            pressCount = pressCount,
            pressPositions = pressPositions.toMutableList().apply { removeAt(removalIndex) },
            offsets = offsets.toMutableList().apply { removeAt(removalIndex) },
        )
    }

    override fun equals(other: Any?): Boolean =
        other is StepVolumeMap &&
            basisSpan == other.basisSpan &&
            pressCount == other.pressCount &&
            pressPositions == other.pressPositions &&
            offsets == other.offsets

    override fun hashCode(): Int =
        31 * (31 * (31 * basisSpan + pressCount) + pressPositions.hashCode()) + offsets.hashCode()

    override fun toString(): String =
        "StepVolumeMap(basisSpan=$basisSpan, pressCount=$pressCount, " +
            "pressPositions=$pressPositions, offsets=$offsets)"

    companion object {
        @Deprecated("Integer press positions define the minimum spacing")
        const val MINIMUM_CONTROL_X_SPACING: Double = 1e-6
        private const val CONTROL_X_EPSILON: Double = 1e-12

        /** Creates the closest strict integer projection of a linear curve. */
        fun linear(
            basisSpan: Int,
            pressCount: Int,
            controlPointCount: Int = pressCount + 1,
        ): StepVolumeMap {
            require(basisSpan > 0) { "basisSpan must be positive" }
            require(pressCount > 0) { "pressCount must be positive" }
            require(pressCount <= basisSpan) { "pressCount cannot exceed basisSpan" }
            require(controlPointCount in 2..pressCount + 1) {
                "controlPointCount must be between 2 and pressCount + 1"
            }
            val segmentCount = controlPointCount - 1
            val offsetTargets = List(controlPointCount) { pointIndex ->
                pointIndex.toDouble() * basisSpan.toDouble() / segmentCount.toDouble()
            }
            return StepVolumeMap(
                basisSpan = basisSpan,
                pressCount = pressCount,
                pressPositions = uniformPressPositions(controlPointCount, pressCount),
                offsets = StrictIntegerProjection.project(offsetTargets, basisSpan),
            )
        }

        /**
         * Authors a legacy continuous curve on [basisSpan]. When its point count already matches
         * [controlPointCount], its x coordinates are projected onto the integer press grid;
         * otherwise it is sampled at uniformly projected press positions. A legacy non-zero
         * floor/safety cap is affinely expanded to the required 0..basisSpan endpoints. A flat
         * curve falls back to a linear shape.
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
            require(controlPointCount in 2..pressCount + 1) {
                "controlPointCount must be between 2 and pressCount + 1"
            }

            val targetPressPositions = if (curve.points.size == controlPointCount) {
                curve.points.map { point -> point.x * pressCount.toDouble() }
            } else {
                List(controlPointCount) { index ->
                    index.toDouble() * pressCount.toDouble() /
                        (controlPointCount - 1).toDouble()
                }
            }
            val pressPositions = StrictIntegerProjection.project(
                targets = targetPressPositions,
                span = pressCount,
            )
            val sampled = pressPositions.map { position ->
                curve.evaluate(position.toDouble() / pressCount.toDouble())
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
                pressPositions = pressPositions,
                offsets = StrictIntegerProjection.project(targets, basisSpan),
            )
        }

        private fun uniformPressPositions(count: Int, pressCount: Int): List<Int> {
            require(count in 2..pressCount + 1) {
                "controlPointCount must be between 2 and pressCount + 1"
            }
            return StrictIntegerProjection.project(
                targets = List(count) { index ->
                    index.toDouble() * pressCount.toDouble() / (count - 1).toDouble()
                },
                span = pressCount,
            )
        }

        private fun projectNormalizedPressPositions(
            normalizedXs: List<Double>,
            pressCount: Int,
        ): List<Int> {
            require(pressCount > 0) { "pressCount must be positive" }
            require(normalizedXs.size in 2..pressCount + 1) {
                "controlPointCount must be between 2 and pressCount + 1"
            }
            require(normalizedXs.all { value -> value.isFinite() && value in 0.0..1.0 }) {
                "normalized x values must be finite and inside 0..1"
            }
            require(abs(normalizedXs.first()) <= CONTROL_X_EPSILON) {
                "The first normalized x must be 0"
            }
            require(abs(normalizedXs.last() - 1.0) <= CONTROL_X_EPSILON) {
                "The last normalized x must be 1"
            }
            require(normalizedXs.zipWithNext().all { (left, right) -> right > left }) {
                "normalized x values must be strictly increasing"
            }
            return StrictIntegerProjection.project(
                targets = normalizedXs.map { value -> value * pressCount.toDouble() },
                span = pressCount,
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
