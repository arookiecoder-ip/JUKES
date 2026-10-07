package com.example.juke.services

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One optimistic/debounced route shared by Activity keys, sliders and Android media sessions. */
object RemoteVolumeControl {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    data class Feedback(val value: Int, val target: String, val sequence: Long)
    private val _feedback = kotlinx.coroutines.flow.MutableStateFlow<Feedback?>(null)
    val feedback = _feedback.asStateFlow()
    private var feedbackSequence = 0L
    private var feedbackHide: Job? = null
    private fun preview(value: Int, target: String) {
        val sequence = ++feedbackSequence
        _feedback.value = Feedback(value.coerceIn(0, 100), target, sequence)
        feedbackHide?.cancel()
        feedbackHide = scope.launch {
            delay(1_200)
            if (_feedback.value?.sequence == sequence) _feedback.value = null
        }
    }
    fun current(context: Context): Int? = when (mode(context)) {
        "ALEXA" -> PlaybackCoordinator.echo(context).state.value.volume
        "REMOTE_PHONE" -> MobileDeviceConnection.devices.value.firstOrNull { it.id == MobileDeviceConnection.output.value.owner }?.volume
        else -> null
    }
    private fun mode(context: Context) = context.getSharedPreferences("music_settings_prefs", Context.MODE_PRIVATE)
        .getString("playback_output", "PHONE")
    fun adjust(context: Context, direction: Int): Boolean {
        if (mode(context) !in setOf("ALEXA", "REMOTE_PHONE")) return false
        val current = current(context) ?: return true
        val output = MobileDeviceConnection.output.value
        val device = MobileDeviceConnection.devices.value.firstOrNull { it.id == output.owner }
        val value = if (mode(context) == "REMOTE_PHONE") remotePhoneVolumeStep(current, direction, device?.volumeSteps)
            else remoteVolumeStep(current, direction)
        set(context, value)
        return true
    }
    fun set(context: Context, value: Int) {
        val app = context.applicationContext
        val selected = mode(app)
        job?.cancel()
        if (selected == "ALEXA") { preview(value, "Alexa"); PlaybackCoordinator.echo(app).setVolume(value); return }
        if (selected != "REMOTE_PHONE") return
        val target = MobileDeviceConnection.output.value
        if (target.mode != "phone" || target.owner.isBlank() || target.token.isBlank() || target.handoffPending) return
        preview(value, MobileDeviceConnection.devices.value.firstOrNull { it.id == target.owner }?.name ?: "Device")
        MobileDeviceConnection.previewVolume(target.owner, value)
        job = scope.launch {
            delay(150)
            val latest = MobileDeviceConnection.output.value
            if (!canSendRemotePhoneVolume(target, latest, mode(app) == selected)) return@launch
            try { MobileDeviceConnection.control(target, "volume", buildJsonObject { put("value", value.coerceIn(0, 100)) }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { com.example.juke.network.NetworkFeedback.notify("Couldn't change device volume. Check its connection.") }
        }
    }
}
