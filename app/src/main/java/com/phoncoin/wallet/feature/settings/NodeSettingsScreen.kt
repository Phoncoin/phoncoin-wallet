package com.phoncoin.wallet.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.theme.PhonColors

@Composable
fun NodeSettingsScreen(
    currentUrl: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    var url by remember { mutableStateOf(currentUrl) }

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.node_settings_title),
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
                    Text(stringResource(R.string.node_settings_url_label), color = PhonColors.Muted)

                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://api.phonchain.org") }
                    )

                    Text(
                        text = stringResource(R.string.node_settings_example),
                        color = PhonColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Auto = nodes.json + cache + fallback
            OutlinedButton(
                onClick = { onSave("") },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, PhonColors.Border),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.node_settings_auto)) }

            // Manual override
            Button(
                onClick = { onSave(url) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.node_settings_save)) }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, PhonColors.Border),
                shape = RoundedCornerShape(14.dp)
            ) { Text(stringResource(R.string.back)) }
        }
    }
}
