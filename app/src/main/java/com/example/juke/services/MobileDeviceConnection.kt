package com.example.juke.services

import android.os.Build
import com.example.juke.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID

data class MobileAudioDevice(val id: String, val name: String, val volume: Int? = null, val volumeSteps: Int? = null)

/** One presence loop per app process, including while an active player is minimized. */
object MobileDeviceConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session = ""
    private val _devices = MutableStateFlow<List<MobileAudioDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private val handled = linkedSetOf<String>()
    private var acknowledge = emptyList<String>()
    private var latestOutput = SharedPlaybackOutput()
    private data class PendingVolume(val value: Int, val until: Long)
    private val pendingVolumes = mutableMapOf<String, PendingVolume>()
    fun previewVolume(id: String, value: Int) {
        pendingVolumes[id] = PendingVolume(value.coerceIn(0, 100), android.os.SystemClock.elapsedRealtime() + 5_000)
        _devices.value = _devices.value.map { if (it.id == id) it.copy(volume = value.coerceIn(0, 100)) else it }
    }
    fun rememberOutput(output: SharedPlaybackOutput): SharedPlaybackOutput {
        if (!output.olderThan(latestOutput)) latestOutput = output
        return latestOutput
    }

    fun start(context: android.content.Context, onState: suspend (SharedPlaybackOutput) -> Unit,
              onCommand: suspend (String, JsonObject) -> Unit) {
        if (job?.isActive == true) return
        val activeSession = UUID.randomUUID().toString()
        session = activeSession
        val activeGeneration = ++generation
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        job = scope.launch {
            var revision = -1L
            var outputToken = ""
            var supportsWait = false
            var failures = 0
            while (isActive && activeGeneration == generation) {
                if (!NetworkFeedback.online.value) { delay(2_000); continue }
                try {
                    val reply = withTimeout(6_000) { request("online", buildJsonObject {
                        put("wait", true); put("revision", revision); put("output_token", outputToken)
                        put("volume", (audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)))
                        put("volume_steps", audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1))
                        put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("ack", JsonArray(acknowledge.map(::JsonPrimitive)))
                    }, activeSession) }
                    if (activeGeneration != generation) return@launch
                    failures = 0
                    acknowledge = emptyList()
                    _devices.value = reply.array("devices").map { it.objectOrEmpty() }
                        .map {
                            val id = it.text("id")
                            val reported = (it["volume"] as? JsonPrimitive)?.intOrNull
                            val pending = pendingVolumes[id]
                            if (pending != null && (reported == pending.value || android.os.SystemClock.elapsedRealtime() >= pending.until)) pendingVolumes.remove(id)
                            MobileAudioDevice(id, it.text("name"), pendingVolumes[id]?.value ?: reported, (it["volume_steps"] as? JsonPrimitive)?.intOrNull)
                        }
                    supportsWait = reply.containsKey("revision")
                    revision = reply.number("revision")
                    val output = rememberOutput(sharedPlaybackOutput(reply))
                    outputToken = output.token
                    onState(output)
                    val completed = mutableListOf<String>()
                    reply.array("commands").forEach { raw ->
                        val command = raw.objectOrEmpty()
                        val id = command.text("id")
                        if (id in handled) { completed += id; return@forEach }
                        if (output.belongsToPhone(PhonePlaybackOwnership.ownerId, command.text("token")) &&
                            PhonePlaybackOwnership.permitsPlayback(command.text("token"))) {
                            onCommand(command.text("action"), command["payload"].objectOrEmpty())
                        }
                        // Stale commands are acknowledged too; they must never act on a new lease.
                        handled += id
                        completed += id
                        while (handled.size > 100) handled.remove(handled.first())
                    }
                    acknowledge = completed
                } catch (_: TimeoutCancellationException) { failures++ }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures++ }
                if (failures > 0) { delay((2_000L shl failures.coerceAtMost(3)).coerceAtMost(15_000)); continue }
                val visible = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                val activeOutput = latestOutput.mode == "phone" && latestOutput.owner == PhonePlaybackOwnership.ownerId
                delay(if (supportsWait && (visible || activeOutput)) 100 else 3_000)
            }
        }
    }

    fun stop() {
        val closingSession = session
        val wasOwner = PhonePlaybackOwnership.token.isNotBlank()
        ++generation
        job?.cancel(); job = null
        _devices.value = emptyList()
        pendingVolumes.clear()
        scope.launch { runCatching { withTimeout(2_000) {
            if (wasOwner) PlaybackService.pausePhoneForHandoff()
            request("offline", presenceSession = closingSession)
        } } }
    }

    suspend fun selectForegroundDefault(): SharedPlaybackOutput? {
        val reply = request("foreground", buildJsonObject { put("name", "${Build.MANUFACTURER} ${Build.MODEL}") })
        return if (reply.flag("default_selected")) rememberOutput(sharedPlaybackOutput(reply)) else null
    }

    suspend fun transfer(target: String, output: SharedPlaybackOutput, serial: String): SharedPlaybackOutput =
        rememberOutput(sharedPlaybackOutput(request("transfer", buildJsonObject {
            put("target_id", target); put("output_token", output.token); put("serial", serial)
        })))

    suspend fun control(output: SharedPlaybackOutput, action: String, payload: JsonObject = buildJsonObject {}) {
        request("command", buildJsonObject {
            put("target_id", output.owner); put("output_token", output.token)
            put("command", action); put("payload", payload); put("command_id", UUID.randomUUID().toString())
        })
    }

    private suspend fun request(action: String, fields: JsonObject = buildJsonObject {}, presenceSession: String = session): JsonObject =
        Backend.post("/api/app/devices/", JsonObject(fields + mapOf(
            "action" to JsonPrimitive(action), "device_id" to JsonPrimitive(PhonePlaybackOwnership.ownerId),
            "session_id" to JsonPrimitive(presenceSession)))).objectOrEmpty()
}
