package com.mediaconverter.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mediaconverter.app.R
import com.mediaconverter.app.ui.components.FormatSelector
import com.mediaconverter.app.ui.components.UrlInputCard
import com.mediaconverter.app.ui.theme.GradientEnd
import com.mediaconverter.app.ui.theme.GradientMid
import com.mediaconverter.app.ui.theme.GradientStart
import com.mediaconverter.app.viewmodel.HomeViewModel
import com.mediaconverter.app.viewmodel.LinkUiState

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(
            LocalContext.current.applicationContext as android.app.Application,
        ),
    ),
    sharedUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) viewModel.onUrlChange(sharedUrl)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
        )
        
        Text(
            text = "הורדה קלה ומהירה מיוטיוב ופייסבוק",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // URL Input
        UrlInputCard(
            url = uiState.url,
            onUrlChange = { viewModel.onUrlChange(it) },
            onPasteClick = {
                val clipText = clipboardManager.getText()?.text
                if (!clipText.isNullOrEmpty()) {
                    viewModel.onUrlChange(clipText)
                }
            },
            enabled = !uiState.isDownloading,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Video Info or Error
        if (uiState.isFetchingInfo) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            Text(text = stringResource(R.string.fetching_info))
        } else if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.linkState is LinkUiState.Error) {
                    Button(onClick = viewModel::retry) {
                        Text(stringResource(R.string.retry))
                    }
                }
                TextButton(onClick = viewModel::clear) {
                    Text(stringResource(R.string.clear))
                }
            }
        } else if (uiState.videoInfo != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.videoInfo!!.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Format & Quality Selection
        if (uiState.videoInfo != null && !uiState.isDownloading) {
            FormatSelector(
                selectedFormat = uiState.selectedFormat,
                onFormatSelected = { viewModel.onFormatChange(it) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

        }

        if (uiState.downloadMessage != null) {
            Text(
                text = uiState.downloadMessage!!,
                style = MaterialTheme.typography.bodyLarge,
                color = if (uiState.isDownloading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (uiState.isDownloading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            OutlinedButton(onClick = viewModel::cancelDownload) {
                Text("ביטול הורדה")
            }
        } else if (uiState.videoInfo != null) {
            Button(
                onClick = { viewModel.startDownload() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(GradientStart, GradientMid, GradientEnd)
                        ),
                        shape = RoundedCornerShape(32.dp)
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                contentPadding = PaddingValues()
            ) {
                Text(
                    text = stringResource(
                        if (uiState.downloadCompleted) {
                            R.string.download_another_button
                        } else {
                            R.string.download_button
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }
    }
}
