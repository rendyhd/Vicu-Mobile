package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.rendyhd.vicu.auth.OidcBrowserSignIn
import com.rendyhd.vicu.auth.OidcHandler
import com.rendyhd.vicu.auth.OidcSignInMode
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.readValue
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorDomain
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.Foundation.NSURLRequest
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonSystemItem
import platform.UIKit.UINavigationController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.modalInPresentation
import platform.UIKit.navigationItem
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Signs in with the identity provider in a web view, like the Android `OidcLoginActivity`. The
 * redirect URI is Vikunja's own frontend callback (already registered at the provider), so the
 * redirect is caught here before it loads; ASWebAuthenticationSession cannot catch an https
 * redirect to a server the app does not own.
 *
 * A provider that signs in with a passkey cannot be completed in the web view (WKWebView serves
 * WebAuthn only for the app's associated domains); that sign-in runs in ASWebAuthenticationSession
 * instead ([OidcSignInMode.Browser]), which has Safari's passkeys and catches the redirect to the
 * app's own scheme ([OidcBrowserSignIn.REDIRECT_URI], registered once at the provider).
 */
@Composable
actual fun rememberOidcLauncher(
    onResult: (code: String?, state: String?, error: String?) -> Unit
): (OidcHandler.AuthParams) -> Unit {
    val onResultState = rememberUpdatedState(onResult)
    return remember {
        { params ->
            val presenter = topViewController()
            when {
                presenter == null -> onResultState.value(null, null, "Could not show the sign-in page")
                params.mode == OidcSignInMode.Browser -> BrowserSignIn.start(params, presenter.view.window) { code, state, error ->
                    onResultState.value(code, state, error)
                }
                else -> {
                    val signIn = OidcWebViewController(params) { code, state, error ->
                        onResultState.value(code, state, error)
                    }
                    val navigation = UINavigationController(rootViewController = signIn)
                    // Only Cancel closes the sheet, so a swipe cannot leave the result unanswered.
                    navigation.modalInPresentation = true
                    presenter.presentViewController(navigation, animated = true, completion = null)
                }
            }
        }
    }
}

/** The script message handler the passkey guard posts to (an ObjC subclass cannot hold it). */
private const val PASSKEY_HANDLER = "vicuPasskey"

/** The OIDC answer in a redirect URL, as (code, state, error). */
private fun redirectResult(url: String): Triple<String?, String?, String?> {
    val query = NSURLComponents.componentsWithString(url)?.queryItems
        ?.filterIsInstance<NSURLQueryItem>()
        ?.associate { it.name to it.value }
        .orEmpty()
    val error = query["error"]
    return when {
        error != null -> Triple(null, null, query["error_description"] ?: error)
        query["code"] != null -> Triple(query["code"], query["state"], null)
        else -> Triple(null, null, null)
    }
}

/**
 * The browser sign-in. One at a time: the session and its anchor are held here until it ends, since
 * nothing else keeps them alive while Safari's sheet is up.
 */
private object BrowserSignIn {
    private var session: ASWebAuthenticationSession? = null
    private var anchor: Anchor? = null

    fun start(params: OidcHandler.AuthParams, window: UIWindow?, onFinish: (String?, String?, String?) -> Unit) {
        val url = NSURL.URLWithString(params.authUrl)
        if (url == null) {
            onFinish(null, null, "Invalid sign-in address")
            return
        }
        session?.cancel()
        val provider = Anchor(window)
        val started = ASWebAuthenticationSession(
            uRL = url,
            callbackURLScheme = OidcBrowserSignIn.SCHEME,
            completionHandler = { callback: NSURL?, error: NSError? ->
                dispatch_async(dispatch_get_main_queue()) {
                    session = null
                    anchor = null
                    when {
                        callback != null -> {
                            val (code, state, failure) = redirectResult(callback.absoluteString.orEmpty())
                            onFinish(code, state, failure)
                        }
                        error != null && error.domain == ASWebAuthenticationSessionErrorDomain &&
                            error.code == ASWebAuthenticationSessionErrorCodeCanceledLogin -> onFinish(null, null, "Cancelled")
                        else -> onFinish(null, null, error?.localizedDescription ?: "Sign-in in the browser failed")
                    }
                }
            },
        )
        started.presentationContextProvider = provider
        // Safari's session and passkeys, not a private one.
        started.prefersEphemeralWebBrowserSession = false
        session = started
        anchor = provider
        if (!started.start()) {
            session = null
            anchor = null
            onFinish(null, null, "Could not open the browser sign-in")
        }
    }

    private class Anchor(private val window: UIWindow?) :
        NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            window ?: UIWindow()
    }
}

/**
 * The sign-in page. It is its own navigation delegate: WKWebView holds the delegate weakly, and
 * the navigation controller holds this controller for as long as the sheet is up.
 */
private class OidcWebViewController(
    private val params: OidcHandler.AuthParams,
    private val onFinish: (code: String?, state: String?, error: String?) -> Unit,
) : UIViewController(nibName = null, bundle = null), WKNavigationDelegateProtocol {

    private var finished = false
    private var webView: WKWebView? = null

    override fun loadView() {
        // Every page gets the passkey guard; a passkey request ends the sign-in so setup can offer
        // the browser. The content controller holds its handler strongly: removed in finish().
        val configuration = WKWebViewConfiguration()
        val content = configuration.userContentController
        content.addUserScript(
            WKUserScript(
                source = OidcBrowserSignIn.passkeyGuardScript(
                    "window.webkit.messageHandlers.$PASSKEY_HANDLER.postMessage('passkey')",
                ),
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
            ),
        )
        content.addScriptMessageHandler(
            PasskeyMessageHandler { finish(null, null, OidcBrowserSignIn.PASSKEY_UNAVAILABLE) },
            PASSKEY_HANDLER,
        )
        val view = WKWebView(frame = CGRectZero.readValue(), configuration = configuration)
        view.navigationDelegate = this
        webView = view
        this.view = view
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        title = "Sign in"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem = UIBarButtonSystemItem.UIBarButtonSystemItemCancel,
            target = this,
            action = NSSelectorFromString("cancel"),
        )
        val url = NSURL.URLWithString(params.authUrl)
        if (url == null) {
            finish(null, null, "Invalid sign-in address")
        } else {
            webView?.loadRequest(NSURLRequest.requestWithURL(url))
        }
    }

    @ObjCAction
    fun cancel() {
        finish(null, null, "Cancelled")
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
        if (!url.startsWith(params.redirectUri)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        val (code, state, error) = redirectResult(url)
        finish(code, state, error)
    }

    private fun finish(code: String?, state: String?, error: String?) {
        if (finished) return
        finished = true
        webView?.stopLoading()
        webView?.configuration?.userContentController?.removeScriptMessageHandlerForName(PASSKEY_HANDLER)
        dismissViewControllerAnimated(true) { onFinish(code, state, error) }
    }

    private class PasskeyMessageHandler(private val onPasskey: () -> Unit) : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            onPasskey()
        }
    }
}
