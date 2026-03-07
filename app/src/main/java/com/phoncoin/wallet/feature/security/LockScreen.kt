package com.phoncoin.wallet.feature.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.security.BiometricAuth
import com.phoncoin.wallet.core.security.SecuritySettings
import com.phoncoin.wallet.core.security.SecurityStore
import com.phoncoin.wallet.core.theme.PhonColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockScreen(
    securitySettings: SecuritySettings,
    onUnlockSuccess: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { SecurityStore(context) }
    val shape = RoundedCornerShape(18.dp)

    // ✅ Strings prepared inside the @Composable scope (NEVER inside onClick)
    val title = stringResource(R.string.lock_title)
    val subtitle = stringResource(R.string.lock_subtitle)
    val pinLabel = stringResource(R.string.lock_pin_label)
    val pinPlaceholder = stringResource(R.string.lock_pin_placeholder)
    val errPinTooShort = stringResource(R.string.lock_error_pin_too_short)
    val errPinIncorrect = stringResource(R.string.lock_error_pin_incorrect)
    val msgPinCreated = stringResource(R.string.lock_pin_created_toast)

    val noLocking = stringResource(R.string.lock_dialog_no_locking)
    val enableHint = stringResource(R.string.lock_dialog_enable_hint)

    val bioUnavailableToast = stringResource(R.string.security_biometrics_unavailable_toast)
    val bioSubtitle = stringResource(R.string.lock_biometric_subtitle)
    val bioLabel = stringResource(R.string.lock_biometric_label)
    val bioLoading = stringResource(R.string.lock_biometric_loading)
    val bioNotAvailable = stringResource(R.string.lock_biometric_not_available)
    val bioDialogTitle = stringResource(R.string.lock_dialog_title)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // ✅ “hasPin” state handled properly (avoids calling store.hasPin() everywhere)
    var hasPin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasPin = store.hasPin()
    }

    // ✅ Biometric availability
    val biometricOk = remember { BiometricAuth.isAvailable(context) }
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val biometricEnabledUi = securitySettings.biometricEnabled && biometricOk && activity != null

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, color = PhonColors.Text, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, color = PhonColors.Muted, style = MaterialTheme.typography.bodyMedium)

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

                    // --- PIN ---
                    if (securitySettings.pinEnabled) {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                pin = it.filter(Char::isDigit).take(12)
                                error = null
                            },
                            label = { Text(pinLabel) },
                            placeholder = { Text(pinPlaceholder) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                        val primaryLabel = if (!hasPin) {
                            stringResource(R.string.lock_create_pin)
                        } else {
                            stringResource(R.string.lock_unlock)
                        }

                        Button(
                            onClick = {
                                if (pin.length < 4) {
                                    error = errPinTooShort
                                    return@Button
                                }

                                if (!hasPin) {
                                    // Create the PIN
                                    store.setPin(pin)
                                    hasPin = true
                                    toast(msgPinCreated)
                                    onUnlockSuccess()
                                    return@Button
                                }

                                // PIN verification
                                if (store.verifyPin(pin)) {
                                    onUnlockSuccess()
                                } else {
                                    error = errPinIncorrect
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(primaryLabel)
                        }
                    }

                    // --- BIOMETRICS ---
                    if (securitySettings.biometricEnabled) {
                        OutlinedButton(
                            enabled = biometricEnabledUi,
                            onClick = {
                                val act = activity
                                if (act == null) {
                                    toast(bioUnavailableToast)
                                    return@OutlinedButton
                                }

                                BiometricAuth.authenticate(
                                    activity = act,
                                    title = bioDialogTitle,
                                    subtitle = bioSubtitle,
                                    onSuccess = { onUnlockSuccess() },
                                    onError = { msg -> toast(msg) }
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, PhonColors.Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Fingerprint, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(if (biometricEnabledUi) bioLabel else bioLoading)
                        }

                        if (!biometricOk) {
                            Text(
                                bioNotAvailable,
                                color = PhonColors.Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // --- aucun verrouillage ---
                    if (!securitySettings.pinEnabled && !securitySettings.biometricEnabled) {
                        Text(noLocking, color = PhonColors.Muted)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Text(enableHint, color = PhonColors.Muted)
        }
    }
}

/**
 * ✅ Robust helper: gets the Activity from any Compose Context.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
