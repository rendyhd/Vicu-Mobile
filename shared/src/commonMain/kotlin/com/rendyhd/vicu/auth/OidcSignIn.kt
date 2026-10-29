package com.rendyhd.vicu.auth

/**
 * Where the OIDC sign-in runs. [WebView] is the app's own web view with Vikunja's frontend callback
 * as redirect, caught before it loads: no setup at the provider. It cannot use passkeys (Android's
 * WebView has no WebAuthn unless the provider's domain is linked to the app; WKWebView only serves
 * domains in the app's associated domains), so a provider that signs in with a passkey, such as
 * Pocket ID, fails there ("An unknown error occurred"). [Browser] signs in in the system browser
 * (a Custom Tab, ASWebAuthenticationSession) where the user's passkeys work; it comes back on
 * [OidcBrowserSignIn.REDIRECT_URI], which has to be added once to the client at the provider.
 */
enum class OidcSignInMode { WebView, Browser }

object OidcBrowserSignIn {
    /** RFC 8252 private-use scheme redirect, the same on Android and iOS and in every build. */
    const val SCHEME = "com.rendyhd.vicu"
    const val REDIRECT_URI = "$SCHEME:/oidc-callback"

    /** The sign-in web view's result in place of a provider error when the page asked for a passkey. */
    const val PASSKEY_UNAVAILABLE = "vicu:passkey-unavailable"

    /**
     * Runs in every page of the sign-in web view. A modal passkey request is refused at once and
     * [signal] (a JavaScript statement) tells the app, which closes the web view and offers the
     * browser. Conditional requests (passkey autofill) pass. Where the web view has no WebAuthn at
     * all (Android), the API is supplied, so the page's request reaches the guard instead of
     * failing on its own support check with an unknown error. The message says "timed out" because
     * older Pocket ID versions only recognise that wording. The methods are replaced on the
     * container's prototype, not on `navigator.credentials` itself: WKWebView hands out a new
     * wrapper for the container after garbage collection, which would not have them. Same rule as
     * the desktop app's passkey-guard.ts.
     */
    fun passkeyGuardScript(signal: String): String = """
        (() => {
          const marker = Symbol.for('vicu.passkeyGuard')
          if (window[marker]) return
          window[marker] = true
          const refuse = () => {
            $signal
            return Promise.reject(new DOMException('The operation either timed out or was not allowed: passkeys cannot be used in this window.', 'NotAllowedError'))
          }
          if (typeof window.PublicKeyCredential !== 'function') {
            const PublicKeyCredential = function PublicKeyCredential() { throw new TypeError('Illegal constructor') }
            PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable = () => Promise.resolve(false)
            PublicKeyCredential.isConditionalMediationAvailable = () => Promise.resolve(false)
            window.PublicKeyCredential = PublicKeyCredential
          }
          let credentials = navigator.credentials
          if (!credentials) {
            credentials = {}
            Object.defineProperty(navigator, 'credentials', { value: credentials, configurable: true })
          }
          const proto = Object.getPrototypeOf(credentials)
          const target = proto && proto !== Object.prototype ? proto : credentials
          const get = target.get || (() => Promise.resolve(null))
          const create = target.create || (() => Promise.resolve(null))
          target.get = function (options) { return options && options.publicKey && options.mediation !== 'conditional' ? refuse() : get.call(this, options) }
          target.create = function (options) { return options && options.publicKey ? refuse() : create.call(this, options) }
        })()
    """.trimIndent()
}
