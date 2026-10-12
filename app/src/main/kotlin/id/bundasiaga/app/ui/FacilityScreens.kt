package id.bundasiaga.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.bundasiaga.app.data.FacilityEntity
import id.bundasiaga.app.data.InputValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FaskesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val facilities by vm.app.db.facilities().observeAll().collectAsState(initial = emptyList())
    val capacity by vm.app.db.capacity().observeAll().collectAsState(initial = emptyList())
    var search by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FacilityEntity?>(null) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val visible = facilities.filter { search.isBlank() || "${it.name} ${it.id} ${it.kind}".contains(search.trim(), true) }
        .sortedBy { it.travelMinutes ?: Int.MAX_VALUE }

    Column(Modifier.fillMaxSize()) {
        BundaTopBar("Fasilitas kesehatan", onBack = { vm.back() })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionHeading("Kontak rujukan Anda", "Simpan kontak fasilitas yang sudah Anda konfirmasi. Tersedia tanpa internet.") }
            item { BigButton("Tambah fasilitas", { editingId = null; showEditor = true }, icon = Icons.Filled.Add) }
            if (error) item { Disclaimer("Perubahan belum tersimpan. Periksa ruang penyimpanan lalu coba lagi.") }
            if (facilities.isNotEmpty()) item {
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Cari nama atau kode fasilitas") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
            }
            if (visible.isEmpty()) item {
                EmptyState(if (facilities.isEmpty()) "Belum ada fasilitas" else "Fasilitas tidak ditemukan",
                    if (facilities.isEmpty()) "Tambahkan puskesmas, rumah sakit, atau kontak rujukan wilayah Anda." else "Coba nama atau kode lain.", Icons.Filled.LocalHospital)
            }
            items(visible, key = { it.id }) { f ->
                SectionCard(null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.titleLarge)
                            Text("${f.kind} · ${f.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editingId = f.id; showEditor = true }) { Icon(Icons.Filled.Edit, "Edit ${f.name}") }
                    }
                    KeyValue("Perkiraan perjalanan", f.travelMinutes?.let { "$it menit" } ?: "Belum diisi")
                    val live = capacity.firstOrNull { it.faskesId == f.id }
                    if (live != null) CapacityLine(live, System.currentTimeMillis())
                    else Text("Belum ada kabar kapasitas. Hubungi fasilitas untuk memastikan layanan tersedia.", style = MaterialTheme.typography.bodyMedium)
                    SecondaryButton("Hubungi ${f.phone}", { dial(context, f.phone) }, icon = Icons.Filled.Call)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Diperbarui ${formatTime(f.updatedAt)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { deleting = f }) { Icon(Icons.Filled.DeleteOutline, "Hapus ${f.name}") }
                    }
                }
            }
            if (facilities.isNotEmpty()) item { Text("Waktu tempuh diisi oleh pengguna dan dapat berubah. Kapasitas perlu dikonfirmasi sebelum keberangkatan.", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (showEditor && (editingId == null || facilities.any { it.id == editingId }))
        FacilityEditor(vm, facilities.firstOrNull { it.id == editingId }, facilities.map { it.id }.toSet()) { showEditor = false }
    deleting?.let { f ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Hapus ${f.name}?") },
            text = { Text("Kontak ini akan dihapus dari daftar fasilitas di perangkat ini.") },
            confirmButton = { TextButton(onClick = {
                deleting = null
                scope.launch {
                    try { withContext(Dispatchers.IO) { vm.app.db.facilities().delete(f.id) }; error = false }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = true }
                }
            }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } })
    }
}

@Composable
private fun FacilityEditor(vm: AppViewModel, original: FacilityEntity?, ids: Set<String>, onClose: () -> Unit) {
    var id by rememberSaveable(original?.id) { mutableStateOf(original?.id ?: "") }
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name ?: "") }
    var kind by rememberSaveable(original?.id) { mutableStateOf(original?.kind ?: "Puskesmas") }
    var phone by rememberSaveable(original?.id) { mutableStateOf(original?.phone ?: "") }
    var travel by rememberSaveable(original?.id) { mutableStateOf(original?.travelMinutes?.toString() ?: "") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val duplicate = original == null && id in ids
    val valid = id.matches(Regex("[A-Z0-9]{1,8}")) && !duplicate && name.isNotBlank() && kind.isNotBlank() &&
        InputValidation.phone(phone) && (travel.isBlank() || travel.toIntOrNull() in 1..1440)
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(if (original == null) "Tambah fasilitas" else "Edit fasilitas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gunakan kode fasilitas yang disepakati dengan puskesmas untuk menerima kabar kapasitas.")
                OutlinedTextField(id, { id = it.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(8) },
                    label = { Text("Kode fasilitas") }, enabled = original == null && !busy, singleLine = true,
                    isError = duplicate, supportingText = { Text(if (duplicate) "Kode sudah digunakan." else "1–8 huruf atau angka") })
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Nama fasilitas") }, singleLine = true, enabled = !busy)
                OutlinedTextField(kind, { kind = it.take(40) }, label = { Text("Jenis fasilitas") }, singleLine = true, enabled = !busy)
                OutlinedTextField(phone, { phone = it.take(24) }, label = { Text("Nomor telepon") }, singleLine = true, enabled = !busy,
                    isError = phone.isNotBlank() && !InputValidation.phone(phone), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(travel, { travel = it.filter { c -> c in '0'..'9' }.take(4) }, label = { Text("Waktu tempuh (menit, opsional)") },
                    singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = travel.isNotBlank() && travel.toIntOrNull() !in 1..1440)
                if (failed) Text("Belum berhasil disimpan. Coba lagi.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = valid && !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { vm.app.db.facilities().save(FacilityEntity(id, name.trim(), kind.trim(), phone.trim(), travel.toIntOrNull(), vm.app.clock.now())) }
                    onClose()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(if (busy) "Menyimpan…" else "Simpan fasilitas") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Batal") } })
}
