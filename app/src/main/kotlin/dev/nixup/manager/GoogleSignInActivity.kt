package dev.nixup.manager

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Google's own sign-in page, the one Android's setup shows (as Aurora Store signs in), for the Google Play account the
 * apps of android.packages.playstore come with. Once an account signs in there, Google gives the page a token that
 * lasts minutes (its oauth_token cookie): it goes back to [MainActivity] with the account's email address, and the
 * module's engine turns it into the account's lasting token (playstore-login), kept where root alone reads it. Nothing
 * of the sign-in stays in the app: its cookies and the page's storage are cleared before and after.
 */
class GoogleSignInActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var trouble: View
    private lateinit var message: TextView
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.google_sign_in_title)
        forget()
        val cookies = CookieManager.getInstance()
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            cookies.setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress.isIndeterminate = false
                    progress.progress = newProgress
                    progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val token = cookie(cookies.getCookie(url), TOKEN) ?: return
                    // The page names the account that signed in; the engine also takes the name Google gives with the token.
                    view.evaluateJavascript(EMAIL) { said -> done(said.trim('"').takeIf { it.contains('@') }.orEmpty(), token) }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) problem(getString(R.string.play_page_failed, error.description))
                }
            }
        }
        val pad = Ui.dp(this, 20)
        message = TextView(this).apply { setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium) }
        trouble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            addView(message)
            addView(Button(this@GoogleSignInActivity).apply {
                setText(R.string.app_info)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            })
        }
        val root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(trouble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // Drawn edge to edge: the page keeps clear of the bars (and of the keyboard, for its fields).
            setOnApplyWindowInsetsListener { view, insets ->
                Ui.padForSystemBars(view, insets, ime = true, consume = true)
            }
        }
        setContentView(root)
        // Back goes back in the page first (a step of the sign-in), then out.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        // Android tells an app it is offline when its network is blocked too (Network access off for it, as LineageOS
        // and others can set it): the page could only fail then.
        if (getSystemService(ConnectivityManager::class.java)?.activeNetwork == null) problem(getString(R.string.play_offline))
        else web.loadUrl(PAGE)
    }

    override fun onResume() {
        super.onResume()
        // Back from App info, with its network on now: the page, at last.
        if (trouble.visibility == View.VISIBLE && getSystemService(ConnectivityManager::class.java)?.activeNetwork != null) {
            trouble.visibility = View.GONE
            web.visibility = View.VISIBLE
            web.loadUrl(PAGE)
        }
    }

    /** Why the page cannot be there, in place of it, with a way to the app's own settings (its network access). */
    private fun problem(text: String) {
        web.visibility = View.GONE
        message.text = text
        trouble.visibility = View.VISIBLE
    }

    @Deprecated("Android 12 and older; newer ones call the callback onCreate registers")
    override fun onBackPressed() = back()

    private fun back() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        forget()
        web.destroy()
        super.onDestroy()
    }

    private fun done(email: String, token: String) {
        if (finished) return
        finished = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_EMAIL, email).putExtra(EXTRA_TOKEN, token))
        finish()
    }

    /** What the page signed in with goes: its cookies and storage. */
    private fun forget() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    companion object {
        const val EXTRA_EMAIL = "email"
        const val EXTRA_TOKEN = "token"

        private const val PAGE = "https://accounts.google.com/EmbeddedSetup"
        private const val TOKEN = "oauth_token"

        /** The account's email address, as the page shows it once it is signed in. */
        private const val EMAIL = "(function() { var e = document.querySelector('[data-profile-identifier][data-email]'); return e ? e.getAttribute('data-email') : null; })();"

        /** A cookie's value, from a Cookie header's text. */
        private fun cookie(cookies: String?, name: String): String? = cookies?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    }
}
