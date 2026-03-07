package com.phoncoin.wallet.feature.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.crypto.SeedGenerator
import com.phoncoin.wallet.core.theme.PhonColors

@Composable
fun CreateWalletScreen(
    onBack: () -> Unit,
    onConfirm: (seedWords: String) -> Unit
) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(18.dp)

    // i18n
    val tTitle = stringResource(R.string.create_wallet_title)
    val tSeedLabel = stringResource(R.string.create_wallet_seed_label)
    val tHiddenMask = stringResource(R.string.create_wallet_hidden_mask)
    val tShow = stringResource(R.string.create_wallet_show)
    val tHide = stringResource(R.string.create_wallet_hide)
    val tTip = stringResource(R.string.create_wallet_tip)
    val tConfirm = stringResource(R.string.create_wallet_confirm)
    val tBack = stringResource(R.string.back)

    var revealed by remember { mutableStateOf(false) }

    // ✅ seed generated only once for this screen
    val words = remember { SeedGenerator.generate12Words(ctx) }
    val seedString = remember(words) { words.joinToString(" ") }

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = tTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = PhonColors.Text
            )

            Card(
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text = tSeedLabel, color = PhonColors.Muted)

                    Text(
                        text = if (revealed) seedString else tHiddenMask,
                        color = PhonColors.Text,
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedButton(
                        onClick = { revealed = !revealed },
                        border = BorderStroke(1.dp, PhonColors.Border),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (revealed) tHide else tShow)
                    }

                    Text(
                        text = tTip,
                        color = PhonColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = { onConfirm(seedString) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(tConfirm)
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, PhonColors.Border),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(tBack)
            }
        }
    }
}
