package com.phoncoin.wallet.feature.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.security.BiometricAuth
import com.phoncoin.wallet.core.security.SecuritySettings
import com.phoncoin.wallet.core.security.SecurityStore
import com.phoncoin.wallet.core.theme.PhonColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // i18n (safe for callbacks)
    val msgCreatePinHint = stringResource(R.string.security_create_pin_hint_toast)
    val msgBiometricsUnavailable = stringResource(R.string.security_biometrics_unavailable_toast)
    val msgEnablePinFirst = stringResource(R.string.security_enable_pin_first_toast)
    val msgPinDeleted = stringResource(R.string.security_pin_deleted_toast)

    val store = remember { SecurityStore(context) }
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(18.dp)

    var settings by remember { mutableStateOf(store.load()) }

    val biometricOk = remember { BiometricAuth.isAvailable(context) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun save(new: SecuritySettings) {
        settings = new
        store.save(new)
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
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = PhonColors.Accent
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.security_title),
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
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {

                    // ✅ PIN (fix: Column weighted so Switch never gets pushed out)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                stringResource(R.string.security_pin_title),
                                color = PhonColors.Text,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(R.string.security_pin_subtitle),
                                color = PhonColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = settings.pinEnabled,
                            onCheckedChange = { checked ->
                                if (checked && !store.hasPin()) {
                                    toast(msgCreatePinHint)
                                }
                                save(settings.copy(pinEnabled = checked).let {
                                    // if PIN is disabled, also disable biometrics
                                    if (!checked) it.copy(biometricEnabled = false) else it
                                })
                            }
                        )
                    }

                    // ✅ Biometrics (already weighted, just add ellipsis safety)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                stringResource(R.string.security_biometrics_title),
                                color = PhonColors.Text,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (biometricOk) "Empreinte / FaceID / code système"
                                else "Non disponible sur cet appareil",
                                color = PhonColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = settings.biometricEnabled,
                            onCheckedChange = { checked ->
                                if (!biometricOk && checked) {
                                    toast(msgBiometricsUnavailable)
                                    return@Switch
                                }
                                if (checked && !settings.pinEnabled) {
                                    toast(msgEnablePinFirst)
                                    return@Switch
                                }
                                save(settings.copy(biometricEnabled = checked))
                            },
                            enabled = biometricOk
                        )
                    }

                    HorizontalDivider(color = PhonColors.Border)

                    Text(
                        stringResource(R.string.security_autolock_title),
                        color = PhonColors.Text,
                        fontWeight = FontWeight.SemiBold
                    )

                    val options = listOf(
                        0 to "Immédiat",
                        30 to stringResource(R.string.security_delay_30s),
                        60 to stringResource(R.string.security_delay_1min),
                        300 to stringResource(R.string.security_delay_5min),
                        900 to stringResource(R.string.security_delay_15min)
                    )

                    var expanded by remember { mutableStateOf(false) }
                    val currentLabel = options.firstOrNull { it.first == settings.autoLockSeconds }?.second
                        ?: "${settings.autoLockSeconds}s"

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .menuAnchor(
                                    type = MenuAnchorType.PrimaryNotEditable,
                                    enabled = true
                                )
                                .fillMaxWidth(),
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.security_delay_label)) }
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            options.forEach { (sec, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        save(settings.copy(autoLockSeconds = sec))
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = PhonColors.Border)

                    // ✅ Privacy mode (fix: Column weighted so Switch always visible)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                stringResource(R.string.security_privacy_mode_title),
                                color = PhonColors.Text,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(R.string.security_privacy_mode_desc),
                                color = PhonColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = settings.privacyMode,
                            onCheckedChange = { save(settings.copy(privacyMode = it)) }
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            store.clearPin()
                            save(store.load())
                            toast(msgPinDeleted)
                        },
                        border = BorderStroke(1.dp, PhonColors.Border),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.security_delete_pin))
                    }
                }
            }
        }
    }
}
