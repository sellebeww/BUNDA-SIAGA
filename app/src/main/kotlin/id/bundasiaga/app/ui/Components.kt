package id.bundasiaga.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.bundasiaga.app.R
import id.bundasiaga.rules.Level

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundaTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = TealDark, titleContentColor = Color.White,
            navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
        ),
    )
}

/** Tampil di setiap layar selama mode simulasi aktif, agar demo tidak pernah tampak seperti kenyataan. */
@Composable
fun SimulationBanner(visible: Boolean, noService: Boolean) {
    if (!visible) return
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF6A1B9A)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Science, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (noService) R.string.sim_banner_no_service else R.string.sim_banner),
            color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = Color.White,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Warna + ikon + kata, tidak pernah warna saja. */
@Composable
fun LevelBanner(level: Level, modifier: Modifier = Modifier, compact: Boolean = false) {
    val st = levelStyle(level)
    val word = stringResource(st.word)
    val action = stringResource(st.action)
    Row(
        modifier
            .fillMaxWidth()
            .background(st.bg, RoundedCornerShape(16.dp))
            .border(3.dp, Color.Black.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(if (compact) 12.dp else 20.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$word. $action" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(st.icon, contentDescription = null, tint = st.fg, modifier = Modifier.size(if (compact) 32.dp else 56.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(word, color = st.fg, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
            Text(action, color = st.fg, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun LevelChip(level: Level) {
    val st = levelStyle(level)
    Row(
        Modifier.background(st.bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(st.icon, contentDescription = null, tint = st.fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(st.word), color = st.fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, container: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

@Composable
fun Bullet(text: String, color: Color = Color.Unspecified) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp), color = color)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Disclaimer(text: String) {
    Box(
        Modifier.fillMaxWidth().background(Color(0xFFFFF4E0), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFB26A00), RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5D3A00))
    }
}

@Composable
fun MockLabel(text: String) {
    Text(
        text,
        modifier = Modifier.background(Color(0xFF455A64), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
    )
}
