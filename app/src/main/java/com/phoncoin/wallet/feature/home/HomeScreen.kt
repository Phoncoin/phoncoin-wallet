package com.phoncoin.wallet.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.theme.PhonColors

@Composable
fun HomeScreen(
    isInitialized: Boolean,
    onCreateWallet: () -> Unit,
    onImportWallet: () -> Unit,
    onUnlock: () -> Unit,
) {
    val cardShape = RoundedCornerShape(18.dp)

    val tagline = stringResource(R.string.home_tagline)
    val titleDetected = stringResource(R.string.home_wallet_detected)
    val titleSetup = stringResource(R.string.home_setup_wallet)
    val descDetected = stringResource(R.string.home_unlock_desc)
    val descSetup = stringResource(R.string.home_setup_desc)
    val btnUnlock = stringResource(R.string.home_unlock)
    val btnCreate = stringResource(R.string.home_create_wallet)
    val btnImport = stringResource(R.string.home_import_seed)
    val footer = stringResource(R.string.home_footer)

    Surface(modifier = Modifier.fillMaxSize(), color = PhonColors.Bg) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    text = "PHONCOIN",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = PhonColors.Accent
                )
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PhonColors.Muted
                )
            }

            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (isInitialized) titleDetected else titleSetup,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = PhonColors.Text
                    )

                    Text(
                        text = if (isInitialized) descDetected else descSetup,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PhonColors.Muted
                    )

                    if (isInitialized) {
                        Button(
                            onClick = onUnlock,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(btnUnlock) }
                    } else {
                        Button(
                            onClick = onCreateWallet,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(btnCreate) }

                        OutlinedButton(
                            onClick = onImportWallet,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, PhonColors.Border)
                        ) { Text(btnImport) }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    text = footer,
                    color = PhonColors.Muted,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
