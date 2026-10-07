package com.example.juke.services

import android.os.Build
import com.example.juke.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.UUID

data class MobileAudioDevice(val id: String, val name: String)

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

    fun start(onState: suspend (SharedPlaybackOutput) -> Unit,
              onCommand: suspend (String, JsonObject) -> Unit) {
        if (job?.isActive == true) return
        val activeSession = UUID.randomUUID().toString()
        session = activeSession
        val activeGeneration = ++generation
        job = scope.launch {
            while (isActive && activeGeneration == generation) {
                if (!NetworkFeedback.online.value) { delay(2_000); continue }
                try {
                    val reply = request("online", buildJsonObject {
                        put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("ack", JsonArray(acknowledge.map(::JsonPrimitive)))
                    }, activeSession)
                    if (activeGeneration != generation) return@launch
                    acknowledge = emptyList()
                    _devices.value = reply.array("devices").map { it.objectOrEmpty() }
                        .map { MobileAudioDevice(it.text("id"), it.text("name")) }
                    val output = sharedPlaybackOutput(reply)
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
                catch (_: Exception) { /* Expiring presence/ownership leases bound network failures. */ }
                delay(2_000)
            }
        }
    }

    fun stop() {
        val closingSession = session
        ++generation
        job?.cancel(); job = null
        _devices.value = emptyList()
        scope.launch { runCatching { withTimeout(2_000) { request("offline", presenceSession = closingSession) } } }
    }

    suspend fun transfer(target: String, output: SharedPlaybackOutput, serial: String): SharedPlaybackOutput =
        sharedPlaybackOutput(request("transfer", buildJsonObject {
            put("target_id", target); put("output_token", output.token); put("serial", serial)
        }))

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
