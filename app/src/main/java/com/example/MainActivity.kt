package com.example

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // For storage permission fallback below API 29
    private var pendingDownloadUrl: String? = null
    private var pendingUserAgent: String? = null
    private var pendingContentDisposition: String? = null
    private var pendingMimeType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Log all uncaught exceptions cleanly to system console
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("FB_Wrapper_Crash", "Uncaught exception in thread " + thread.name, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()
        setupOnBackPressed()
    }

    private fun setupWebView() {
        if (!::webView.isInitialized) return

        try {
            // Basic web settings configurations
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                javaScriptCanOpenWindowsAutomatically = true
                
                try {
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                // Custom User-Agent exactly as specified
                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            // Keep cookies enabled and synchronized
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Setup clients
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Return false to handle link navigation entirely in the WebView
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (::progressBar.isInitialized) {
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (::progressBar.isInitialized) {
                    progressBar.visibility = View.GONE
                }

                // Advanced JS Injection (Element Blocker) as specified
                // An IIFE with MutationObserver to continuously look for and remove specific DOM elements.
                val js = """
                    (function() {
                        var selectors = ['.m.fixed-container.bottom', '.m.bg-s3'];
                        function removeElements() {
                            selectors.forEach(function(selector) {
                                try {
                                    var elements = document.querySelectorAll(selector);
                                    elements.forEach(function(el) {
                                        el.remove();
                                    });
                                } catch(e) {
                                    console.error('Error removing element', selector, e);
                                }
                            });
                        }
                        // Initial pass
                        removeElements();
                        // Dynamic SPA observation
                        var observer = new MutationObserver(function(mutations) {
                            removeElements();
                        });
                        observer.observe(document.body || document.documentElement, {
                            childList: true,
                            subtree: true
                        });
                    })();
                """.trimIndent()
                
                try {
                    view?.evaluateJavascript(js, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    runOnUiThread {
                        if (!isFinishing) {
                            Toast.makeText(this@MainActivity, "Connection failure. Please verify internet access.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (::progressBar.isInitialized) {
                    progressBar.progress = newProgress
                    if (newProgress == 100) {
                        progressBar.visibility = View.GONE
                    } else {
                        progressBar.visibility = View.VISIBLE
                    }
                }
            }
        }

        // Handled initial URL loading
        try {
            webView.loadUrl("https://m.facebook.com")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Intercept download URLs and route them to modern DownloadManager
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            startDownloadProcess(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun startDownloadProcess(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Write permission check for older versions (API <= 28)
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadUrl = url
                pendingUserAgent = userAgent
                pendingContentDisposition = contentDisposition
                pendingMimeType = mimetype
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
                return
            }
        }
        enqueueDownload(url, userAgent, contentDisposition, mimetype)
    }

    private fun enqueueDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            val downloadUri = Uri.parse(url)
            val request = DownloadManager.Request(downloadUri)

            // Dynamic session cookies extraction & headers integration
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrEmpty()) {
                request.addRequestHeader("cookie", cookie)
            }
            request.addRequestHeader("User-Agent", userAgent)

            // Guess proper filename & guess correct mime type
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            request.setMimeType(mimetype)

            // Dynamic Notification settings for start/completion of download task
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setTitle(fileName)
            request.setDescription("Downloading media attachment from Facebook wrapper...")

            // Save under public Downloads storage folder
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            runOnUiThread {
                if (!isFinishing) {
                    Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                if (!isFinishing) {
                    Toast.makeText(this, "Failed to start download: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val url = pendingDownloadUrl
                val ua = pendingUserAgent
                val cd = pendingContentDisposition
                val mt = pendingMimeType
                if (url != null && ua != null && cd != null && mt != null) {
                    enqueueDownload(url, ua, cd, mt)
                }
            } else {
                runOnUiThread {
                    if (!isFinishing) {
                        Toast.makeText(this, "Storage permission is required to save downloads.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            // Clear cache
            pendingDownloadUrl = null
            pendingUserAgent = null
            pendingContentDisposition = null
            pendingMimeType = null
        }
    }

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    companion object {
        private const val STORAGE_PERMISSION_CODE = 101
    }
}
