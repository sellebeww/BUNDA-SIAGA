package id.bundasiaga.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import id.bundasiaga.app.R
import id.bundasiaga.codec.Tingkat
import id.bundasiaga.rules.Level

// Palet diambil dari logo BUNDA SIAGA.
val Teal = Color(0xFF1F6F6B)
val TealDark = Color(0xFF0F4C4A)
val Coral = Color(0xFFAD4942)
val Cream = Color(0xFFF5F8F7)
val Ink = Color(0xFF18332F)

// Warna level: kontras tinggi, selalu berpasangan dengan ikon dan kata.
val MerahBg = Color(0xFFB3261E)
val KuningBg = Color(0xFFFFC928)
val HijauBg = Color(0xFF1B7F3B)

data class LevelStyle(val bg: Color, val fg: Color, val icon: ImageVector, val word: Int, val action: Int)

fun levelStyle(level: Level) = when (level) {
    Level.MERAH -> LevelStyle(MerahBg, Color.White, Icons.Filled.Error, R.string.level_merah, R.string.level_merah_action)
    Level.KUNING -> LevelStyle(KuningBg, Ink, Icons.Filled.Warning, R.string.level_kuning, R.string.level_kuning_action)
    Level.HIJAU -> LevelStyle(HijauBg, Color.White, Icons.Filled.CheckCircle, R.string.level_hijau, R.string.level_hijau_action)
}

fun levelStyle(t: Tingkat) = levelStyle(
    when (t) {
        Tingkat.MERAH -> Level.MERAH
        Tingkat.KUNING -> Level.KUNING
        Tingkat.HIJAU -> Level.HIJAU
    },
)

private val base = Typography()

// Readable body text, a clear heading hierarchy, and room for system font scaling.
private val BundaTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun BundaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCFE8E4),
            onPrimaryContainer = TealDark,
            secondary = Coral,
            onSecondary = Color.White,
            background = Cream,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFE9F0ED),
            onSurfaceVariant = Color(0xFF50655F),
            outline = Color(0xFF748780),
            outlineVariant = Color(0xFFD8E4DE),
            secondaryContainer = Color(0xFFFFEDE7),
            onSecondaryContainer = Color(0xFF79372F),
            error = MerahBg,
        ),
        typography = BundaTypography,
        content = content,
    )
}
