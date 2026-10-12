package id.bundasiaga.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.bundasiaga.rules.Level

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val exams by vm.app.db.exams().observeAll().collectAsState(initial = emptyList())
    var search by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = exams.filter { (level == null || it.level == level) &&
        (search.isBlank() || it.patientCode.contains(search.trim(), true)) }
    Column(Modifier.fillMaxSize()) {
        BundaTopBar("Riwayat pemeriksaan", onBack = { vm.back() })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionHeading("${exams.size} pemeriksaan tersimpan", "Hasil terbaru ditampilkan lebih dahulu, termasuk yang belum dirujuk.") }
            item {
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Cari kode pasien") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = if (search.isNotEmpty()) ({ IconButton(onClick = { search = "" }) { Icon(Icons.Filled.Close, "Hapus pencarian") } }) else null)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = level == null, onClick = { level = null }, label = { Text("Semua") })
                    Level.entries.reversed().forEach { lv ->
                        FilterChip(selected = level == lv.name, onClick = { level = lv.name }, label = { Text(lv.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            vm.examError?.let { message -> item { Disclaimer(message) } }
            if (visible.isEmpty()) item {
                EmptyState(if (exams.isEmpty()) "Belum ada pemeriksaan" else "Hasil tidak ditemukan",
                    if (exams.isEmpty()) "Selesaikan pemeriksaan untuk menyimpan hasil di sini." else "Ubah kata pencarian atau pilih filter Semua.", Icons.Filled.History)
            }
            items(visible, key = { it.id }) { record ->
                OutlinedCard(onClick = { vm.openExam(record) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(record.patientCode, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            runCatching { Level.valueOf(record.level) }.getOrNull()?.let { LevelChip(it) }
                        }
                        Text(formatTime(record.createdAt), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (record.outboxId == null) "Hasil tersimpan · belum dibuat rujukan" else "Hasil tersimpan · rujukan tersedia",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ChevronRight, "Buka hasil", tint = Teal)
                        }
                    }
                }
            }
        }
    }
}
