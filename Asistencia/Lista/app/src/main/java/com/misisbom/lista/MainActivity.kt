package com.misisbom.lista

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    private var showUpdateDialog by mutableStateOf(false)
    private var isDownloadingUpdate by mutableStateOf(false)
    private var downloadProgress by mutableStateOf(0f)
    private var updateErrorMsg by mutableStateOf("")
    private var updateApkUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set status bar and navigation bar color to match the app theme
        window.statusBarColor = Color.parseColor("#0f172a")
        window.navigationBarColor = Color.parseColor("#0f172a")

        // Programmatically apply window insets to prevent drawing under status bars
        val rootLayout = findViewById<FrameLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        webView = findViewById(R.id.webView)

        webView.apply {
            // Configure WebView settings for full application capability
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Use native viewport sizing from HTML
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false

            // Setup WebViewAssetLoader to serve local assets from a secure domain
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this@MainActivity))
                .build()

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    return false
                }
            }

            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidApp")

            // Load local assets through the virtual secure domain
            loadUrl("https://appassets.androidplatform.net/assets/lista.html")
        }

        // Hook back button dispatcher callback to handle WebView back navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        // Check for OTA updates (using version.json layout)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://sisbom-de5f8.web.app/lista-version.json")
                val connection = url.openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val stream = connection.getInputStream()
                val text = stream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(text)
                if (json.has("android")) {
                    val androidObj = json.getJSONObject("android")
                    val serverVersionCode = androidObj.getInt("versionCode")
                    val apkUrl = androidObj.getString("url")

                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        pInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode.toLong()
                    }

                    if (serverVersionCode > currentVersionCode) {
                        withContext(Dispatchers.Main) {
                            updateApkUrl = apkUrl
                            showUpdateDialog = true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Add ComposeView for OTA Update Dialog
        val composeView = ComposeView(this).apply {
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = ComposeColor(0xFF0F172A),
                        surface = ComposeColor(0xFF0F172A)
                    )
                ) {
                    if (showUpdateDialog) {
                        UpdateDialog()
                    }
                }
            }
        }
        rootLayout.addView(composeView)
    }

    @Composable
    fun UpdateDialog() {
        AlertDialog(
            onDismissRequest = { /* Bloqueante, no cerrar */ },
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = ComposeColor(0xFFB91C1C),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ACTUALIZACIÓN REQUERIDA",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = ComposeColor.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Hay una nueva versión disponible para seguir utilizando Asistencia de manera segura.",
                        fontSize = 12.sp,
                        color = ComposeColor(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isDownloadingUpdate) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            color = ComposeColor(0xFFB91C1C),
                            trackColor = ComposeColor(0xFFB91C1C).copy(alpha = 0.2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Descargando: ${(downloadProgress * 100).toInt()}%",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = ComposeColor.White
                        )
                    } else {
                        if (updateErrorMsg.isNotEmpty()) {
                            Text(
                                text = updateErrorMsg,
                                color = ComposeColor.Red,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = "Presione el botón inferior para descargar e instalar automáticamente la última versión.",
                            fontSize = 11.sp,
                            color = ComposeColor(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { downloadAndInstallApk(updateApkUrl) },
                    enabled = !isDownloadingUpdate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ComposeColor(0xFFB91C1C),
                        disabledContainerColor = ComposeColor(0xFFB91C1C).copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isDownloadingUpdate) "DESCARGANDO..." else "DESCARGAR E INSTALAR",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = ComposeColor.White
                    )
                }
            },
            containerColor = ComposeColor(0xFF0F172A),
            shape = RoundedCornerShape(20.dp)
        )
    }

    fun downloadAndInstallApk(urlString: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    this,
                    "Permiso de instalación requerido. Habilítelo e intente nuevamente.",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            }
        }

        if (isDownloadingUpdate) return
        isDownloadingUpdate = true
        updateErrorMsg = ""
        downloadProgress = 0f

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var url = java.net.URL(urlString)
                var connection = url.openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.instanceFollowRedirects = false
                
                var redirectCount = 0
                var status = connection.responseCode
                while (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                    status == 307 || status == 308) {
                    
                    if (redirectCount > 5) break
                    val newUrl = connection.getHeaderField("Location") ?: break
                    url = java.net.URL(url, newUrl)
                    connection = url.openConnection() as java.net.HttpURLConnection
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.instanceFollowRedirects = false
                    status = connection.responseCode
                    redirectCount++
                }

                val fileLength = connection.contentLength
                val apkFile = File(cacheDir, "SisBom_Lista_Update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val input = java.io.BufferedInputStream(connection.inputStream, 8192)
                val output = java.io.FileOutputStream(apkFile)

                val data = ByteArray(1024)
                var total: Long = 0
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        downloadProgress = total.toFloat() / fileLength.toFloat()
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                if (apkFile.exists() && apkFile.length() > 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Descarga completada. Iniciando instalación...", Toast.LENGTH_SHORT).show()
                        installApk(apkFile)
                    }
                } else {
                    throw Exception("El archivo descargado está vacío")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isDownloadingUpdate = false
                    updateErrorMsg = e.localizedMessage ?: "Error de descarga"
                    Toast.makeText(this@MainActivity, "Error al descargar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al iniciar instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    class WebAppInterface(private val activity: MainActivity) {
        @android.webkit.JavascriptInterface
        fun updateLauncherIcon(key: String) {
            val useClient = key.trim().isNotEmpty()
            val pm = activity.packageManager
            val defaultAlias = android.content.ComponentName(activity, "com.misisbom.lista.MainActivityDefault")
            val placillaAlias = android.content.ComponentName(activity, "com.misisbom.lista.MainActivityPlacilla")

            try {
                if (useClient) {
                    pm.setComponentEnabledSetting(
                        placillaAlias,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        defaultAlias,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                } else {
                    pm.setComponentEnabledSetting(
                        defaultAlias,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        placillaAlias,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @android.webkit.JavascriptInterface
        fun updateApk(apkUrl: String) {
            activity.runOnUiThread {
                activity.downloadAndInstallApk(apkUrl)
            }
        }
    }
}
