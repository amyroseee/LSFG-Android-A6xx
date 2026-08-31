package com.lsfg.android.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.session.ExtractResult
import com.lsfg.android.session.ShaderExtractor
import com.lsfg.android.ui.components.IconBadge
import com.lsfg.android.ui.components.LsfgCard
import com.lsfg.android.ui.components.LsfgSecondaryButton
import com.lsfg.android.ui.components.LsfgTopBar
import com.lsfg.android.ui.theme.LsfgPrimary
import com.lsfg.android.ui.theme.LsfgStatusGood
import com.lsfg.android.ui.theme.LsfgStatusWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed class ExtractionState {
    data object Idle : ExtractionState()
    data object Running : ExtractionState()
    data class Done(val success: Boolean, val message: String?) : ExtractionState()
}

@Composable
fun DllPickerScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()

    var pickError by remember { mutableStateOf<String?>(null) }
    var extractionState by remember { mutableStateOf<ExtractionState>(ExtractionState.Idle) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = ctx.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "Lossless.dll"
        if (!name.equals("Lossless.dll", ignoreCase = true)) {
            pickError = "Selected file is \"$name\", expected \"Lossless.dll\". Pick the correct file."
            return@rememberLauncherForActivityResult
        }
        pickError = null
        prefs.setDll(uri.toString(), name)
        refreshConfigState(prefs)
        pendingUri = uri
        extractionState = ExtractionState.Running
    }

    LaunchedEffect(pendingUri, extractionState) {
        val uri = pendingUri
        if (uri != null && extractionState is ExtractionState.Running) {
            val result = withContext(Dispatchers.IO) { ShaderExtractor.extract(ctx, uri) }
            when (result) {
                is ExtractResult.Success -> {
                    prefs.setShadersReady(true)
                    refreshConfigState(prefs)
                    extractionState = ExtractionState.Done(success = true, message = null)
                }
                is ExtractResult.Failure -> {
                    prefs.setShadersReady(false)
                    refreshConfigState(prefs)
                    extractionState = ExtractionState.Done(success = false, message = result.message)
                }
            }
            pendingUri = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_dll),
            onBack = { nav.popBackStack() },
        )

        val statusIcon: ImageVector
        val statusTint = when {
            extractionState is ExtractionState.Done && !(extractionState as ExtractionState.Done).success -> {
                statusIcon = Icons.Filled.Error
                MaterialTheme.colorScheme.error
            }
            state.shadersReady -> {
                statusIcon = Icons.Filled.CheckCircle
                LsfgStatusGood
            }
            state.dllDisplayName != null -> {
                statusIcon = Icons.AutoMirrored.Filled.InsertDriveFile
                LsfgStatusWarn
            }
            else -> {
                statusIcon = Icons.AutoMirrored.Filled.InsertDriveFile
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        }

        LsfgCard(accent = state.shadersReady) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = statusIcon, tint = statusTint, size = 48.dp)
                Spacer(Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.dllDisplayName ?: "No file selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            state.shadersReady -> "Shaders extracted and cached."
                            state.dllDisplayName != null -> "DLL selected. Shaders not extracted yet."
                            else -> stringResource(R.string.dll_status_none)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (extractionState is ExtractionState.Running) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Extracting and translating shaders…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    color = LsfgPrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val s = extractionState
            if (s is ExtractionState.Done) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (s.success) "Extraction succeeded. SPIR-V cached."
                    else "Extraction failed: ${s.message}",
                    color = if (s.success) LsfgStatusGood else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (pickError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pickError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LsfgCard {
            Text(
                text = "SOURCE",
                style = MaterialTheme.typography.labelSmall,
                color = LsfgPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pick Lossless.dll from your own legally purchased copy of Lossless Scaling on Steam.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = extractionState !is ExtractionState.Running,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LsfgPrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.dll_pick_button))
            }
            Spacer(Modifier.height(8.dp))
            LsfgSecondaryButton(
                text = stringResource(R.string.dll_reextract_button),
                onClick = {
                    val uri = state.dllUri?.let(Uri::parse) ?: return@LsfgSecondaryButton
                    prefs.setShadersReady(false)
                    refreshConfigState(prefs)
                    pendingUri = uri
                    extractionState = ExtractionState.Running
                },
                enabled = state.dllUri != null && extractionState !is ExtractionState.Running,
                leadingIcon = Icons.Filled.Refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
