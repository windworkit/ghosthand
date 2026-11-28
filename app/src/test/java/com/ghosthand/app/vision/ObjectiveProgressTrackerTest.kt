package com.ghosthand.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveProgressTrackerTest {

    private fun snapshot(
        current: Int,
        target: Int,
        x: Float = 0.5f,
        y: Float = 0.12f
    ) = ObjectiveSnapshot(
        counters = listOf(
            ObjectiveCounterObservation(
                type = ObjectiveType.FLOWER,
                current = current,
                target = target,
                screenX = x,
                screenY = y,
                sourceText = "Flowers $current/$target"
            )
        ),
        unclassifiedNumericTokens = emptyList(),
        capturedAtWidth = 720,
        capturedAtHeight = 1280
    )

    @Test
    fun reportsProgressOnlyAfterTwoConsecutiveObservations() {
        val tracker = ObjectiveProgressTracker()

        assertTrue(tracker.observe(snapshot(2, 5)).increases.isEmpty())
        assertTrue(tracker.observe(snapshot(3, 5)).increases.isEmpty())

        val confirmed = tracker.observe(snapshot(3, 5))

        assertEquals(1, confirmed.increases.size)
        assertEquals(2, confirmed.increases.single().previous)
        assertEquals(3, confirmed.increases.single().current)
        assertEquals(1, confirmed.increases.single().amount)
    }

    @Test
    fun clearsPendingChangeWhenOriginalValueReturns() {
        val tracker = ObjectiveProgressTracker()
        tracker.observe(snapshot(2, 5))

        assertTrue(tracker.observe(snapshot(3, 5)).increases.isEmpty())
        assertTrue(tracker.observe(snapshot(2, 5)).increases.isEmpty())
        assertTrue(tracker.observe(snapshot(3, 5)).increases.isEmpty())

        val confirmed = tracker.observe(snapshot(3, 5))
        assertEquals(1, confirmed.increases.size)
        assertEquals(3, confirmed.increases.single().current)
    }

    @Test
    fun keepsSameTypeCountersAtDifferentPositionsSeparate() {
        val tracker = ObjectiveProgressTracker()
        val both = ObjectiveSnapshot(
            counters = listOf(
                ObjectiveCounterObservation(ObjectiveType.FLOWER, 1, 4, 0.2f, 0.12f, "Flowers 1/4"),
                ObjectiveCounterObservation(ObjectiveType.FLOWER, 2, 6, 0.8f, 0.12f, "Flowers 2/6")
            ),
            unclassifiedNumericTokens = emptyList(),
            capturedAtWidth = 720,
            capturedAtHeight = 1280
        )
        tracker.observe(both)

        val changedSecond = both.copy(
            counters = listOf(
                both.counters[0],
                both.counters[1].copy(current = 3, sourceText = "Flowers 3/6")
            )
        )
        assertTrue(tracker.observe(changedSecond).increases.isEmpty())
        val confirmed = tracker.observe(changedSecond)

        assertEquals(1, confirmed.increases.size)
        assertEquals(3, confirmed.increases.single().current)
        assertEquals(0.8f, confirmed.increases.single().screenX, 0.001f)
    }

    @Test
    fun reportsCounterDecreaseSeparatelyFromProgress() {
        val tracker = ObjectiveProgressTracker()
        tracker.observe(snapshot(3, 5))
        assertTrue(tracker.observe(snapshot(2, 5)).decreases.isEmpty())

        val confirmed = tracker.observe(snapshot(2, 5))

        assertTrue(confirmed.increases.isEmpty())
        assertEquals(1, confirmed.decreases.size)
        assertEquals(3, confirmed.decreases.single().previous)
        assertEquals(2, confirmed.decreases.single().current)
    }

    @Test
    fun ignoresCountersWithoutValidTargets() {
        val tracker = ObjectiveProgressTracker()
        val invalid = ObjectiveSnapshot(
            counters = listOf(
                ObjectiveCounterObservation(ObjectiveType.SCORE, 1200, null, 0.5f, 0.2f, "Score 1200"),
                ObjectiveCounterObservation(ObjectiveType.FLOWER, 7, 5, 0.5f, 0.3f, "Flowers 7/5")
            ),
            unclassifiedNumericTokens = emptyList(),
            capturedAtWidth = 720,
            capturedAtHeight = 1280
        )

        assertTrue(tracker.observe(invalid).increases.isEmpty())
        assertTrue(tracker.observe(invalid).decreases.isEmpty())
    }

    @Test
    fun resetDiscardsPreviousCounterBaselines() {
        val tracker = ObjectiveProgressTracker()
        tracker.observe(snapshot(2, 5))
        tracker.reset()

        assertTrue(tracker.observe(snapshot(3, 5)).increases.isEmpty())
        assertTrue(tracker.observe(snapshot(3, 5)).increases.isEmpty())
    }
}
