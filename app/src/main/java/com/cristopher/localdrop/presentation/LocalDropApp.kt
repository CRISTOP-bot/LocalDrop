package com.cristopher.localdrop.presentation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cristopher.localdrop.data.network.localIpv4Address
import com.cristopher.localdrop.domain.model.*
import com.cristopher.localdrop.utils.readableRate
import com.cristopher.localdrop.utils.readableSize
import kotlinx.coroutines.flow.collectLatest
import java.text.DateFormat
import java.util.Date

private enum class Screen { HOME, HISTORY, SETTINGS, QR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalDropApp(vm: MainViewModel, onScanQr: () -> Unit) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var cancelConfirmation by remember { mutableStateOf(false) }
    var rejectSession by remember { mutableStateOf<String?>(null) }
    var folderSession by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val files by vm.sharedFiles.collectAsState()
    val devices by vm.devices.collectAsState()
    val history by vm.history.collectAsState()
    val active by vm.activeTransfer.collectAsState()
    val incoming by vm.incoming.collectAsState()
    val settings by vm.settings.collectAsState()
    val identity by vm.localIdentity.collectAsState()
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.filesFromUris(it) }
    val folderChooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val session = folderSession
        folderSession = null
        if (session != null) {
            uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }; vm.acceptIncoming(session, it) }
        }
    }
    LaunchedEffect(Unit) { vm.message.collectLatest { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (screen == Screen.HOME) "LocalDrop" else screenTitle(screen)) }, actions = { if (screen == Screen.HOME) IconButton(onClick = { screen = Screen.QR }) { Icon(Icons.Default.QrCode2, "Código QR") } }) },
        bottomBar = { NavigationBar {
            NavigationBarItem(selected = screen == Screen.HOME, onClick = { screen = Screen.HOME }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Inicio") })
            NavigationBarItem(selected = screen == Screen.HISTORY, onClick = { screen = Screen.HISTORY }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Historial") })
            NavigationBarItem(selected = screen == Screen.SETTINGS, onClick = { screen = Screen.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ajustes") })
        } },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(files, devices, active, settings, context, onPick = { chooser.launch(arrayOf("*/*")) }, onSend = vm::sendTo, onRefresh = vm::refreshDevices, onRevoke = vm::revokeDevice, onCancel = { cancelConfirmation = true }, onQr = { screen = Screen.QR })
                Screen.HISTORY -> HistoryScreen(history, vm::deleteHistory)
                Screen.SETTINGS -> SettingsScreen(settings, vm::saveSettings)
                Screen.QR -> QrScreen(settings, context, identity, onScanQr)
            }
            if (incoming.isNotEmpty()) IncomingRequestsPanel(incoming, onAccept = { session -> folderSession = session; folderChooser.launch(null) }, onReject = { rejectSession = it })
            if (cancelConfirmation) AlertDialog(onDismissRequest = { cancelConfirmation = false }, title = { Text("¿Cancelar transferencia?") }, text = { Text("El archivo quedará incompleto y aparecerá como cancelado en el historial.") }, confirmButton = { TextButton(onClick = { vm.cancel(); cancelConfirmation = false }) { Text("Cancelar transferencia") } }, dismissButton = { TextButton(onClick = { cancelConfirmation = false }) { Text("Continuar") } })
            rejectSession?.let { session -> AlertDialog(onDismissRequest = { rejectSession = null }, title = { Text("¿Rechazar envío?") }, text = { Text("El dispositivo origen recibirá una respuesta de rechazo.") }, confirmButton = { TextButton(onClick = { vm.rejectIncoming(session); rejectSession = null }) { Text("Rechazar") } }, dismissButton = { TextButton(onClick = { rejectSession = null }) { Text("Volver") } }) }
        }
    }
}

private fun screenTitle(screen: Screen) = when (screen) { Screen.HOME -> "LocalDrop"; Screen.HISTORY -> "Historial"; Screen.SETTINGS -> "Ajustes"; Screen.QR -> "Conexión QR" }

