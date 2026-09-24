package dev.retrobit.assistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.retrobit.assistant.launch.ACTION_LISTEN
import dev.retrobit.assistant.ui.AssistantApp

class MainActivity : ComponentActivity() {
    private val vm: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RbitTheme {
                Surface(Modifier.fillMaxSize()) { AssistantApp(vm) }
            }
        }
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
    }

    override fun onStop() {
        vm.setForeground(false)
        super.onStop()
    }

    private fun handleLaunch(intent: Intent?) {
        if (intent?.action == ACTION_LISTEN || intent?.action == Intent.ACTION_ASSIST) vm.requestListen()
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
    }
}

@Composable
fun RbitTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, typography = PixelTypography, content = content)
}

/** PixelOperator (CC0, Jayvee Enaguas) untuk seluruh teks aplikasi. */
private val PixelOperator = FontFamily(
    Font(R.font.pixel_operator, FontWeight.Normal),
    Font(R.font.pixel_operator_bold, FontWeight.Bold),
)

/**
 * Font piksel tampak lebih kecil daripada Roboto pada ukuran yang sama, jadi setiap
 * gaya Material 3 diperbesar ~20% (minimal 14sp) dan judul memakai varian tebal.
 */
private val PixelTypography: Typography = Typography().run {
    fun TextStyle.px(bold: Boolean = false): TextStyle {
        val size = maxOf(14f, Math.round(fontSize.value * 1.2f).toFloat())
        return copy(
            fontFamily = PixelOperator,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = size.sp,
            lineHeight = (size * 1.3f).sp,
            letterSpacing = 0.sp,
        )
    }
    Typography(
        displayLarge = displayLarge.px(true), displayMedium = displayMedium.px(true), displaySmall = displaySmall.px(true),
        headlineLarge = headlineLarge.px(true), headlineMedium = headlineMedium.px(true), headlineSmall = headlineSmall.px(true),
        titleLarge = titleLarge.px(true), titleMedium = titleMedium.px(true), titleSmall = titleSmall.px(true),
        bodyLarge = bodyLarge.px(), bodyMedium = bodyMedium.px(), bodySmall = bodySmall.px(),
        labelLarge = labelLarge.px(true), labelMedium = labelMedium.px(), labelSmall = labelSmall.px(),
    )
}
