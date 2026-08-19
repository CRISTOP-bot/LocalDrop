package com.cristopher.localdrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.cristopher.localdrop.presentation.LocalDropApp
import com.cristopher.localdrop.presentation.MainViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val scanLauncher = registerForActivityResult(ScanContract()) { result -> result.contents?.let(viewModel::onQrScanned) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        handleShare(intent)
        setContent { MaterialTheme { Surface { LocalDropApp(viewModel, onScanQr = { scanLauncher.launch(ScanOptions().apply { setPrompt("Escanea el QR de LocalDrop"); setBeepEnabled(false) }) }) } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent) }
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty() else listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        if (uris.isNotEmpty()) viewModel.filesFromUris(uris)
    }
    companion object { private const val NOTIFICATION_PERMISSION_REQUEST = 7001 }
}
