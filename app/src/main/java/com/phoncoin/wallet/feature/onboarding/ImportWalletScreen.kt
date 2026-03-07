package com.phoncoin.wallet.feature.onboarding

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.crypto.KeyDerivation
import com.phoncoin.wallet.core.theme.PhonColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    onBack: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(18.dp)

    // i18n (Composable OK ici)
    val tBack = stringResource(R.string.back)
    val tTitle = stringResource(R.string.import_seed_title)
    val tDesc = stringResource(R.string.import_seed_desc)
    val tLabel = stringResource(R.string.import_seed_label)
    val tPaste = stringResource(R.string.import_seed_paste)
    val tClipboardEmpty = stringResource(R.string.import_seed_clipboard_empty)
    val tPubkeyPreviewTitle = stringResource(R.string.import_seed_pubkey_preview)
    val tConfirmCheckbox = stringResource(R.string.import_seed_confirm_checkbox)
    val tConfirmRequired = stringResource(R.string.import_seed_confirm_required_toast)
    val tImported = stringResource(R.string.import_seed_imported_toast)
    val tWarning = stringResource(R.string.import_seed_warning)
    val tButtonImport = stringResource(R.string.import_seed_action)

    var seedInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var derivedPreview by remember { mutableStateOf<String?>(null) }
    var confirmChecked by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun normalizeSeed(s: String): String =
        s.trim().lowercase().replace("\n", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")

    fun validateAndPreview(raw: String): Boolean {
        val normalized = normalizeSeed(raw)
        val words = if (normalized.isBlank()) 0 else normalized.split(" ").size

        if (words !in setOf(12, 24)) {
            // ✅ DO NOT use stringResource() here -> context.getString()
            error = context.getString(R.string.import_seed_error_words_fmt, words)
            derivedPreview = null
            confirmChecked = false
            return false
        }

        return try {
            val keys = KeyDerivation.deriveFromSeed(normalized)
            derivedPreview = keys.publicKeyHex.take(20) + "…"
            error = null
            true
        } catch (_: Exception) {
            error = context.getString(R.string.import_seed_error_format)
            derivedPreview = null
            confirmChecked = false
            false
        }
    }

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = tBack,
                        tint = PhonColors.Accent
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    tTitle,
                    color = PhonColors.Text,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Card(
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(tDesc, color = PhonColors.Muted, style = MaterialTheme.typography.bodyMedium)

                    OutlinedTextField(
                        value = seedInput,
                        onValueChange = {
                            seedInput = it
                            error = null
                            derivedPreview = null
                            confirmChecked = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text(tLabel) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )

                    OutlinedButton(
                        onClick = {
                            val clip = clipboard.getText()?.text.orEmpty()
                            if (clip.isBlank()) toast(tClipboardEmpty)
                            else {
                                seedInput = clip
                                validateAndPreview(seedInput)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, PhonColors.Border)
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tPaste)
                    }

                    derivedPreview?.let { preview ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                            border = BorderStroke(1.dp, PhonColors.Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(tPubkeyPreviewTitle, color = PhonColors.Muted, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(preview, color = PhonColors.Text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = confirmChecked, onCheckedChange = { confirmChecked = it })
                                    Spacer(Modifier.width(8.dp))
                                    Text(tConfirmCheckbox, color = PhonColors.Text)
                                }
                            }
                        }
                    }

                    error?.let { Text(it, color = PhonColors.Danger, style = MaterialTheme.typography.bodySmall) }

                    Button(
                        onClick = {
                            val ok = validateAndPreview(seedInput)
                            if (!ok) return@Button
                            if (!confirmChecked) {
                                toast(tConfirmRequired)
                                return@Button
                            }
                            onConfirm(normalizeSeed(seedInput))
                            toast(tImported)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        enabled = seedInput.isNotBlank()
                    ) {
                        Text(tButtonImport)
                    }

                    Text(tWarning, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    BackHandler { onBack() }
}
