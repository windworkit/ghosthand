package com.ghosthand.app.vision

import kotlin.math.abs

/**
 * Debounces explicitly labeled objective counters across captured frames.
 *
 * This class deliberately does not infer icon identity or game mechanics.
 * It reports only a counter change that is visible in two consecutive
 * observations at approximately the same screen position.
 */
data class ObjectiveProgressEvent(
    val type: ObjectiveType,
    val previous: Int,
    val current: Int,
    val target: Int,
    val screenX: Float,
    val screenY: Float
) {
    val amount: Int get() = current - previous
}

data class ObjectiveProgressUpdate(
    val increases: List<ObjectiveProgressEvent>,
    val decreases: List<ObjectiveProgressEvent>
)

class ObjectiveProgressTracker(
    private val positionTolerance: Float = 0.06f,
    private val confirmationsRequired: Int = 2
) {
    private class TrackedCounter(
        val type: ObjectiveType,
        val target: Int,
        var screenX: Float,
        var screenY: Float,
        var confirmedCurrent: Int
    ) {
        var pendingCurrent: Int? = null
        var pendingObservations: Int = 0
    }

    private val tracked = mutableListOf<TrackedCounter>()

    init {
        require(positionTolerance > 0f && positionTolerance <= 1f)
        require(confirmationsRequired >= 2)
    }

    fun reset() {
        tracked.clear()
    }

    fun observe(snapshot: ObjectiveSnapshot): ObjectiveProgressUpdate {
        val increases = mutableListOf<ObjectiveProgressEvent>()
        val decreases = mutableListOf<ObjectiveProgressEvent>()
        val matched = mutableSetOf<TrackedCounter>()

        snapshot.counters.forEach { counter ->
            val current = counter.current ?: return@forEach
            val target = counter.target ?: return@forEach
            if (target <= 0 || current < 0 || current > target ||
                !counter.screenX.isFinite() || !counter.screenY.isFinite()
            ) return@forEach

            val candidates = tracked.asSequence()
                .filter { it !in matched && it.type == counter.type && it.target == target }
                .map { candidate ->
                    val dx = candidate.screenX - counter.screenX
                    val dy = candidate.screenY - counter.screenY
                    candidate to (dx * dx + dy * dy)
                }
                .filter { (_, distanceSquared) ->
                    distanceSquared <= positionTolerance * positionTolerance
                }
                .sortedBy { it.second }
                .toList()

            // If two same-type counters are equally close, do not guess which
            // one changed. A new baseline is safer than attributing progress
            // to the wrong objective.
            val trackedCounter = when {
                candidates.isEmpty() -> null
                candidates.size > 1 &&
                    abs(candidates[0].second - candidates[1].second) < 0.0001f -> null
                else -> candidates.first().first
            } ?: TrackedCounter(
                counter.type,
                target,
                counter.screenX,
                counter.screenY,
                current
            ).also { tracked.add(it) }

            matched.add(trackedCounter)
            trackedCounter.screenX = counter.screenX
            trackedCounter.screenY = counter.screenY

            if (current == trackedCounter.confirmedCurrent) {
                trackedCounter.pendingCurrent = null
                trackedCounter.pendingObservations = 0
                return@forEach
            }

            if (trackedCounter.pendingCurrent == current) {
                trackedCounter.pendingObservations++
            } else {
                trackedCounter.pendingCurrent = current
                trackedCounter.pendingObservations = 1
            }

            if (trackedCounter.pendingObservations < confirmationsRequired) {
                return@forEach
            }

            val event = ObjectiveProgressEvent(
                type = counter.type,
                previous = trackedCounter.confirmedCurrent,
                current = current,
                target = target,
                screenX = counter.screenX,
                screenY = counter.screenY
            )
            if (current > trackedCounter.confirmedCurrent) {
                increases.add(event)
            } else {
                decreases.add(event)
            }
            trackedCounter.confirmedCurrent = current
            trackedCounter.pendingCurrent = null
            trackedCounter.pendingObservations = 0
        }

        return ObjectiveProgressUpdate(increases, decreases)
    }
}
