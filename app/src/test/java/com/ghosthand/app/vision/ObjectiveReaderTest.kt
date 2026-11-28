package com.ghosthand.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectiveReaderTest {

    @Test
    fun readsExplicitObjectiveLabelAndCounterFromOcrLine() {
        val words = listOf(
            OcrWord("2", 0.80f, 0.15f, 0.02f, 0.03f),
            OcrWord("5", 0.85f, 0.15f, 0.02f, 0.03f)
        )
        val lines = listOf(
            OcrWord("Flowers 2/5", 0.82f, 0.15f, 0.16f, 0.04f)
        )

        val snapshot = ObjectiveReader().read(words, lines, 720, 1280)

        assertEquals(1, snapshot.counters.size)
        assertEquals(ObjectiveType.FLOWER, snapshot.counters.single().type)
        assertEquals(2, snapshot.counters.single().current)
        assertEquals(5, snapshot.counters.single().target)
    }

    @Test
    fun doesNotGuessObjectiveTypeFromNumbersAlone() {
        val words = listOf(OcrWord("3", 0.8f, 0.2f, 0.02f, 0.03f))
        val snapshot = ObjectiveReader().read(words, emptyList(), 720, 1280)

        assertTrue(snapshot.counters.isEmpty())
        assertEquals(listOf("3"), snapshot.unclassifiedNumericTokens)
        assertFalse(snapshot.hasRecognizedObjectives)
    }

    @Test
    fun recognizesBombFireworkCrateAndScoreLabels() {
        val labels = listOf(
            "Bombs 1/3",
            "Fireworks 0/2",
            "Crates 4/6",
            "Score 1200"
        )
        val lines = labels.mapIndexed { index, label ->
            OcrWord(label, 0.5f, 0.12f + index * 0.1f, 0.25f, 0.04f)
        }

        val snapshot = ObjectiveReader().read(emptyList(), lines, 720, 1280)

        assertEquals(
            listOf(
                ObjectiveType.BOMB,
                ObjectiveType.FIREWORK,
                ObjectiveType.CRATE,
                ObjectiveType.SCORE
            ),
            snapshot.counters.map { it.type }
        )
        assertEquals(listOf(3, 2, 6, null), snapshot.counters.map { it.target })
    }

    @Test
    fun doesNotMatchObjectiveNamesAsSubstrings() {
        val lines = listOf(
            OcrWord("bombastic bonus", 0.5f, 0.2f, 0.3f, 0.04f),
            OcrWord("fireworker display", 0.5f, 0.3f, 0.3f, 0.04f)
        )
        val snapshot = ObjectiveReader().read(emptyList(), lines, 720, 1280)

        assertTrue(snapshot.counters.isEmpty())
    }

    @Test
    fun keepsSameTypeObjectivesAtDifferentPositionsOnOneRow() {
        val lines = listOf(
            OcrWord("Flowers 1/4", 0.20f, 0.15f, 0.12f, 0.04f),
            OcrWord("Flowers 2/6", 0.75f, 0.15f, 0.12f, 0.04f)
        )

        val snapshot = ObjectiveReader().read(emptyList(), lines, 720, 1280)

        assertEquals(2, snapshot.counters.size)
        assertEquals(listOf(1, 2), snapshot.counters.map { it.current })
        assertEquals(listOf(4, 6), snapshot.counters.map { it.target })
    }

    @Test
    fun preservesCounterRatiosWhenOcrSplitsThemFromIcons() {
        val words = listOf(OcrWord("2/7", 0.8f, 0.2f, 0.05f, 0.03f))
        val snapshot = ObjectiveReader().read(words, emptyList(), 720, 1280)

        assertTrue(snapshot.counters.isEmpty())
        assertEquals(listOf("2/7"), snapshot.unclassifiedNumericTokens)
    }

    @Test
    fun recognizesOtherExplicitObjectiveLabels() {
        val labels = listOf(
            "Butterflies 1/4",
            "Apples 0/2",
            "Balloons 3/6",
            "Gems 2/5"
        )
        val lines = labels.mapIndexed { index, text ->
            OcrWord(text, 0.5f, 0.1f + index * 0.1f, 0.2f, 0.04f)
        }
        val snapshot = ObjectiveReader().read(emptyList(), lines, 720, 1280)

        assertEquals(
            listOf(
                ObjectiveType.BUTTERFLY,
                ObjectiveType.APPLE,
                ObjectiveType.BALLOON,
                ObjectiveType.GEM
            ),
            snapshot.counters.map { it.type }
        )
        assertEquals(listOf(4, 2, 6, 5), snapshot.counters.map { it.target })
    }
}
