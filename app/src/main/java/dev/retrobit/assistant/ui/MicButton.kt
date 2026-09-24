package dev.retrobit.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.retrobit.assistant.Phase
import dev.retrobit.assistant.R

/**
 * Tombol bicara utama. Mendengarkan: lingkaran membesar mengikuti keras suara.
 * Memproses: denyut pelan. Ikon berubah jadi "stop" saat ada yang bisa dihentikan.
 */
@Composable
fun MicButton(phase: Phase, level: Float, followUp: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val animLevel by animateFloatAsState(if (phase == Phase.LISTENING) level else 0f, tween(120), label = "level")
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "pulseValue",
    )
    val container = when (phase) {
        Phase.LISTENING -> scheme.error
        Phase.THINKING, Phase.SPEAKING -> scheme.secondary
        Phase.IDLE -> scheme.primary
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val base = 42.dp.toPx()
                when (phase) {
                    Phase.LISTENING -> {
                        drawCircle(container.copy(alpha = 0.18f), radius = base + animLevel * 32.dp.toPx())
                        drawCircle(container.copy(alpha = 0.28f), radius = base + animLevel * 16.dp.toPx())
                    }
                    Phase.THINKING, Phase.SPEAKING -> drawCircle(
                        container.copy(alpha = 0.35f * (1f - pulse)),
                        radius = base + pulse * 30.dp.toPx(),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                    Phase.IDLE -> {}
                }
            }
            FilledIconButton(
                onClick = onClick,
                modifier = Modifier.size(84.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = container),
            ) {
                val stop = phase == Phase.LISTENING || phase == Phase.THINKING || phase == Phase.SPEAKING
                Icon(
                    painterResource(if (stop) R.drawable.px_stop_solid else R.drawable.px_mic),
                    contentDescription = when (phase) {
                        Phase.IDLE -> "Bicara"
                        Phase.LISTENING -> "Berhenti mendengarkan"
                        Phase.THINKING -> "Hentikan jawaban"
                        Phase.SPEAKING -> "Hentikan suara"
                    },
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Text(
            when (phase) {
                Phase.IDLE -> "Ketuk untuk bicara"
                Phase.LISTENING -> if (followUp) "Silakan lanjut bicara…" else "Mendengarkan…"
                Phase.THINKING -> "Memproses… ketuk untuk berhenti"
                Phase.SPEAKING -> "Berbicara… ketuk untuk menghentikan"
            },
            fontSize = 16.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
