package com.phoncoin.wallet.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.phoncoin.wallet.R

// Palette “whitepaper” (accent/bg/border) – style PHONCOIN
private val Bg = Color(0xFF050608)
private val BgAlt = Color(0xFF0B0F16)
private val Accent = Color(0xFF00F28A)
private val Border = Color(0xFF222837)

@Composable
fun SplashScreen(
    onDone: () -> Unit,
    durationMs: Long = 1400L
) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.94f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        scale.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(durationMs)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(BgAlt, Bg, Color.Black),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .alpha(alpha.value)
                .scale(scale.value)
                .fillMaxWidth()
                .border(1.dp, Border, RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF020617), Color(0xFF020617))),
                    RoundedCornerShape(18.dp)
                )
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.phoncoin_logo),
                contentDescription = "PHONCOIN",
                modifier = Modifier
                    .size(92.dp)
                    .padding(bottom = 14.dp)
            )

            Text(
                text = "PHONCOIN",
                color = Accent,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Proof-of-Phone • PoP Secure",
                color = Color(0xFFE5E7EB),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(14.dp))

            // ✅ Seul ce texte est traduisible
            Text(
                text = stringResource(R.string.splash_tagline_world_phone),
                color = Color(0xFFA0A7B5),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "— BabySatoshi",
                color = Accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
