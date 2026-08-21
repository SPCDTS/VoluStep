package dev.spcdts.volumemapper.core

import kotlin.math.abs

/**
 * A normalized control point used by [MappingCurve]. Both coordinates are in 0..1.
 */
data class MappingPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && x in 0.0..1.0) { "x must be finite and in 0..1" }
        require(y.isFinite() && y in 0.0..1.0) { "y must be finite and in 0..1" }
    }
}

/** Selects a deterministic x value when [MappingCurve.inverse] encounters a plateau. */
enum class InverseBias {
    LOWER,
    MIDPOINT,
    UPPER,
}

/**
 * An immutable, monotonic, piecewise-linear mapping from normalized key position x to volume y.
 *
 * The x axis always covers 0..1. The y endpoints may be moved, which lets a profile express a
 * non-zero floor or a safety cap. Adjacent points may share a y value, but x values are strictly
 * ordered and separated by [minimumXSpacing].
 */
class MappingCurve(
    points: List<MappingPoint>,
    val minimumXSpacing: Double = DEFAULT_MINIMUM_X_SPACING,
) {
    val points: List<MappingPoint> = points.toList()

    init {
        require(minimumXSpacing.isFinite() && minimumXSpacing > 0.0) {
            "minimumXSpacing must be finite and positive"
        }
        require(this.points.size >= 2) { "A curve needs at least two points" }
        require(abs(this.points.first().x) <= EPSILON) { "The first point must have x = 0" }
        require(abs(this.points.last().x - 1.0) <= EPSILON) { "The last point must have x = 1" }

        this.points.zipWithNext().forEachIndexed { index, (left, right) ->
            require(right.x - left.x + EPSILON >= minimumXSpacing) {
                "Points $index and ${index + 1} are too close or out of order"
            }
            require(right.y + EPSILON >= left.y) {
                "Curve must be monotonic: y at ${index + 1} is below y at $index"
            }
        }
    }

    /** Evaluates the curve. Values outside 0..1 are clamped to the endpoint values. */
    fun evaluate(input: Double): Double {
        require(input.isFinite()) { "input must be finite" }
        if (input <= points.first().x) return points.first().y
        if (input >= points.last().x) return points.last().y

        val rightIndex = firstIndexWithXAtLeast(input)
        val left = points[rightIndex - 1]
        val right = points[rightIndex]
        val fraction = (input - left.x) / (right.x - left.x)
        return lerp(left.y, right.y, fraction)
    }

    /**
     * Returns an x whose mapped value is [output]. For a horizontal segment, [bias] selects which
     * point on that plateau should be used. Outputs outside the curve's range clamp to an endpoint.
     */
    fun inverse(output: Double, bias: InverseBias = InverseBias.MIDPOINT): Double {
        require(output.isFinite()) { "output must be finite" }
        if (output < points.first().y - EPSILON) return points.first().x
        if (output > points.last().y + EPSILON) return points.last().x

        val firstExact = points.indexOfFirst { abs(it.y - output) <= EPSILON }
        if (firstExact >= 0) {
            var lastExact = firstExact
            while (lastExact + 1 < points.size &&
                abs(points[lastExact + 1].y - output) <= EPSILON
            ) {
                lastExact += 1
            }
            val lower = points[firstExact].x
            val upper = points[lastExact].x
            return when (bias) {
                InverseBias.LOWER -> lower
                InverseBias.MIDPOINT -> (lower + upper) / 2.0
                InverseBias.UPPER -> upper
            }
        }

        for (index in 1 until points.size) {
            val left = points[index - 1]
            val right = points[index]
            if (output > left.y && output < right.y) {
                val fraction = (output - left.y) / (right.y - left.y)
                return lerp(left.x, right.x, fraction)
            }
        }

        // The epsilon comparisons above can leave a value microscopically outside the endpoint.
        return if (output <= points.first().y) points.first().x else points.last().x
    }

    /**
     * Moves one point while preserving all curve invariants. Endpoint x values remain fixed; their
     * y values may move. Interior x/y values are clamped between their neighbours.
     */
    fun movePoint(index: Int, requestedX: Double, requestedY: Double): MappingCurve {
        require(index in points.indices) { "Point index is outside the curve" }
        require(requestedX.isFinite() && requestedY.isFinite()) {
            "Requested coordinates must be finite"
        }

        val lastIndex = points.lastIndex
        val constrainedX = when (index) {
            0 -> 0.0
            lastIndex -> 1.0
            else -> requestedX.coerceIn(
                points[index - 1].x + minimumXSpacing,
                points[index + 1].x - minimumXSpacing,
            )
        }
        val constrainedY = requestedY.coerceIn(
            minimumValue = if (index == 0) 0.0 else points[index - 1].y,
            maximumValue = if (index == lastIndex) 1.0 else points[index + 1].y,
        )

        return MappingCurve(
            points = points.toMutableList().apply {
                this[index] = MappingPoint(constrainedX, constrainedY)
            },
            minimumXSpacing = minimumXSpacing,
        )
    }

    /** Inserts a control point. The requested x must leave enough room on both sides. */
    fun addPoint(requestedX: Double, requestedY: Double = evaluate(requestedX)): MappingCurve {
        require(requestedX.isFinite() && requestedX > 0.0 && requestedX < 1.0) {
            "A new point must have x strictly inside 0..1"
        }
        require(requestedY.isFinite()) { "Requested y must be finite" }

        val rightIndex = firstIndexWithXAtLeast(requestedX)
        val left = points[rightIndex - 1]
        val right = points[rightIndex]
        require(requestedX - left.x + EPSILON >= minimumXSpacing) {
            "New point is too close to its left neighbour"
        }
        require(right.x - requestedX + EPSILON >= minimumXSpacing) {
            "New point is too close to its right neighbour"
        }

        val point = MappingPoint(requestedX, requestedY.coerceIn(left.y, right.y))
        return MappingCurve(
            points = points.toMutableList().apply { add(rightIndex, point) },
            minimumXSpacing = minimumXSpacing,
        )
    }

    /** Removes an interior point. Endpoints cannot be removed. */
    fun removePoint(index: Int): MappingCurve {
        require(index in 1 until points.lastIndex) { "Only an interior point can be removed" }
        return MappingCurve(
            points = points.toMutableList().apply { removeAt(index) },
            minimumXSpacing = minimumXSpacing,
        )
    }

    /**
     * Computes the signed area under the curve between two x values. Endpoint values are extended
     * as constants outside 0..1. This is useful for frame-rate-independent hold acceleration.
     */
    fun integral(fromX: Double, toX: Double): Double {
        require(fromX.isFinite() && toX.isFinite()) { "Integral bounds must be finite" }
        if (abs(fromX - toX) <= EPSILON) return 0.0
        if (fromX > toX) return -integral(toX, fromX)

        var cursor = fromX
        var area = 0.0

        if (cursor < 0.0) {
            val right = minOf(toX, 0.0)
            area += (right - cursor) * points.first().y
            cursor = right
        }

        val internalEnd = minOf(toX, 1.0)
        while (cursor < internalEnd - EPSILON) {
            val rightIndex = firstIndexWithXGreaterThan(cursor.coerceAtLeast(0.0))
            val segmentRight = points[rightIndex]
            val right = minOf(internalEnd, segmentRight.x)
            val leftValue = evaluate(cursor)
            val rightValue = evaluate(right)
            area += (right - cursor) * (leftValue + rightValue) / 2.0
            cursor = right
        }

        if (toX > 1.0) {
            val left = maxOf(cursor, 1.0)
            area += (toX - left) * points.last().y
        }
        return area
    }

    private fun firstIndexWithXAtLeast(x: Double): Int {
        var low = 0
        var high = points.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (points[middle].x + EPSILON >= x) high = middle else low = middle + 1
        }
        return low.coerceAtLeast(1)
    }

    private fun firstIndexWithXGreaterThan(x: Double): Int {
        var low = 0
        var high = points.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (points[middle].x > x + EPSILON) high = middle else low = middle + 1
        }
        return low.coerceIn(1, points.lastIndex)
    }

    override fun equals(other: Any?): Boolean =
        other is MappingCurve &&
            points == other.points &&
            minimumXSpacing == other.minimumXSpacing

    override fun hashCode(): Int = 31 * points.hashCode() + minimumXSpacing.hashCode()

    override fun toString(): String =
        "MappingCurve(points=$points, minimumXSpacing=$minimumXSpacing)"

    companion object {
        const val DEFAULT_MINIMUM_X_SPACING: Double = 0.02
        private const val EPSILON: Double = 1e-9

        fun linear(): MappingCurve = MappingPreset.LINEAR.createCurve()

        private fun lerp(start: Double, end: Double, fraction: Double): Double =
            start + (end - start) * fraction
    }
}

