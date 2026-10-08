package `in`.synthora.musicbox

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.test.platform.app.InstrumentationRegistry
import `in`.synthora.musicbox.ui.screens.AccountCheckScreen
import `in`.synthora.musicbox.ui.screens.SignInScreen
import `in`.synthora.musicbox.ui.theme.JUKETheme
import `in`.synthora.musicbox.viewmodels.AccountStatus
import `in`.synthora.musicbox.viewmodels.AccountUiState
import `in`.synthora.musicbox.viewmodels.AccountViewModel
import `in`.synthora.musicbox.viewmodels.AuthStage
import org.junit.Rule
import org.junit.Test

/** Exercise native account screens without sending credentials or changing remote accounts. */
class AccountUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private fun account() = AccountViewModel(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    )

    @Test fun emptySignInShowsValidationWithoutLeavingNativeLogin() {
        val account = account()
        compose.setContent {
            val state by account.state.collectAsState()
            JUKETheme { SignInScreen(state.copy(stage = AuthStage.SIGNED_OUT), account) }
        }
        compose.onNodeWithText("Sign in").performClick()
        compose.onNodeWithText("Enter your username and password").assertIsDisplayed()
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
    }

    @Test fun authenticatorStepRejectsAnEmptyCodeLocally() {
        val account = account()
        compose.setContent {
            val state by account.state.collectAsState()
            JUKETheme { SignInScreen(state.copy(stage = AuthStage.NEEDS_CODE), account) }
        }
        compose.onNodeWithText("Verify").performClick()
        compose.onAllNodesWithText("Enter the 6-digit code from your authenticator app").assertCountEquals(2)
        compose.onNodeWithText("Use a different account").assertIsDisplayed()
    }

    @Test fun loginCannotBeSubmittedAgainWhileRequestIsRunning() {
        val account = account()
        compose.setContent {
            JUKETheme { SignInScreen(AccountUiState(stage = AuthStage.SIGNED_OUT, busy = true), account) }
        }
        compose.onNodeWithTag("account-submit").assertIsNotEnabled()
    }

    @Test fun unavailableLinkedAccountsStillAllowAnonymousRecommendations() {
        val account = account()
        compose.setContent {
            JUKETheme {
                AccountCheckScreen(AccountUiState(stage = AuthStage.SIGNED_IN, status = AccountStatus()), account)
            }
        }
        compose.onNodeWithText("Your accounts").assertIsDisplayed()
        compose.onNodeWithText("Connect what's missing, or continue").assertIsDisplayed()
        compose.onNodeWithText("Continue anyway").performScrollTo().assertIsDisplayed().performClick()
    }
}
