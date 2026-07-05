package com.balancesentinel.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Brand colors ──
private val DeepSeekBlue = Color(0xFF4D6BFE)
private val DeepSeekBlueDark = Color(0xFF3B50D4)
private val SurfaceLight = Color(0xFFF8F9FC)
private val OnSurfaceLight = Color(0xFF1A1C2E)

// ── Semantic colors (深色/浅色自适应) ──
@Immutable
data class WalletSemanticColors(
    val success: Color,
    val warning: Color,
    val granted: Color,
    val neutralGrey: Color,
    val warningBg: Color,
    val warningText: Color,
    val warningTextDim: Color,
    val successBg: Color,
    val warningBgTransparent: Color,
    val neutralBg: Color,
)

private val LightSemanticColors = WalletSemanticColors(
    success = Color(0xFF4CAF50),
    warning = Color(0xFFFF9800),
    granted = Color(0xFF7C4DFF),
    neutralGrey = Color(0xFF9E9E9E),
    warningBg = Color(0xFFFFF3E0),
    warningText = Color(0xFFE65100),
    warningTextDim = Color(0xFFBF360C),
    successBg = Color(0xFF4CAF50).copy(alpha = 0.15f),
    warningBgTransparent = Color(0xFFFF9800).copy(alpha = 0.15f),
    neutralBg = Color(0xFF9E9E9E).copy(alpha = 0.15f),
)

private val DarkSemanticColors = WalletSemanticColors(
    success = Color(0xFF81C784),
    warning = Color(0xFFFFB74D),
    granted = Color(0xFFB39DDB),
    neutralGrey = Color(0xFF9E9E9E),
    warningBg = Color(0xFF3E2723),
    warningText = Color(0xFFFFCC80),
    warningTextDim = Color(0xFFFFAB91),
    successBg = Color(0xFF81C784).copy(alpha = 0.15f),
    warningBgTransparent = Color(0xFFFFB74D).copy(alpha = 0.15f),
    neutralBg = Color(0xFF757575).copy(alpha = 0.15f),
)

val LocalWalletColors = staticCompositionLocalOf { LightSemanticColors }

object WalletColors {
    val success: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.success
    val warning: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.warning
    val granted: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.granted
    val neutralGrey: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.neutralGrey
    val warningBg: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.warningBg
    val warningText: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.warningText
    val warningTextDim: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.warningTextDim
    val successBg: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.successBg
    val warningBgTransparent: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.warningBgTransparent
    val neutralBg: Color @Composable @ReadOnlyComposable get() = LocalWalletColors.current.neutralBg
}

// ── Light color scheme ──
private val LightColorScheme = lightColorScheme(
    primary = DeepSeekBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = DeepSeekBlueDark,
    secondary = Color(0xFF5C5F7E),
    onSecondary = Color.White,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF6B6E8A),
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    error = Color(0xFFE53E3E),
    onError = Color.White,
    outline = Color(0xFFD0D3E4)
)

// ── Dark color scheme ──
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB8C4FF),
    onPrimary = Color(0xFF1A1C2E),
    primaryContainer = Color(0xFF3344A0),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFC1C3E2),
    onSecondary = Color(0xFF1A1C2E),
    surface = Color(0xFF1A1C2E),
    onSurface = Color(0xFFE4E5F0),
    surfaceVariant = Color(0xFF2A2D3E),
    onSurfaceVariant = Color(0xFFB0B3C4),
    background = Color(0xFF1A1C2E),
    onBackground = Color(0xFFE4E5F0),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF1A1C2E),
    outline = Color(0xFF555870)
)

@Composable
fun DeepSeekBalanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalWalletColors provides semanticColors
            ) {
                content()
            }
        }
    )
}
