package id.bundasiaga.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.bundasiaga.app.BuildConfig
import id.bundasiaga.app.R
import id.bundasiaga.app.data.InputValidation
import id.bundasiaga.app.data.ReferralMapper
import id.bundasiaga.app.data.Role

@Composable
fun SetupScreen(vm: AppViewModel) {
    val s = vm.app.settings
    val context = LocalContext.current
    val firstRun = !s.isConfigured
    var role by rememberSaveable { mutableStateOf(s.role?.takeIf { it != Role.PUSKESMAS || BuildConfig.SMS_DIRECT }) }
    var name by rememberSaveable { mutableStateOf(s.userName) }
    var lokasi by rememberSaveable { mutableStateOf(s.lokasi) }
    var dusun by rememberSaveable { mutableStateOf(s.dusunId) }
    var dest by rememberSaveable { mutableStateOf(s.destinationNumber) }
    var callback by rememberSaveable { mutableStateOf(s.callbackNumber) }
    var server by rememberSaveable { mutableStateOf(s.serverUrl) }
    var token by rememberSaveable { mutableStateOf(s.serverToken) }
    var showPermissionInfo by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val permissions = buildList {
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.settingsChanged()
        vm.resetTo(homeFor(vm.app))
    }

    val needsDest = role == Role.KADER || role == Role.BIDAN
    val valid = role != null && (role != Role.PUSKESMAS || BuildConfig.SMS_DIRECT) && name.isNotBlank() && lokasi.isNotBlank() && (!needsDest || InputValidation.phone(dest)) && (callback.isBlank() || InputValidation.phone(callback)) && InputValidation.serverUrl(server, BuildConfig.DEBUG)

    Column(Modifier.fillMaxWidth()) {
        BundaTopBar(stringResource(R.string.setup_title), onBack = if (firstRun || vm.stack.size == 1) null else ({ vm.back() }))
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (firstRun) {
                Image(
                    painterResource(R.drawable.logo), contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally),
                )
                Text(stringResource(R.string.setup_welcome), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
            }
            SectionHeading(stringResource(R.string.setup_role))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoleCard(Role.KADER, Icons.Filled.Groups, R.string.role_kader, R.string.role_kader_desc, role) { role = it }
                RoleCard(Role.BIDAN, Icons.Filled.MedicalServices, R.string.role_bidan, R.string.role_bidan_desc, role) { role = it }
                if (BuildConfig.SMS_DIRECT) {
                    RoleCard(Role.PUSKESMAS, Icons.Filled.LocalHospital, R.string.role_puskesmas, R.string.role_puskesmas_desc, role) { role = it }
                } else {
                    Text(stringResource(R.string.play_no_puskesmas), style = MaterialTheme.typography.bodySmall)
                }

            }
            SectionCard(stringResource(R.string.setup_profile_section)) {
                Field(name, { name = it }, R.string.setup_name, R.string.setup_name_hint)
                Field(lokasi, { lokasi = it.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(8) }, R.string.setup_lokasi, R.string.setup_lokasi_hint)

            }
            SectionCard(stringResource(R.string.setup_contact_section)) {
                if (needsDest) Field(dest, { dest = it }, R.string.setup_dest, R.string.setup_dest_hint, KeyboardType.Phone)
                Field(callback, { callback = it }, R.string.setup_callback, R.string.setup_callback_hint, KeyboardType.Phone)
            }
            SecondaryButton(stringResource(R.string.setup_optional_section), onClick = { expanded = !expanded })
            if (expanded) {
                SectionCard(null) {
                    Text(stringResource(R.string.setup_optional_note), style = MaterialTheme.typography.bodySmall)
                    // Every role may fetch signed rule updates; only the puskesmas gateway uploads cases (token).
                    Field(server, { server = it }, R.string.setup_server, if (role == Role.PUSKESMAS) R.string.setup_server_hint else R.string.setup_server_hint_rules, KeyboardType.Uri)
                    if (!InputValidation.serverUrl(server, BuildConfig.DEBUG)) Text("Isi alamat HTTPS tanpa token, parameter, atau fragmen.", color = MaterialTheme.colorScheme.error)
                    if (role == Role.PUSKESMAS) {
                        Field(token, { token = it }, R.string.setup_token, R.string.setup_token_hint, secret = true)
                    }

                }
            }
            BigButton(
                stringResource(R.string.setup_save), enabled = valid,
                onClick = {
                    s.role = role
                    s.userName = name
                    s.lokasi = lokasi
                    s.dusunId = dusun
                    s.destinationNumber = dest
                    s.callbackNumber = callback
                    s.serverUrl = server
                    s.serverToken = token
                    if (BuildConfig.SMS_DIRECT) {
                        showPermissionInfo = true
                    } else {
                        // Build Play: tanpa SMS dan tanpa notifikasi, jadi tidak ada izin yang perlu diminta.
                        vm.settingsChanged()
                        vm.resetTo(homeFor(vm.app))
                    }
                },
            )
            if (!valid) Text(stringResource(R.string.setup_incomplete), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showPermissionInfo) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.perm_title)) },
            text = { Text(stringResource(R.string.perm_body)) },
            confirmButton = {
                TextButton(onClick = { showPermissionInfo = false; launcher.launch(permissions) }) {
                    Text(stringResource(R.string.perm_continue))
                }
            },
        )
    }
}

@Composable
private fun RoleCard(value: Role, icon: ImageVector, title: Int, desc: Int, selected: Role?, onSelect: (Role) -> Unit) {
    val isSel = selected == value
    OutlinedCard(
        onClick = { onSelect(value) },
        modifier = Modifier.fillMaxWidth().semantics { this.role = SemanticsRole.RadioButton; this.selected = isSel },
        colors = CardDefaults.outlinedCardColors(containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) Teal else MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSel, onClick = null, modifier = Modifier.size(48.dp))
            Icon(icon, contentDescription = null, tint = Teal, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, hint: Int, type: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(hint)) },
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) ({
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (visible) R.string.hide_token else R.string.show_token))
            }
        }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}
