package com.example.juke.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.juke.R
import com.example.juke.ui.components.GlassAlertDialog
import com.example.juke.ui.components.GlassButton
import com.example.juke.ui.components.GlassPillButton
import com.example.juke.ui.theme.GlassBackdrop
import com.example.juke.ui.theme.GlassCard
import com.example.juke.ui.theme.GlassLevel
import com.example.juke.ui.theme.GlassShapes
import com.example.juke.ui.theme.glassPane
import com.example.juke.ui.theme.isGlassDark
import com.example.juke.utils.rememberJukeHaptics
import com.example.juke.viewmodels.AccountStatus
import com.example.juke.viewmodels.AccountUiState
import com.example.juke.viewmodels.AccountViewModel
import com.example.juke.viewmodels.AuthStage

/** Sign in with the web remote's owner account: username and password, then the authenticator code. */
@Composable
fun SignInScreen(state: AccountUiState, account: AccountViewModel) {
    val haptic = rememberJukeHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(GlassBackdrop.color(isGlassDark()))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .size(88.dp)
                    .glassPane(CircleShape, GlassLevel.Thick),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp).clip(CircleShape)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Music Box",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))

            AnimatedContent(
                targetState = state.stage,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "signInStage"
            ) { stage ->
                Column(
                    Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (stage) {
                        AuthStage.CHECKING, AuthStage.SIGNED_IN -> {
                            Spacer(Modifier.height(32.dp))
                            CircularProgressIndicator()
                        }
                        AuthStage.SIGNED_OUT -> {
                            Text(
                                "Sign in with your web remote account",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(28.dp))
                            GlassTextField(
                                value = username,
                                onValueChange = { username = it },
                                placeholder = "Username",
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) })
                            )
                            Spacer(Modifier.height(12.dp))
                            GlassTextField(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = "Password",
                                password = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    keyboard?.hide()
                                    account.login(username, password)
                                })
                            )
                            Spacer(Modifier.height(24.dp))
                            SubmitButton("Sign in", state.busy) {
                                haptic.click()
                                keyboard?.hide()
                                account.login(username, password)
                            }
                        }
                        AuthStage.NEEDS_CODE -> {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp).size(28.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Enter the 6-digit code from your authenticator app",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(28.dp))
                            GlassTextField(
                                value = code,
                                onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) },
                                placeholder = "000000",
                                centered = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    keyboard?.hide()
                                    account.verifyCode(code)
                                })
                            )
                            Spacer(Modifier.height(24.dp))
                            SubmitButton("Verify", state.busy) {
                                haptic.click()
                                keyboard?.hide()
                                account.verifyCode(code)
                            }
                            TextButton(onClick = {
                                code = ""
                                account.restartLogin()
                            }) { Text("Use a different account") }
                        }
                    }
                    state.error?.let { message ->
                        Spacer(Modifier.height(16.dp))
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmitButton(text: String, busy: Boolean, onClick: () -> Unit) {
    GlassPillButton(
        text = if (busy) "" else text,
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.testTag("account-submit").fillMaxWidth(),
        leading = if (busy) {
            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) }
        } else null
    )
}