@Composable
private fun HomeScreen(files: List<TransferFile>, devices: List<LocalDevice>, active: TransferProgress?, settings: LocalSettings, context: android.content.Context, onPick: () -> Unit, onSend: (LocalDevice) -> Unit, onRefresh: () -> Unit, onRevoke: (String) -> Unit, onCancel: () -> Unit, onQr: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { NetworkCard(settings, context) }
        item { Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(8.dp)); Text(if (files.isEmpty()) "Enviar archivos" else "Cambiar archivos") } }
        if (files.isNotEmpty()) { item { Text("Archivos seleccionados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }; items(files) { file -> FileRow(file.name, file.size, file.mimeType) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Dispositivos en la red", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Buscar de nuevo") } } }
        if (devices.isEmpty()) item { EmptyState("No hay dispositivos visibles", "Pulsa actualizar o abre LocalDrop en otro dispositivo conectado a la misma Wi‑Fi.") }
        items(devices, key = { it.id }) { device -> DeviceCard(device, files.isNotEmpty(), onSend = { onSend(device) }, onRevoke = { onRevoke(device.id) }) }
        item { OutlinedButton(onClick = onQr, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(8.dp)); Text("Conectar con código QR") } }
        active?.let { progress -> item { TransferCard(progress, onCancel) } }
    }
}

@Composable private fun NetworkCard(settings: LocalSettings, context: android.content.Context) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column { Text("Red local", fontWeight = FontWeight.Bold); Text("${settings.deviceName} • ${localIpv4Address(context) ?: "sin conexión Wi‑Fi"}:${settings.port}", style = MaterialTheme.typography.bodySmall) } } } }
@Composable private fun DeviceCard(device: LocalDevice, enabled: Boolean, onSend: () -> Unit, onRevoke: () -> Unit) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (device.type == DeviceType.TABLET) Icons.Default.Tablet else Icons.Default.PhoneAndroid, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(device.name, fontWeight = FontWeight.Bold); Text("${device.host}:${device.port} • ${if (device.paired) "Emparejado" else "Descubierto"}", style = MaterialTheme.typography.bodySmall) }; if (device.paired) TextButton(onClick = onRevoke) { Text("Revocar") }; Button(enabled = enabled, onClick = onSend) { Text("Enviar") } } } }
@Composable private fun FileRow(name: String, size: Long, mime: String) { ListItem(headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${size.readableSize()} • $mime") }, leadingContent = { Icon(Icons.Default.InsertDriveFile, null) }) }
@Composable private fun EmptyState(title: String, message: String) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.DevicesOther, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(8.dp)); Text(title, fontWeight = FontWeight.Bold); Text(message, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun TransferCard(progress: TransferProgress, onCancel: () -> Unit) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (progress.state == TransferState.RUNNING) "Transferencia en curso" else "Transferencia ${progress.state.name.lowercase()}", fontWeight = FontWeight.Bold); Text(progress.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis); LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth()); Text("${(progress.fraction * 100).toInt()}% • ${progress.transferred.readableSize()} de ${progress.total.readableSize()} • ${progress.bytesPerSecond.readableRate()}"); progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (progress.state == TransferState.RUNNING) TextButton(onClick = onCancel) { Text("Cancelar") } } } }

