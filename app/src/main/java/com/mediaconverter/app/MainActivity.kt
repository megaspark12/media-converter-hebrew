package com.mediaconverter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.mediaconverter.app.data.ShareTextParser
import com.mediaconverter.app.ui.screens.HomeScreen
import com.mediaconverter.app.ui.theme.MediaConverterTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

internal class ShareEventViewModel : ViewModel() {
    private val events = Channel<String>(Channel.BUFFERED)
    val sharedUrls: Flow<String> = events.receiveAsFlow()
    private var initialIntentConsumed = false

    fun acceptInitial(url: String?) {
        if (initialIntentConsumed) return
        initialIntentConsumed = true
        url?.let(events::trySend)
    }

    fun acceptNew(url: String?) {
        url?.let(events::trySend)
    }
}

class MainActivity : ComponentActivity() {
    private val shareEvents by viewModels<ShareEventViewModel>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result handled automatically
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeShareIntent(intent, isInitialIntent = true)
        
        // Request necessary permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: Request notification permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 and below: Request storage permission to write to public Downloads
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        setContent {
            MediaConverterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        sharedUrls = shareEvents.sharedUrls,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeShareIntent(intent, isInitialIntent = false)
    }

    private fun consumeShareIntent(intent: Intent?, isInitialIntent: Boolean) {
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val url = ShareTextParser.parse(intent?.action, intent?.type, text)?.value
        if (isInitialIntent) shareEvents.acceptInitial(url) else shareEvents.acceptNew(url)
    }
}