/** A text field drawn as a glass pill, matching the search header. */
@Composable
private fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    centered: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var visible by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .glassPane(GlassShapes.Pill, GlassLevel.Regular)
            .padding(start = 20.dp, end = if (password) 4.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder }.padding(vertical = 14.dp),
            decorationBox = { inner ->
                Box(contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1
                        )
                    }
                    inner()
                }
            }
        )
        if (password) {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * After signing in, the server's linked accounts are checked like the web app does: Amazon for
 * the Echo and YouTube Music for personalized music and signed-in playback. When everything is
 * connected the app opens by itself.
 */
@Composable
fun AccountCheckScreen(state: AccountUiState, account: AccountViewModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(GlassBackdrop.color(isGlassDark()))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Your accounts",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.status == null) "Checking Amazon and YouTube Music…"
                else if (state.status.allConnected) "Everything is connected"
                else "Connect what's missing, or continue",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                AccountStatusCard(state, account)
            }
            Spacer(Modifier.height(28.dp))
            GlassPillButton(
                text = if (state.status?.allConnected == false) "Continue anyway" else "Continue",
                onClick = account::continueToApp,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
            )
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Amazon and YouTube Music connection rows with their connect actions (sign-in check and Settings). */
@Composable
fun AccountStatusCard(state: AccountUiState, account: AccountViewModel) {
    var showAmazonDialog by remember { mutableStateOf(false) }
    val status = state.status
    val checking = status == null || state.checkingStatus

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            AccountRow(
                icon = Icons.Outlined.Speaker,
                title = "Echo (Amazon)",
                detail = state.amazonMessage ?: if (status?.amazonConnected == true) "Controls your Echo devices" else "Needed to play on Echo",
                ok = status?.amazonConnected,
                checking = checking && status == null,
                action = if (status != null && !status.amazonConnected) "Connect" else null,
                onAction = { showAmazonDialog = true }
            )
            AccountRow(
                icon = Icons.Outlined.MusicNote,
                title = "YouTube Music",
                detail = state.youtubeMessage ?: if (status?.youtubeLibrary == true) "Personalized Home, library and likes"
                else "Home shows trending music until you connect",
                ok = status?.youtubeLibrary,
                checking = checking && status == null,
                action = if (status != null && !status.youtubeLibrary) "Connect" else null,
                onAction = { account.connectYouTube(retry = status?.youtubeReconnectRequired == true) }
            )
            AccountRow(
                icon = Icons.Outlined.Cookie,
                title = "YouTube cookies",
                detail = when {
                    status == null -> "Checking…"
                    status.youtubeCookies -> "Valid"
                    status.youtubeCookiesPresent -> "Expired. Reconnect YouTube Music to renew them"
                    else -> "Not found. Reconnect YouTube Music to add them"
                },
                ok = status?.youtubeCookies,
                checking = checking && status == null,
                action = if (status != null && !status.youtubeCookies && status.youtubeLibrary) "Renew" else null,
                onAction = { account.connectYouTube(retry = true) }
            )
            if (status != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = account::refreshStatus, enabled = !state.checkingStatus) {
                        Text(if (state.checkingStatus) "Checking…" else "Check again")
                    }
                }
            }
        }
    }

    if (showAmazonDialog) {
        AmazonSignInDialog(
            busy = state.busy,
            onDismiss = { showAmazonDialog = false },
            onConnect = { email, password ->
                account.connectAmazon(email, password)
                showAmazonDialog = false
            }
        )
    }
}

@Composable
private fun AccountRow(
    icon: ImageVector,
    title: String,
    detail: String,
    ok: Boolean?,
    checking: Boolean,
    action: String?,
    onAction: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).glassPane(CircleShape, GlassLevel.Thin),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            action != null -> GlassButton(onClick = onAction) { Text(action) }
            ok == true -> Icon(Icons.Filled.CheckCircle, "Connected", tint = MaterialTheme.colorScheme.primary)
            ok == false -> Icon(Icons.Filled.ErrorOutline, "Not connected", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AmazonSignInDialog(busy: Boolean, onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Amazon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Amazon finishes the sign-in on its own page, which opens next.",
                    style = MaterialTheme.typography.bodyMedium
                )
                GlassTextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "Amazon email",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
                )
                GlassTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Amazon password",
                    password = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConnect(email, password) })
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(email, password) }, enabled = !busy && email.isNotBlank() && password.isNotEmpty()) {
                Text("Continue")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Small status line for an account that still has work to do (used where space is short). */
fun AccountStatus.summary(): String = when {
    allConnected -> "Amazon and YouTube Music connected"
    !amazonConnected && !youtubeLibrary -> "Amazon and YouTube Music need connecting"
    !amazonConnected -> "Amazon needs connecting"
    !youtubeLibrary -> "YouTube Music needs connecting"
    else -> "YouTube cookies need renewing"
}
