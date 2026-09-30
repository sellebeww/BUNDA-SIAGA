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
val Coral = Color(0xFFE0625A)
val Cream = Color(0xFFF8F1E7)
val Ink = Color(0xFF1B1B1B)

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

// Semuanya satu tingkat lebih besar dari bawaan Material: untuk pembaca di layar kecil dan murah.
private val BundaTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 19.sp, lineHeight = 27.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodySmall = base.bodySmall.copy(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
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
            surfaceVariant = Color(0xFFEFE7DA),
            onSurfaceVariant = Color(0xFF4A4A4A),
            error = MerahBg,
        ),
        typography = BundaTypography,
        content = content,
    )
}
