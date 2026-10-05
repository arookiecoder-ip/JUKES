package com.example.juke.services

/** Queue identity and actual playing item delivered together by Media3.onEvents. */
data class PhonePlaybackSnapshot(val queueIds: List<String> = emptyList(), val currentId: String? = null)
