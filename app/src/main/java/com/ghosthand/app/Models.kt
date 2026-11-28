package com.ghosthand.app

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

@Serializable
sealed interface Step {
    @Serializable @SerialName("tap")
    data class Tap(val x: Float, val y: Float) : Step

    @Serializable @SerialName("long")
    data class LongPress(val x: Float, val y: Float, val ms: Long = 700) : Step

    @Serializable @SerialName("swipe")
    data class Swipe(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val ms: Long = 280
    ) : Step

    @Serializable @SerialName("wait")
    data class Wait(val ms: Long = 450) : Step

    @Serializable @SerialName("loop")
    data class LoopStart(val times: Int = 5) : Step

    @Serializable @SerialName("end")
    object LoopEnd : Step
}

@Serializable
data class Script(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New run",
    val steps: List<Step> = emptyList(),
    val repeats: Int = 1,
    val jitterMs: Long = 140,
    val jitterDp: Float = 5f,
    val keepAwake: Boolean = true
)

@Serializable
data class ScriptBackup(
    val schemaVersion: Int = 1,
    val scripts: List<Script>
)

/**
 * Portable, validated backup format for scripts. Import merges by stable
 * script ID, allowing a user to restore runs after reinstalling GhostHand.
 */
object ScriptBackupCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_BACKUP_CHARS = 8 * 1024 * 1024
    private const val MAX_SCRIPTS = 500
    private const val MAX_STEPS_PER_SCRIPT = 2_000
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val validId = Regex(
        """[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"""
    )

    fun encode(scripts: List<Script>): String {
        require(scripts.size <= MAX_SCRIPTS) { "Too many scripts to back up" }
        require(scripts.map { it.id }.distinct().size == scripts.size) {
            "Script backup contains duplicate IDs"
        }
        require(scripts.all(::isValidScript)) { "A script contains invalid data" }
        val content = json.encodeToString(ScriptBackup(SCHEMA_VERSION, scripts))
        require(content.length <= MAX_BACKUP_CHARS) { "Backup would be too large" }
        return content
    }

    fun decode(content: String): List<Script> {
        require(content.isNotBlank() && content.length <= MAX_BACKUP_CHARS) {
            "Backup file is empty or too large"
        }
        val backup = json.decodeFromString<ScriptBackup>(content)
        require(backup.schemaVersion == SCHEMA_VERSION) {
            "Unsupported GhostHand backup version: ${backup.schemaVersion}"
        }
        require(backup.scripts.size <= MAX_SCRIPTS) { "Backup contains too many scripts" }
        require(backup.scripts.map { it.id }.distinct().size == backup.scripts.size) {
            "Backup contains duplicate script IDs"
        }
        require(backup.scripts.all(::isValidScript)) {
            "Backup contains invalid script data"
        }
        return backup.scripts
    }

    private fun isValidScript(script: Script): Boolean {
        if (!validId.matches(script.id)) return false
        if (script.name.length > 500) return false
        if (script.steps.size > MAX_STEPS_PER_SCRIPT) return false
        if (script.repeats !in setOf(0, 1, 2, 3, 5, 10)) return false
        if (script.jitterMs !in 0L..600L) return false
        if (!script.jitterDp.isFinite() || script.jitterDp !in 0f..20f) return false

        var loopDepth = 0
        for (step in script.steps) {
            when (step) {
                is Step.Tap ->
                    if (!validCoord(step.x) || !validCoord(step.y)) return false
                is Step.LongPress ->
                    if (!validCoord(step.x) || !validCoord(step.y) ||
                        step.ms !in 100L..10_000L
                    ) return false
                is Step.Swipe ->
                    if (!validCoord(step.x1) || !validCoord(step.y1) ||
                        !validCoord(step.x2) || !validCoord(step.y2) ||
                        step.ms !in 40L..3_000L
                    ) return false
                is Step.Wait ->
                    if (step.ms !in 50L..120_000L) return false
                is Step.LoopStart -> {
                    if (step.times !in setOf(0, 2, 3, 5, 10, 20)) return false
                    loopDepth++
                }
                is Step.LoopEnd -> {
                    if (loopDepth == 0) return false
                    loopDepth--
                }
            }
        }
        return loopDepth == 0
    }

    private fun validCoord(value: Float): Boolean =
        value.isFinite() && value in 0f..1f
}

fun describe(st: Step): String = when (st) {
    is Step.Tap       -> "tap ${p(st.x)} · ${p(st.y)}"
    is Step.LongPress -> "hold ${p(st.x)} · ${p(st.y)} ${st.ms}ms"
    is Step.Swipe     -> "swipe ${p(st.x1)}·${p(st.y1)} → ${p(st.x2)}·${p(st.y2)}"
    is Step.Wait      -> "wait ${st.ms}ms"
    is Step.LoopStart -> if (st.times <= 0) "loop forever" else "repeat ×${st.times}"
    is Step.LoopEnd   -> "end of loop"
}

private fun p(v: Float) = "${(v * 100).roundToInt()}%"

fun depths(steps: List<Step>): List<Int> {
    var d = 0
    return steps.map { st ->
        when (st) {
            is Step.LoopStart -> d.also { d++ }
            is Step.LoopEnd   -> { d = (d - 1).coerceAtLeast(0); d }
            else -> d
        }
    }
}

fun estimateMs(s: Script): Long {
    var time = 0L
    val stack = ArrayDeque<LongArray>()
    for (st in s.steps) {
        when (st) {
            is Step.LoopStart -> { stack.addLast(longArrayOf(time, if (st.times <= 0) 200 else st.times.toLong().coerceAtMost(500L))); time = 0 }
            is Step.LoopEnd   -> if (stack.isNotEmpty()) { val f = stack.removeLast(); time = f[0] + time * f[1] }
            is Step.Tap       -> time += 160
            is Step.LongPress -> time += st.ms.coerceAtLeast(280) + 130
            is Step.Swipe     -> time += st.ms + 130
            is Step.Wait      -> time += st.ms
        }
        time = time.coerceAtMost(86_400_000L)
    }
    return time * (if (s.repeats <= 0) 1 else s.repeats)
}

fun fmtDur(ms: Long): String {
    val t = ms / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

object ScriptStore {
    private lateinit var dir: File
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun init(c: Context) { dir = File(c.filesDir, "scripts").apply { mkdirs() } }

    fun list(): List<Script> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<Script>(it.readText()) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun load(id: String): Script? = runCatching {
        json.decodeFromString<Script>(File(dir, "$id.json").readText())
    }.getOrNull()

    fun save(s: Script) { File(dir, "${s.id}.json").writeText(json.encodeToString(s)) }
    fun delete(id: String) { File(dir, "$id.json").delete() }

    fun exportBackup(): String = ScriptBackupCodec.encode(list())

    /** Merge a validated backup into the current script store by script ID. */
    fun importBackup(content: String): Int {
        val restored = ScriptBackupCodec.decode(content)
        restored.forEach(::save)
        return restored.size
    }

    fun create(name: String): Script = Script(id = UUID.randomUUID().toString(), name = name).also { save(it) }
}
