package com.snapfetch.app.ui.overlay

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.snapfetch.app.ui.theme.SnapFetchTheme

class ShareOverlayActivity : ComponentActivity() {

    private val viewModel: OverlayViewModel by viewModels { OverlayViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle the incoming shared text intent
        handleIntent(intent)

        setContent {
            SnapFetchTheme(isTranslucentOverlay = true) {
                ShareOverlayRoot(
                    viewModel = viewModel,
                    onDismiss = { finishWithSlide() },
                    onDownloadStarted = {
                        Toast.makeText(
                            applicationContext,
                            "⚡ Descarga iniciada en segundo plano",
                            Toast.LENGTH_SHORT
                        ).show()
                        finishWithSlide()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if ("text/plain" == type || type.startsWith("text/")) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                viewModel.processSharedContent(sharedText)
            }
        }
    }

    private fun finishWithSlide() {
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}

@Composable
fun ShareOverlayRoot(
    viewModel: OverlayViewModel,
    onDismiss: () -> Unit,
    onDownloadStarted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val preferences by viewModel.userPreferences.collectAsState()
    val selectedQuality by viewModel.selectedQuality.collectAsState()
    val isPreferencesExpanded by viewModel.isPreferencesExpanded.collectAsState()

    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Dimmed / blurred backdrop scrim: Clicking outside dismisses the overlay
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onDismiss()
                    }
            )
        }

        // Animated Bottom Sheet
        AnimatedVisibility(
            visible = isVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            SnapFetchOverlaySheet(
                uiState = uiState,
                userPreferences = preferences,
                selectedQuality = selectedQuality,
                isPreferencesExpanded = isPreferencesExpanded,
                onTogglePreferences = { viewModel.togglePreferencesExpanded() },
                onSelectMode = { viewModel.selectDownloadMode(it) },
                onSelectQuality = { viewModel.selectQuality(it) },
                onToggleRemember = { viewModel.toggleRememberPreference(it) },
                onDismiss = onDismiss,
                onDownloadTriggered = { metadata, quality ->
                    val context = androidx.compose.ui.platform.AndroidUiDispatcher.Main
                    viewModel.startDownload(
                        context = com.snapfetch.app.SnapFetchApplication.instance,
                        metadata = metadata,
                        quality = quality
                    )
                    onDownloadStarted()
                }
            )
        }
    }
}
