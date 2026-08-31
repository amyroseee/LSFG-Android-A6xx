package com.lsfg.android.ui

import android.text.Html
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.ui.components.IconBadge
import com.lsfg.android.ui.components.LsfgCard
import com.lsfg.android.ui.components.LsfgSecondaryButton
import com.lsfg.android.ui.components.LsfgTopBar
import com.lsfg.android.ui.theme.LsfgPrimary
import com.lsfg.android.ui.theme.LsfgStatusWarn

@Composable
fun LegalScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val bodyHtml = stringResource(R.string.legal_warning_body)
    val plain = remember(bodyHtml) {
        Html.fromHtml(bodyHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.legal_title),
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                IconBadge(icon = Icons.Filled.Gavel, tint = LsfgStatusWarn, size = 56.dp)
            }
            LsfgCard(accent = true) {
                Text(
                    text = "IMPORTANT",
                    style = MaterialTheme.typography.labelSmall,
                    color = LsfgPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = plain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.size(8.dp))
        }

        Button(
            onClick = {
                prefs.setLegalAccepted(true)
                refreshConfigState(prefs)
                nav.navigate(Routes.DLL) {
                    popUpTo(Routes.HOME) { inclusive = false }
                }
            },
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = LsfgPrimary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.legal_accept))
        }
        Spacer(Modifier.height(8.dp))
        LsfgSecondaryButton(
            text = stringResource(R.string.legal_decline),
            onClick = { nav.popBackStack() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
