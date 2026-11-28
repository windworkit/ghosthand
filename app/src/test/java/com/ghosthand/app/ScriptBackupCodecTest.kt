package com.ghosthand.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ScriptBackupCodecTest {

    @Test
    fun roundTripsAllScriptStepTypes() {
        val script = Script(
            id = UUID.randomUUID().toString(),
            name = "Woodoku level routine",
            steps = listOf(
                Step.Tap(0.2f, 0.3f),
                Step.LongPress(0.4f, 0.5f, 800),
                Step.Swipe(0.1f, 0.2f, 0.8f, 0.9f, 650),
                Step.Wait(500),
                Step.LoopStart(3),
                Step.Tap(0.5f, 0.6f),
                Step.LoopEnd
            ),
            repeats = 3,
            jitterMs = 120,
            jitterDp = 3.5f,
            keepAwake = true
        )

        val encoded = ScriptBackupCodec.encode(listOf(script))
        val decoded = ScriptBackupCodec.decode(encoded)

        assertEquals(listOf(script), decoded)
    }

    @Test
    fun backupCanContainNoScripts() {
        assertTrue(ScriptBackupCodec.decode(ScriptBackupCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun rejectsUnsafeIdsAndInvalidCoordinates() {
        val unsafeId = Script(id = "../outside", name = "bad")
        assertFalse(runCatching {
            ScriptBackupCodec.encode(listOf(unsafeId))
        }.isSuccess)

        val invalidCoordinates = Script(
            steps = listOf(Step.Tap(Float.NaN, 0.5f))
        )
        assertFalse(runCatching {
            ScriptBackupCodec.encode(listOf(invalidCoordinates))
        }.isSuccess)
    }

    @Test
    fun rejectsUnbalancedLoopMarkers() {
        val missingEnd = Script(steps = listOf(Step.LoopStart(2)))
        val missingStart = Script(steps = listOf(Step.LoopEnd))

        assertFalse(runCatching {
            ScriptBackupCodec.encode(listOf(missingEnd))
        }.isSuccess)
        assertFalse(runCatching {
            ScriptBackupCodec.encode(listOf(missingStart))
        }.isSuccess)
    }

    @Test
    fun rejectsSettingsOutsideEditorRanges() {
        val highJitter = Script(jitterDp = 21f)
        val invalidRepeatCount = Script(repeats = 4)
        val unsupportedLoopCount = Script(
            steps = listOf(Step.LoopStart(4), Step.LoopEnd)
        )
        val excessiveLongPress = Script(
            steps = listOf(Step.LongPress(0.5f, 0.5f, 10_001))
        )

        listOf(
            highJitter,
            invalidRepeatCount,
            unsupportedLoopCount,
            excessiveLongPress
        ).forEach { script ->
            assertFalse(runCatching {
                ScriptBackupCodec.encode(listOf(script))
            }.isSuccess)
        }
    }

    @Test
    fun rejectsUnsupportedBackupSchema() {
        val unsupported = """{"schemaVersion":99,"scripts":[]}"""

        assertFalse(runCatching {
            ScriptBackupCodec.decode(unsupported)
        }.isSuccess)
    }

    @Test
    fun rejectsDuplicateScriptIds() {
        val id = UUID.randomUUID().toString()
        val scripts = listOf(
            Script(id = id, name = "one"),
            Script(id = id, name = "two")
        )

        assertFalse(runCatching {
            ScriptBackupCodec.encode(scripts)
        }.isSuccess)
    }
}
