package `in`.synthora.musicbox.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.synthora.musicbox.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class JamUiState(val active: Boolean = false, val url: String = "", val busy: Boolean = false, val error: String? = null)
class JamViewModel : ViewModel() {
    private val _state = MutableStateFlow(JamUiState())
    val state = _state.asStateFlow()
    fun refresh() = request(null)
    fun start() = request("start")
    fun stop() = request("stop")
    private fun request(action: String?) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = (if (action == null) Backend.get("/alexa/jam/status/") else
                    Backend.post("/alexa/jam/$action/", JsonObject(emptyMap()))).objectOrEmpty()
                _state.value = JamUiState(result.flag("active"), result.text("url"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(busy = false, error = networkErrorMessage(e) ?: e.message ?: "Couldn't update Jam") }
        }
    }
}