@Composable private fun IncomingRequestsPanel(requests: List<IncomingRequest>, onAccept: (String) -> Unit, onReject: (String) -> Unit) { Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) { requests.forEach { request -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Solicitud de ${request.device.name}", fontWeight = FontWeight.Bold); Text("${request.device.host} • fingerprint ${request.device.fingerprint?.take(16) ?: "no verificado"}", style = MaterialTheme.typography.bodySmall); request.files.forEach { Text("• ${it.name} — ${it.size.readableSize()}", maxLines = 1, overflow = TextOverflow.Ellipsis) }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { onReject(request.sessionId) }) { Text("Rechazar") }; Button(onClick = { onAccept(request.sessionId) }) { Text("Aceptar") } } } } } } }

@Composable private fun HistoryScreen(history: List<TransferHistory>, onDelete: (Long) -> Unit) { if (history.isEmpty()) EmptyState("Aún no hay transferencias", "Cuando envíes o recibas archivos aparecerán aquí.") else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(history, key = { it.id }) { item -> Card(Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${item.deviceName} • ${item.size.readableSize()} • ${DateFormat.getDateTimeInstance().format(Date(item.timestamp))}${item.error?.let { " • $it" } ?: ""}") }, leadingContent = { Icon(if (item.direction == TransferDirection.SENT) Icons.Default.FileUpload else Icons.Default.FileDownload, null) }, trailingContent = { IconButton(onClick = { onDelete(item.id) }) { Icon(Icons.Default.DeleteOutline, "Borrar") } }) } } } }

@Composable private fun SettingsScreen(settings: LocalSettings, onSave: (LocalSettings) -> Unit) { val context = LocalContext.current; var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }; var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }; var confirm by remember(settings.confirmIncoming) { mutableStateOf(settings.confirmIncoming) }; var auto by remember(settings.autoDiscovery) { mutableStateOf(settings.autoDiscovery) }; var verify by remember(settings.verifyIntegrity) { mutableStateOf(settings.verifyIntegrity) }; var folder by remember(settings.defaultFolder) { mutableStateOf(settings.defaultFolder) }; val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }; folder = uri; onSave(LocalSettings(name.ifBlank { "Android" }, port.toIntOrNull()?.coerceIn(0, 65535) ?: 0, folder, auto, confirm, verify)) } }; Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nombre del dispositivo") }, singleLine = true); OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Puerto local (0 = automático)") }, singleLine = true); OutlinedButton(onClick = { folderPicker.launch(null) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text(if (folder == null) "Elegir carpeta de recepción" else "Cambiar carpeta de recepción") }; SettingSwitch("Confirmar transferencias entrantes", confirm) { confirm = it }; SettingSwitch("Descubrimiento automático", auto) { auto = it }; SettingSwitch("Verificar SHA-256", verify) { verify = it }; Button(onClick = { onSave(LocalSettings(name.ifBlank { "Android" }, port.toIntOrNull()?.coerceIn(0, 65535) ?: 0, folder, auto, confirm, verify)) }, Modifier.fillMaxWidth()) { Text("Guardar") }; Text("Las transferencias solo usan HTTP dentro de la red local. No se usa nube ni servidor externo.", style = MaterialTheme.typography.bodySmall) } }
@Composable private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onChecked) } }

@Composable private fun QrScreen(settings: LocalSettings, context: android.content.Context, identity: LocalIdentity, onScan: () -> Unit) { val host = localIpv4Address(context).orEmpty(); val payload = "localdrop://connect?v=1&id=${Uri.encode(identity.deviceId)}&host=${Uri.encode(host)}&port=${settings.port}&name=${Uri.encode(settings.deviceName)}&pk=${Uri.encode(identity.publicKey)}&fp=${identity.fingerprint}"; Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("Conecta otro dispositivo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Escanea este código desde LocalDrop. Incluye la identidad pública para autenticar el emparejamiento.", style = MaterialTheme.typography.bodyMedium); if (host.isNotEmpty() && settings.port > 0 && identity.publicKey.isNotEmpty()) QrCode(payload) else EmptyState("Red local no disponible", "Conéctate a Wi‑Fi para generar un QR válido."); Text("Fingerprint: ${identity.fingerprint.take(20)}…", style = MaterialTheme.typography.labelSmall); Text("$host:${settings.port}", style = MaterialTheme.typography.labelLarge); OutlinedButton(onClick = onScan) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Escanear QR") } } }
