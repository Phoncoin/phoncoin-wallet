package com.phoncoin.wallet.feature.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.theme.PhonColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedBackupScreen(
    seed: String?,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(18.dp)
    val scroll = rememberScrollState()

    // ✅ Preload strings (IMPORTANT to avoid the @Composable error)
    val msgSeedCopied = stringResource(R.string.seed_copied_toast)
    val msgRevealBeforeCopy = stringResource(R.string.seed_reveal_before_copy)
    val seedFieldLabel = stringResource(R.string.seed_field_label)
    val mask = stringResource(R.string.create_wallet_hidden_mask)

    var revealed by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    Scaffold(
        containerColor = PhonColors.Bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.seed_backup_title),
                        color = PhonColors.Text,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = PhonColors.Accent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PhonColors.Bg)
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    Text(
                        stringResource(R.string.seed_backup_warning),
                        color = PhonColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (seed == null) {
                        Text(
                            stringResource(R.string.seed_wallet_not_initialized),
                            color = PhonColors.Text,
                            fontWeight = FontWeight.SemiBold
                        )
                        return@Column
                    }

                    // ✅ Same rendering as your screenshot: framed field + hidden seed
                    val displayValue = if (revealed) seed else mask

                    OutlinedTextField(
                        value = displayValue,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(seedFieldLabel) }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { revealed = !revealed },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, PhonColors.Border),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (revealed)
                                    stringResource(R.string.seed_hide_phrase)
                                else
                                    stringResource(R.string.seed_show_phrase)
                            )
                        }

                        Button(
                            onClick = {
                                if (!revealed) {
                                    toast(msgRevealBeforeCopy)
                                } else {
                                    clipboard.setText(AnnotatedString(seed))
                                    toast(msgSeedCopied)
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.copy))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.seed_backup_tip),
                color = PhonColors.Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