/** Curated starting points for the visual editor. All remain fully editable. */
enum class MappingPreset(private val controlPoints: List<MappingPoint>) {
    LINEAR(
        listOf(
            MappingPoint(0.0, 0.0),
            MappingPoint(0.25, 0.25),
            MappingPoint(0.5, 0.5),
            MappingPoint(0.75, 0.75),
            MappingPoint(1.0, 1.0),
        ),
    ),
    LOW_VOLUME_FINE(
        listOf(
            MappingPoint(0.0, 0.0),
            MappingPoint(0.25, 0.05),
            MappingPoint(0.5, 0.15),
            MappingPoint(0.75, 0.42),
            MappingPoint(1.0, 1.0),
        ),
    ),
    S_CURVE(
        listOf(
            MappingPoint(0.0, 0.0),
            MappingPoint(0.2, 0.08),
            MappingPoint(0.5, 0.5),
            MappingPoint(0.8, 0.92),
            MappingPoint(1.0, 1.0),
        ),
    ),
    NIGHT_CAP(
        listOf(
            MappingPoint(0.0, 0.0),
            MappingPoint(0.25, 0.03),
            MappingPoint(0.5, 0.09),
            MappingPoint(0.75, 0.2),
            MappingPoint(1.0, 0.45),
        ),
    ),
    ;

    fun createCurve(minimumXSpacing: Double = MappingCurve.DEFAULT_MINIMUM_X_SPACING): MappingCurve =
        MappingCurve(controlPoints, minimumXSpacing)
}
