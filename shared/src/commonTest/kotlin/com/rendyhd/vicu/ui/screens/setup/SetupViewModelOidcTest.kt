package com.rendyhd.vicu.ui.screens.setup

import com.rendyhd.vicu.auth.LoginHarness
import com.rendyhd.vicu.auth.OidcBrowserSignIn
import com.rendyhd.vicu.auth.OidcHandler
import com.rendyhd.vicu.auth.OidcSignInMode
import com.rendyhd.vicu.auth.PasswordLoginHandler
import com.rendyhd.vicu.data.remote.api.OidcProviderDto
import com.rendyhd.vicu.worker.awaitUntil
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The OIDC sign-in in Setup: the in-app web view, and the browser for a passkey. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelOidcTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val provider = OidcProviderDto(
        key = "pocketid",
        name = "Pocket ID",
        authUrl = "https://id.example.com/authorize",
        clientId = "client-1",
    )

    private fun harness() = LoginHarness { respond("", HttpStatusCode.NotFound) }

    private fun LoginHarness.viewModel() = SetupViewModel(
        apiService = api,
        authManager = authManager,
        baseUrlHolder = baseUrlHolder,
        passwordLoginHandler = PasswordLoginHandler { api },
        oidcHandler = OidcHandler { api },
        accountSession = accountSession,
        platformAuthHooks = hooks,
        syncStaleness = fixture.staleness,
        repositoryHooks = fixture.hooks,
    )

    @Test
    fun `a passkey prompt in the web view moves setup to the browser sign-in`() = runTest {
        val h = harness()
        val vm = h.viewModel()
        vm.updateServerUrl("https://tasks.example.com")
        vm.selectOidcProvider(provider)
        val webView = assertNotNull(vm.getOidcAuthParams())
        assertEquals(OidcSignInMode.WebView, webView.mode)

        vm.handleOidcCallback(null, null, OidcBrowserSignIn.PASSKEY_UNAVAILABLE)
        awaitUntil { vm.uiState.value.step == SetupStep.OidcBrowser }

        val state = vm.uiState.value
        assertTrue(state.passkeyPrompted)
        assertEquals(OidcSignInMode.Browser, state.oidcMode)
        assertEquals(null, state.error)
        assertFalse(state.isLoading)

        val browser = assertNotNull(vm.getOidcAuthParams())
        assertEquals(OidcSignInMode.Browser, browser.mode)
        assertEquals(OidcBrowserSignIn.REDIRECT_URI, browser.redirectUri)
        h.close()
    }

    @Test
    fun `a failed browser sign-in returns to the browser step with the error`() = runTest {
        val h = harness()
        val vm = h.viewModel()
        vm.updateServerUrl("https://tasks.example.com")
        vm.showOidcBrowser(provider)
        assertNotNull(vm.getOidcAuthParams())
        assertEquals(SetupStep.OidcInProgress, vm.uiState.value.step)

        vm.handleOidcCallback(null, null, "access_denied")
        awaitUntil { vm.uiState.value.step == SetupStep.OidcBrowser }

        assertEquals("OIDC error: access_denied", vm.uiState.value.error)
        assertFalse(vm.uiState.value.passkeyPrompted, "chosen by the user, not after a passkey prompt")
        h.close()
    }

    @Test
    fun `back from the browser sign-in returns to the method picker and the web view`() = runTest {
        val h = harness()
        val vm = h.viewModel()
        vm.showOidcBrowser(provider, passkeyPrompted = true)
        assertTrue(vm.uiState.value.canGoBack)

        vm.goBack()

        val state = vm.uiState.value
        assertEquals(SetupStep.AuthMethodPicker, state.step)
        assertEquals(OidcSignInMode.WebView, state.oidcMode)
        assertFalse(state.passkeyPrompted)

        // Leaving a browser sign-in in progress goes back to the browser step, not the picker.
        vm.showOidcBrowser(provider)
        vm.getOidcAuthParams()
        vm.goBack()
        assertEquals(SetupStep.OidcBrowser, vm.uiState.value.step)
        h.close()
    }

    @Test
    fun `choosing a provider card signs in in the web view again`() = runTest {
        val h = harness()
        val vm = h.viewModel()
        vm.showOidcBrowser(provider, passkeyPrompted = true)

        vm.selectOidcProvider(provider)

        assertEquals(OidcSignInMode.WebView, vm.uiState.value.oidcMode)
        assertFalse(vm.uiState.value.passkeyPrompted)
        h.close()
    }
}
