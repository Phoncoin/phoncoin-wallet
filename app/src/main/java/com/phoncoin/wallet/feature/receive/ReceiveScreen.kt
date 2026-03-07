package com.phoncoin.wallet.feature.receive

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.phoncoin.wallet.core.crypto.KeyDerivation
import com.phoncoin.wallet.core.storage.SecureStore
import com.phoncoin.wallet.core.theme.PhonColors
import com.phoncoin.wallet.R

@Composable
fun ReceiveScreen(
    addressHex: String?,
    explorerBaseUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val msgAddressCopied = stringResource(R.string.address_copied)

    val clipboard = LocalClipboardManager.current
    val store = remember { SecureStore(context) }

    // Security fallback: if addressHex is not provided, derive it from the seed
    val seed = remember { store.getSeed() }
    val derivedAddress = remember(seed) {
        seed?.let { KeyDerivation.deriveFromSeed(it).publicKeyHex }
    }
    val address = addressHex ?: derivedAddress

    fun shortAddr(a: String): String =
        if (a.length <= 22) a else a.take(12) + "…" + a.takeLast(8)

    val qrBitmap = remember(address) { address?.let { generateQrBitmap(it, 900) } }

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.receive_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PhonColors.Text
                )
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.receive_qr_cd),
                            modifier = Modifier.size(260.dp)
                        )
                    } else {
                        Text(stringResource(R.string.receive_qr_unavailable), color = PhonColors.Muted)
                    }

                    // Short address row + actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = address?.let { shortAddr(it) } ?: stringResource(R.string.receive_address_unavailable),
                            color = PhonColors.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(Modifier.width(10.dp))

                        OutlinedButton(
                            onClick = {
                                if (address != null) {
                                    clipboard.setText(AnnotatedString(address))
                                    Toast.makeText(context, msgAddressCopied, Toast.LENGTH_SHORT).show()
                                }
                            },
                            border = BorderStroke(1.dp, PhonColors.Border),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) { Text(stringResource(R.string.copy)) }
                    }

                    // Full address (visually scrollable via ellipsis + optionally multi-line)
                    Text(
                        text = address ?: "—",
                        color = PhonColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Open-in-explorer button (browser)
                    OutlinedButton(
                        onClick = {
                            if (address != null) {
                                val url = explorerBaseUrl // on ouvre l’explorer, l’utilisateur peut coller l’adresse
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, PhonColors.Border),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(stringResource(R.string.receive_open_explorer)) }
                }
            }
        }
    }
}

// --- QR helper ---
private fun generateQrBitmap(text: String, size: Int): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)

    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}