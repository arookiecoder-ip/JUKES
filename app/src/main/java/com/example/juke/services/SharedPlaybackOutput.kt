package com.example.juke.services

import com.example.juke.network.number
import com.example.juke.network.text
import kotlinx.serialization.json.JsonObject

data class SharedPlaybackOutput(val mode: String = "", val owner: String = "", val token: String = "",
    val leaseMs: Long = 0, val serial: String = "", val handoffPending: Boolean = false, val controller: String = "", val revision: Long = 0, val epoch: String = "") {
    fun olderThan(other: SharedPlaybackOutput): Boolean = epoch.isNotBlank() && epoch == other.epoch && revision < other.revision

    fun belongsToPhone(ownerId: String, claimedToken: String): Boolean =
        mode == "phone" && owner == ownerId && token.isNotBlank() && token == claimedToken && !handoffPending
}

fun sharedPlaybackOutput(json: JsonObject) = SharedPlaybackOutput(json.text("playback_output"),
    json.text("output_owner"), json.text("output_token"), json.number("phone_lease_ms"), json.text("output_serial"), json["handoff_pending"]?.toString() == "true", json.text("output_controller"), json.number("output_revision"), json.text("output_epoch"))

/** Reject prepared audio from an older play intent, including a relinquished online lease. */
internal fun canStartPhonePlayback(expectedToken: String, currentToken: String, relinquishing: Boolean,
    leaseUntil: Long, now: Long): Boolean = !relinquishing && expectedToken == currentToken &&
    (currentToken.isBlank() || now < leaseUntil)

internal fun canContinueDownloadedOffline(online: Boolean, scheme: String?): Boolean =
    !online && scheme in setOf("file", "content")
