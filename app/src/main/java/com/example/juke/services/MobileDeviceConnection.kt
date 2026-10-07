package com.example.juke.services

import android.os.Build
import com.example.juke.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID

data class MobileAudioDevice(val id: String, val name: String, val volume: Int? = null)

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
            while (isActive && activeGeneration == generation) {
                if (!NetworkFeedback.online.value) { delay(2_000); continue }
                try {
                    val reply = request("online", buildJsonObject {
                        put("wait", true); put("revision", revision); put("output_token", outputToken)
                        put("volume", (audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)))
                        put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("ack", JsonArray(acknowledge.map(::JsonPrimitive)))
                    }, activeSession)
                    if (activeGeneration != generation) return@launch
                    acknowledge = emptyList()
                    _devices.value = reply.array("devices").map { it.objectOrEmpty() }
                        .map { MobileAudioDevice(it.text("id"), it.text("name"), (it["volume"] as? JsonPrimitive)?.intOrNull) }
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
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { delay(2_000) /* Expiring leases bound network failures. */ }
                delay(if (supportsWait) 100 else 2_000)
            }
        }
    }

    fun stop() {
        val closingSession = session
        val wasOwner = PhonePlaybackOwnership.token.isNotBlank()
        ++generation
        job?.cancel(); job = null
        _devices.value = emptyList()
        scope.launch { runCatching { withTimeout(2_000) {
            if (wasOwner) PlaybackService.pausePhoneForHandoff()
            request("offline", presenceSession = closingSession)
        } } }
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
