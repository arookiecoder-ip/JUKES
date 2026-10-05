package com.example.juke.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Keep the app's layout stable underneath Android's share chooser. */
@Composable
fun rememberShareAction(): (String, String) -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val activity = context.activity()
    var previousInputMode by remember { mutableStateOf<Int?>(null) }
    fun restore() {
        previousInputMode?.let { activity?.window?.setSoftInputMode(it) }
        previousInputMode = null
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { restore() }
    DisposableEffect(activity) { onDispose { restore() } }
    return { title, content ->
        focus.clearFocus(force = true)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        if (previousInputMode == null) previousInputMode = activity?.window?.attributes?.softInputMode
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        try {
            launcher.launch(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_TITLE, title)
            }, title))
        } catch (_: android.content.ActivityNotFoundException) {
            restore()
            Toast.makeText(context, "No app is available to share this link", Toast.LENGTH_SHORT).show()
        }
    }
}
