package `in`.synthora.musicbox.ui.components.player

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import `in`.synthora.musicbox.utils.rememberJukeHaptics
import `in`.synthora.musicbox.viewmodels.MusicUiState
import `in`.synthora.musicbox.viewmodels.MusicViewModel

@Composable
fun PlayerProgress(
    currentPosition: Long,
    uiState: MusicUiState,
    musicViewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val duration = if (uiState.duration > 0) uiState.duration else musicViewModel.playbackManager.getDuration().coerceAtLeast(0)

    val output by musicViewModel.output.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val trackId = uiState.currentTrack?.uuid
    val savedCache by androidx.compose.runtime.produceState(0f, trackId, output, lifecycle, duration) {
        value = 0f
        if (output == `in`.synthora.musicbox.viewmodels.PlaybackOutput.PHONE && trackId != null) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    value = maxOf(musicViewModel.playbackManager.getSavedCacheFraction(trackId),
                        `in`.synthora.musicbox.services.bufferedPlaybackFraction(
                            musicViewModel.playbackManager.getBufferedPosition(trackId), duration))
                    kotlinx.coroutines.delay(1_000)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PlaybackSeekSlider(
            positionMs = currentPosition,
            durationMs = duration,
            cachedFraction = savedCache,
            onSeek = musicViewModel::seekTo
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-6).dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                `in`.synthora.musicbox.utils.playbackTime(currentPosition),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                `in`.synthora.musicbox.utils.playbackTime(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSeekSlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    cachedFraction: Float = 0f
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val haptic = rememberJukeHaptics()
    ExpandableTrackSlider(
        value = progress,
        bufferedFraction = cachedFraction,
        label = "Playback position",
        enabled = durationMs > 0,
        restingHeight = 4.dp,
        draggingHeight = 8.dp,
        onValueChangeFinished = { value ->
            haptic.gestureEnd()
            onSeek((value * durationMs).toLong())
        },
        modifier = modifier
    )
}
