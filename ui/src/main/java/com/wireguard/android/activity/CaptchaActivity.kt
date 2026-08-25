/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wireguard.android.util.CaptchaBrowserProfile

/** Displays VK's CAPTCHA and returns its success token to the native authorization flow. */
class CaptchaActivity : AppCompatActivity() {
    private var previousNetwork: Network? = null
    private var didBindNetwork = false
    private var requestId = ""
    private var resultDelivered = false
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CAPTCHA is interactive and time-limited: make the app task visible immediately instead
        // of leaving the activity behind whatever the user opened while TURN was connecting.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        try {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .appTasks
                .firstOrNull()
                ?.moveToFront()
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to move CAPTCHA task to foreground", e)
        }

        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val redirectUri = intent.getStringExtra(EXTRA_REDIRECT_URI)
        if (requestId.isEmpty() || redirectUri.isNullOrEmpty() || !CaptchaCoordinator.attach(requestId, this)) {
            Log.e(TAG, "Missing or stale CAPTCHA request")
            finish()
            return
        }

        bindToPhysicalNetwork()
        val profile = CaptchaBrowserProfile.get(this)
        val documentStartScript = profile.navigatorOverridesScript() + "\n" + INTERCEPT_SCRIPT

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = profile.userAgent
            addJavascriptInterface(CaptchaBridge(), JAVASCRIPT_BRIDGE)
            webChromeClient = WebChromeClient()

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try {
                    WebViewCompat.addDocumentStartJavaScript(this, documentStartScript, VK_ORIGIN_RULES)
                    Log.d(TAG, "Installed CAPTCHA interceptor at document start")
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to install document-start CAPTCHA interceptor", e)
                }
            } else {
                Log.w(TAG, "Document-start scripts are unsupported by this WebView; using callback injection")
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!deliverTokenFromUrl(url)) injectInterceptor(view, documentStartScript)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    if (!deliverTokenFromUrl(url)) injectInterceptor(view, documentStartScript)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!deliverTokenFromUrl(url)) injectInterceptor(view, documentStartScript)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    deliverTokenFromUrl(request?.url?.toString())
                    return false
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Log.e(
                            TAG,
                            "CAPTCHA page load failed for ${request.url.host}: " +
                                "code=${error?.errorCode}, description=${error?.description}",
                        )
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        Log.e(
                            TAG,
                            "CAPTCHA page HTTP error for ${request.url.host}: " +
                                "status=${errorResponse?.statusCode}",
                        )
                    }
                }
            }
        }

        setContentView(requireNotNull(webView))
        Log.d(TAG, "Loading CAPTCHA page for request $requestId")
        webView?.loadUrl(redirectUri, mapOf("Accept-Language" to profile.acceptLanguageHeader()))
    }

    private fun injectInterceptor(view: WebView?, script: String) {
        view?.evaluateJavascript(script, null)
    }

    private fun deliverTokenFromUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return try {
            val uri = Uri.parse(url)
            val directToken = uri.getQueryParameter("success_token")
            val fragmentToken = uri.fragment
                ?.let { Uri.parse("https://localhost/?$it").getQueryParameter("success_token") }
            val token = directToken ?: fragmentToken
            if (token.isNullOrEmpty()) false else {
                deliverResult(token)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect CAPTCHA redirect", e)
            false
        }
    }

    private fun deliverResult(token: String) {
        if (resultDelivered) return
        resultDelivered = true
        val accepted = CaptchaCoordinator.complete(requestId, token)
        Log.d(TAG, "CAPTCHA result ${if (accepted) "delivered" else "ignored"} for request $requestId")
        if (accepted) finish()
    }

    private inner class CaptchaBridge {
        @JavascriptInterface
        fun onResult(successToken: String) {
            if (successToken.isEmpty()) return
            runOnUiThread { deliverResult(successToken) }
        }

        @JavascriptInterface
        fun onProfile(browserFp: String?, deviceJson: String?, adFp: String?) {
            CaptchaBrowserProfile.recordCaptured(this@CaptchaActivity, browserFp, deviceJson, adFp)
            Log.d(TAG, "Persisted CAPTCHA browser fingerprint captured from WebView")
        }
    }

    private fun bindToPhysicalNetwork() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            previousNetwork = connectivityManager.boundNetworkForProcess
            for (network in connectivityManager.allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

                connectivityManager.bindProcessToNetwork(network)
                didBindNetwork = true
                Log.d(TAG, "Bound CAPTCHA WebView to physical network: $network")
                return
            }
            Log.w(TAG, "No physical network found for CAPTCHA WebView")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind CAPTCHA WebView to physical network", e)
        }
    }

    private fun restoreNetworkBinding() {
        if (!didBindNetwork) return
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.bindProcessToNetwork(previousNetwork)
            Log.d(TAG, "Restored previous process network binding")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore process network binding", e)
        }
    }

    override fun onDestroy() {
        Log.d(
            TAG,
            "Destroying CAPTCHA activity: resultDelivered=$resultDelivered, " +
                "changingConfigurations=$isChangingConfigurations, finishing=$isFinishing",
        )
        if (!resultDelivered && !isChangingConfigurations) {
            CaptchaCoordinator.complete(requestId, "")
        }
        webView?.apply {
            removeJavascriptInterface(JAVASCRIPT_BRIDGE)
            stopLoading()
            destroy()
        }
        webView = null
        restoreNetworkBinding()
        // Signal the coordinator only after the process-wide network binding is restored.
        CaptchaCoordinator.detach(requestId, this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WireGuard/CaptchaActivity"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_REDIRECT_URI = "redirect_uri"
        private const val JAVASCRIPT_BRIDGE = "AndroidCaptcha"
        private val VK_ORIGIN_RULES = setOf(
            "https://vk.ru",
            "https://*.vk.ru",
            "https://vk.com",
            "https://*.vk.com",
        )

        private val INTERCEPT_SCRIPT = """
            (function() {
                if (window.__wireguardCaptchaInterceptorInstalled) return;
                window.__wireguardCaptchaInterceptorInstalled = true;

                function findToken(value, depth) {
                    if (!value || depth > 4) return null;
                    if (typeof value === 'string') {
                        try { return findToken(JSON.parse(value), depth + 1); } catch (_) { return null; }
                    }
                    if (typeof value !== 'object') return null;
                    if (typeof value.success_token === 'string' && value.success_token.length > 0) {
                        return value.success_token;
                    }
                    for (var key in value) {
                        if (Object.prototype.hasOwnProperty.call(value, key)) {
                            var token = findToken(value[key], depth + 1);
                            if (token) return token;
                        }
                    }
                    return null;
                }

                function report(value) {
                    try {
                        var token = findToken(value, 0);
                        if (token) AndroidCaptcha.onResult(token);
                    } catch (_) {}
                }

                function captureProfile(body) {
                    try {
                        if (typeof body !== 'string' || body.length === 0) return;
                        var params = new URLSearchParams(body);
                        var browserFp = params.get('browser_fp');
                        var device = params.get('device');
                        var adFp = params.get('adFp');
                        if (browserFp || device || adFp) {
                            AndroidCaptcha.onProfile(browserFp || '', device || '', adFp || '');
                        }
                    } catch (_) {}
                }

                var originalOpen = XMLHttpRequest.prototype.open;
                var originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function() {
                    this.__wireguardCaptchaUrl = arguments[1];
                    return originalOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    var xhr = this;
                    captureProfile(arguments[0]);
                    xhr.addEventListener('load', function() {
                        try { report(xhr.responseType === 'json' ? xhr.response : xhr.responseText); } catch (_) {}
                    });
                    return originalSend.apply(this, arguments);
                };

                var originalFetch = window.fetch;
                if (originalFetch) {
                    window.fetch = function() {
                        try { captureProfile(arguments[1] && arguments[1].body); } catch (_) {}
                        var promise = originalFetch.apply(this, arguments);
                        promise.then(function(response) {
                            response.clone().text().then(report).catch(function() {});
                        }).catch(function() {});
                        return promise;
                    };
                }

                window.addEventListener('message', function(event) { report(event.data); });
            })();
        """.trimIndent()

        internal fun createIntent(context: Context, requestId: String, redirectUri: String): Intent {
            return Intent(context, CaptchaActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_REDIRECT_URI, redirectUri)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
        }

        fun solveCaptcha(context: Context, redirectUri: String): String {
            return CaptchaCoordinator.solve(context, redirectUri)
        }
    }
}
