package com.example.juke

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.juke.ui.screens.AccountStatusCard
import com.example.juke.ui.theme.JUKETheme
import com.example.juke.viewmodels.AccountStatus
import com.example.juke.viewmodels.AccountUiState
import com.example.juke.viewmodels.AccountViewModel
import org.junit.Rule
import org.junit.Test

class DownloadCookieUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replacementIsAvailableWithValidOrInvalidCookies() {
        val state = mutableStateOf(AccountUiState(status = AccountStatus(youtubeCookies = true)))
        val account = AccountViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        compose.setContent { JUKETheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountStatusCard(state.value, account) } } }
        compose.onNodeWithText("Replace cookies").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Replace download cookies").assertIsDisplayed()
        compose.onNodeWithText("Test and save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { state.value = state.value.copy(status = AccountStatus(youtubeCookies = false)) }
        compose.onNodeWithText("Replace cookies").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Choose cookies.txt").assertIsDisplayed()
    }

    @Test fun rejectionAllowsRetryAndSuccessfulSaveClearsExport() {
        val state = mutableStateOf(AccountUiState(status = AccountStatus()))
        val account = AccountViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        compose.setContent { JUKETheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountStatusCard(state.value, account) } } }
        compose.onNodeWithText("Replace cookies").performScrollTo().performClick()
        compose.onNodeWithTag("download-cookie-export").performTextInput("sample export")
        compose.runOnIdle { state.value = state.value.copy(cookieBusy = true) }
        compose.onNodeWithText("Test and save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(cookieBusy = false, cookieMessage = "Audio download failed") }
        compose.onNodeWithTag("download-cookie-export").assertTextContains("sample export")
        compose.onNodeWithText("Test and save").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(cookieSaved = 1) }
        compose.onNodeWithText("Replace download cookies").assertDoesNotExist()
        compose.onNodeWithText("Replace cookies").performScrollTo().performClick()
        compose.onNodeWithText("Test and save").assertIsNotEnabled()
    }
}
