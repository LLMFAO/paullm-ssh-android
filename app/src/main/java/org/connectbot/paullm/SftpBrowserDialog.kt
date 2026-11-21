package org.connectbot.paullm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.service.TerminalBridge
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpDirectoryEntry
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale

private const val MAX_PREVIEW_BYTES = 256 * 1024
private const val TRANSFER_CHUNK_BYTES = 64 * 1024
private const val SFTP_PIPELINE_DEPTH = 16

private data class QueuedSftpRead(
    val offset: Long,
    val length: Int,
    val deferred: Deferred<SftpResult<ByteArray?>>,
)

/**
 * Pipelined SFTP download keeping up to [SFTP_PIPELINE_DEPTH] 64 KiB reads in flight simultaneously
 * over cbssh's multiplexed SftpDispatcher while writing chunks strictly in order and filling any
 * server-side short reads cleanly.
 */
private suspend fun pipelinedSftpDownload(
    client: SftpClient,
    handle: SftpFileHandle,
    knownSize: Long,
    rawOut: OutputStream,
    onProgress: (Float) -> Unit,
) = coroutineScope {
    val totalSize = if (knownSize > 0L) {
        knownSize
    } else {
        (client.fstat(handle) as? SftpResult.Success)?.value?.size ?: -1L
    }
    val out = BufferedOutputStream(rawOut, 256 * 1024)
    val queue = ArrayDeque<QueuedSftpRead>(SFTP_PIPELINE_DEPTH)
    var nextRequestOffset = 0L
    var writtenBytes = 0L
    var lastReportedBytes = 0L
    var chunkBytes = TRANSFER_CHUNK_BYTES

    fun enqueueUpToLimit() {
        while (queue.size < SFTP_PIPELINE_DEPTH) {
            if (totalSize > 0L && nextRequestOffset >= totalSize) break
            val reqLen = if (totalSize > 0L) {
                minOf(chunkBytes.toLong(), totalSize - nextRequestOffset).toInt()
            } else {
                chunkBytes
            }
            if (reqLen <= 0) break
            val reqOffset = nextRequestOffset
            nextRequestOffset += reqLen
            queue.addLast(
                QueuedSftpRead(
                    offset = reqOffset,
                    length = reqLen,
                    deferred = async { client.read(handle, reqOffset, reqLen) },
                ),
            )
        }
    }

    try {
        enqueueUpToLimit()
        while (queue.isNotEmpty()) {
            val head = queue.removeFirst()
            val bytes = when (val res = head.deferred.await()) {
                is SftpResult.Success -> res.value
                else -> error("Read failed: ${res.errorText()}")
            }
            if (bytes == null || bytes.isEmpty()) {
                queue.forEach { it.deferred.cancel() }
                queue.clear()
                break
            }
            out.write(bytes)
            writtenBytes += bytes.size

            // If server capped this read shorter than requested before EOF, fill the exact gap
            // synchronously and adapt future chunk size so subsequent reads stay aligned.
            if (bytes.size < head.length) {
                if (bytes.size in 4096 until chunkBytes) {
                    chunkBytes = bytes.size
                }
                var subOffset = head.offset + bytes.size
                val endOffset = head.offset + head.length
                var hitEof = false
                while (subOffset < endOffset) {
                    val rem = (endOffset - subOffset).toInt()
                    val subBytes = when (val subRes = client.read(handle, subOffset, rem)) {
                        is SftpResult.Success -> subRes.value
                        else -> error("Read failed: ${subRes.errorText()}")
                    }
                    if (subBytes == null || subBytes.isEmpty()) {
                        hitEof = true
                        break
                    }
                    out.write(subBytes)
                    subOffset += subBytes.size
                    writtenBytes += subBytes.size
                }
                if (hitEof) {
                    queue.forEach { it.deferred.cancel() }
                    queue.clear()
                    break
                }
            }

            if (totalSize > 0L && (writtenBytes - lastReportedBytes >= 256 * 1024 || writtenBytes >= totalSize)) {
                lastReportedBytes = writtenBytes
                onProgress((writtenBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f))
            }
            enqueueUpToLimit()
        }
        out.flush()
        if (totalSize > 0L) onProgress(1f)
    } catch (t: Throwable) {
        queue.forEach { it.deferred.cancel() }
        throw t
    }
}

private data class RemoteItem(
    val name: String,
    val fullPath: String,
    val isDir: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val permissions: Int?,
)

private data class PendingUpload(
    val uri: Uri,
    val destination: String,
    val name: String,
    val expectedBytes: Long,
    val existingTarget: SftpAttributes?,
)

private fun SftpDirectoryEntry.toRemoteItem(parentDir: String): RemoteItem {
    val perms = attrs.permissions
    val fileType = perms?.and(0xF000) ?: 0
    val isDir = fileType == 0x4000 || longname.startsWith("d")
    val isLink = fileType == 0xA000 || longname.startsWith("l")
    val joined = if (parentDir == "/") "/$filename" else "$parentDir/$filename"
    return RemoteItem(
        name = filename,
        fullPath = joined,
        isDir = isDir,
        isSymlink = isLink,
        size = attrs.size ?: 0L,
        permissions = perms?.and(0x0FFF),
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GiB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MiB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.US, "%.1f KiB", bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatOctal(perms: Int?): String =
    perms?.let { String.format(Locale.US, "%03o", it and 0x01FF) } ?: "---"

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (col >= 0 && cursor.moveToFirst()) {
            val name = cursor.getString(col)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "upload.bin" } ?: "upload.bin"
}

private fun parentRemotePath(path: String): String {
    if (path.isBlank() || path == "/" || path == ".") return "/"
    val trimmed = path.trimEnd('/')
    val parent = trimmed.substringBeforeLast('/')
    return if (parent.isEmpty()) "/" else parent
}

private fun <T> SftpResult<T>.errorText(): String = when (this) {
    is SftpResult.Success -> ""
    is SftpResult.ServerError -> message.ifBlank { statusCode.name }
    is SftpResult.ProtocolError -> message
    is SftpResult.IoError -> cause.message ?: cause.javaClass.simpleName
}

@Composable
private fun SftpPathBar(
    path: String,
    onOpen: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    val segments = if (path.isBlank() || path == "." || path == "/") {
        emptyList()
    } else {
        val names = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        var accumulated = ""
        names.map { name ->
            accumulated += "/$name"
            name to accumulated
        }
    }
    Row(
        modifier = Modifier.horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val atRoot = path == "/"
        Text(
            text = "/",
            modifier = Modifier.clickable(enabled = !atRoot && path != ".") { onOpen("/") },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (atRoot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        segments.forEachIndexed { index, (label, target) ->
            if (index > 0) {
                Text(
                    "/",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val current = target == path.trimEnd('/').ifEmpty { "/" }
            Text(
                text = label,
                modifier = Modifier.clickable(enabled = !current) { onOpen(target) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpBrowserDialog(
    bridge: TerminalBridge,
    startPath: String? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sftpClient by remember { mutableStateOf<SftpClient?>(null) }
    var currentPath by remember { mutableStateOf(".") }
    var homePath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showHidden by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var errorBanner by remember { mutableStateOf<String?>(null) }

    var transferTitle by remember { mutableStateOf<String?>(null) }
    var transferProgress by remember { mutableStateOf<Float?>(null) }
    var activeTransferJob by remember { mutableStateOf<Job?>(null) }

    var selectedItem by remember { mutableStateOf<RemoteItem?>(null) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewBody by remember { mutableStateOf<String?>(null) }
    var showMkdirDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteItem?>(null) }
    var chmodTarget by remember { mutableStateOf<RemoteItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteItem?>(null) }
    var pendingDownloadTarget by remember { mutableStateOf<RemoteItem?>(null) }
    var pendingUpload by remember { mutableStateOf<PendingUpload?>(null) }
    var highlightName by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var scrollGeneration by remember { mutableIntStateOf(0) }
    var pendingScroll by remember { mutableStateOf(0 to 0) }
    val listState = rememberLazyListState()
    val history = remember { mutableStateListOf<String>() }

    fun captureScroll() {
        val path = currentPath
        if (path.isBlank() || path == ".") return
        bridge.rememberSftpScroll(path, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    fun showDirectory(path: String, raw: List<SftpDirectoryEntry>, highlight: String?, scroll: Pair<Int, Int>) {
        val mapped = raw
            .filter { it.filename != "." && it.filename != ".." }
            .map { it.toRemoteItem(path) }
            .sortedWith(compareByDescending<RemoteItem> { it.isDir }.thenBy { it.name.lowercase() })
        currentPath = path
        highlightName = highlight
        entries = mapped
        bridge.rememberSftpDirectory(path)
        val shown = mapped.filter { showHidden || !it.name.startsWith(".") || it.name == highlight }
        val index = when {
            highlight != null -> shown.indexOfFirst { it.name == highlight }.coerceAtLeast(0)
            else -> scroll.first
        }
        pendingScroll = index to scroll.second
        scrollGeneration += 1
        loading = false
        refreshing = false
    }

    suspend fun loadDir(client: SftpClient, targetPath: String, pushHistory: Boolean = true) {
        val leaving = currentPath
        val captured = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        if (pushHistory && leaving.isNotBlank() && leaving != "." && leaving != targetPath) {
            bridge.rememberSftpScroll(leaving, captured.first, captured.second)
            if (history.lastOrNull() != leaving) {
                history.add(leaving)
                if (history.size > 40) history.removeAt(0)
            }
        }
        if (entries.isEmpty()) loading = true else refreshing = true
        errorBanner = null
        statusBanner = null
        val resolved = when (val rp = client.realpath(targetPath)) {
            is SftpResult.Success -> rp.value
            else -> targetPath
        }
        when (val res = client.listdir(resolved)) {
            is SftpResult.Success -> {
                val scroll = if (resolved == leaving) captured else bridge.sftpScrollFor(resolved)
                showDirectory(resolved, res.value, highlight = null, scroll = scroll)
            }

            else -> {
                val parent = parentRemotePath(resolved)
                val name = resolved.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() && parent != resolved }
                val parentRes = if (name != null) client.listdir(parent) else null
                if (name != null && parentRes is SftpResult.Success) {
                    showDirectory(parent, parentRes.value, highlight = name, scroll = 0 to 0)
                    statusBanner = "Opened the folder containing $name"
                } else {
                    errorBanner = "Cannot open $resolved: ${res.errorText()}"
                    loading = false
                    refreshing = false
                }
            }
        }
    }

    fun goBack() {
        val previous = if (history.isNotEmpty()) history.removeAt(history.lastIndex) else null
        val client = sftpClient
        if (previous != null && client != null) {
            scope.launch { loadDir(client, previous, pushHistory = false) }
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(bridge) {
        loading = true
        errorBanner = null
        when (val openRes = bridge.openSftpClient()) {
            is SftpResult.Success -> {
                val client = openRes.value
                sftpClient = client
                val resolvedHome = when (val rp = client.realpath(".")) {
                    is SftpResult.Success -> rp.value
                    else -> "/"
                }
                homePath = resolvedHome
                val requested = startPath?.takeIf { it.isNotBlank() } ?: bridge.sftpDirectory ?: resolvedHome
                loadDir(client, requested, pushHistory = false)
            }

            else -> {
                errorBanner = "SFTP unavailable: ${openRes.errorText()}"
                loading = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            captureScroll()
            activeTransferJob?.cancel()
            runCatching { sftpClient?.close() }
        }
    }

    fun startUpload(request: PendingUpload) {
        val client = sftpClient ?: return
        activeTransferJob = scope.launch {
            transferTitle = "Uploading ${request.name}"
            transferProgress = 0f
            errorBanner = null
            try {
                val message = uploadSftpFileSafely(
                    context = context,
                    client = client,
                    source = request.uri,
                    destination = request.destination,
                    displayName = request.name,
                    expectedBytes = request.expectedBytes,
                    expectedTarget = request.existingTarget,
                    onProgress = { sent ->
                        if (request.expectedBytes > 0) {
                            transferProgress = (sent.toFloat() / request.expectedBytes.toFloat()).coerceIn(0f, 1f)
                        }
                    },
                )
                statusBanner = message
                loadDir(client, currentPath)
            } catch (cancelled: CancellationException) {
                statusBanner = cancelled.message ?: "Upload canceled; the previous remote file is preserved"
            } catch (e: Exception) {
                errorBanner = e.message ?: "Upload failed"
            } finally {
                transferTitle = null
                transferProgress = null
                activeTransferJob = null
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val client = sftpClient ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = queryDisplayName(context, uri)
        if (fileName.isBlank() || fileName == "." || fileName == ".." ||
            fileName.any { it == '/' || it.isISOControl() }
        ) {
            errorBanner = "The selected file has an invalid name"
            return@rememberLauncherForActivityResult
        }
        val remoteDest = if (currentPath == "/") "/$fileName" else "$currentPath/$fileName"
        scope.launch {
            try {
                val size = withContext(Dispatchers.IO) {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }
                val existing = withContext(Dispatchers.IO) { inspectSftpUploadTarget(client, remoteDest) }
                val request = PendingUpload(uri, remoteDest, fileName, size, existing)
                if (existing == null) {
                    startUpload(request)
                } else {
                    pendingUpload = request
                }
            } catch (e: Exception) {
                errorBanner = e.message ?: "Could not prepare upload"
            }
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val client = sftpClient ?: return@rememberLauncherForActivityResult
        val target = pendingDownloadTarget ?: return@rememberLauncherForActivityResult
        pendingDownloadTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        activeTransferJob = scope.launch {
            transferTitle = "Downloading ${target.name}"
            transferProgress = 0f
            errorBanner = null
            try {
                withContext(Dispatchers.IO) {
                    val openRes = client.open(target.fullPath, setOf(SftpOpenFlag.READ))
                    val handle = when (openRes) {
                        is SftpResult.Success -> openRes.value
                        else -> error("Open failed: ${openRes.errorText()}")
                    }
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            pipelinedSftpDownload(
                                client = client,
                                handle = handle,
                                knownSize = target.size,
                                rawOut = out,
                                onProgress = { progress -> transferProgress = progress },
                            )
                        } ?: error("Cannot write destination file")
                    } finally {
                        client.close(handle)
                    }
                }
                statusBanner = "Downloaded ${target.name}"
            } catch (_: CancellationException) {
                statusBanner = "Download canceled"
            } catch (e: Exception) {
                errorBanner = e.message ?: "Download failed"
            } finally {
                transferTitle = null
                transferProgress = null
                activeTransferJob = null
            }
        }
    }

    fun previewFile(item: RemoteItem) {
        val client = sftpClient ?: return
        scope.launch {
            transferTitle = "Reading ${item.name}"
            errorBanner = null
            try {
                val text = withContext(Dispatchers.IO) {
                    val openRes = client.open(item.fullPath, setOf(SftpOpenFlag.READ))
                    val handle = when (openRes) {
                        is SftpResult.Success -> openRes.value
                        else -> error(openRes.errorText())
                    }
                    try {
                        val out = ByteArrayOutputStream()
                        var offset = 0L
                        while (offset < MAX_PREVIEW_BYTES) {
                            val remaining = (MAX_PREVIEW_BYTES - offset).toInt().coerceAtMost(TRANSFER_CHUNK_BYTES)
                            when (val rRes = client.read(handle, offset, remaining)) {
                                is SftpResult.Success -> {
                                    val chunk = rRes.value ?: break
                                    if (chunk.isEmpty()) break
                                    out.write(chunk)
                                    offset += chunk.size
                                }

                                else -> error(rRes.errorText())
                            }
                        }
                        val suffix = if (item.size > MAX_PREVIEW_BYTES) "\n\n--- Truncated at 256 KiB ---" else ""
                        out.toString(Charsets.UTF_8.name()) + suffix
                    } finally {
                        client.close(handle)
                    }
                }
                previewTitle = item.name
                previewBody = text
            } catch (e: Exception) {
                errorBanner = "Preview failed: ${e.message}"
            } finally {
                transferTitle = null
            }
        }
    }

    fun shareFile(item: RemoteItem) {
        val client = sftpClient ?: return
        activeTransferJob = scope.launch {
            transferTitle = "Preparing ${item.name} to share"
            transferProgress = 0f
            errorBanner = null
            try {
                val cached = withContext(Dispatchers.IO) {
                    val shareDir = File(context.cacheDir, "sftp_share").apply { mkdirs() }
                    val dest = File(shareDir, item.name.replace('/', '_'))
                    val openRes = client.open(item.fullPath, setOf(SftpOpenFlag.READ))
                    val handle = when (openRes) {
                        is SftpResult.Success -> openRes.value
                        else -> error(openRes.errorText())
                    }
                    try {
                        dest.outputStream().use { out ->
                            pipelinedSftpDownload(
                                client = client,
                                handle = handle,
                                knownSize = item.size,
                                rawOut = out,
                                onProgress = { progress -> transferProgress = progress },
                            )
                        }
                    } finally {
                        client.close(handle)
                    }
                    dest
                }
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.paullm.fileprovider",
                    cached,
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share ${item.name}"))
            } catch (_: CancellationException) {
                statusBanner = "Share canceled"
            } catch (e: Exception) {
                errorBanner = "Share failed: ${e.message}"
            } finally {
                transferTitle = null
                transferProgress = null
                activeTransferJob = null
            }
        }
    }

    val visibleEntries = remember(entries, showHidden, highlightName) {
        if (showHidden) {
            entries
        } else {
            entries.filter { !it.name.startsWith(".") || it.name == highlightName }
        }
    }

    LaunchedEffect(scrollGeneration, visibleEntries) {
        if (scrollGeneration == 0 || visibleEntries.isEmpty()) return@LaunchedEffect
        val (index, offset) = pendingScroll
        runCatching {
            listState.scrollToItem(index.coerceIn(0, visibleEntries.lastIndex), offset.coerceAtLeast(0))
        }
    }

    Dialog(
        onDismissRequest = { goBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("SFTP Files", style = MaterialTheme.typography.titleMedium)
                            SftpPathBar(
                                path = currentPath,
                                onOpen = { target ->
                                    val client = sftpClient ?: return@SftpPathBar
                                    scope.launch { loadDir(client, target) }
                                },
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close SFTP")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val client = sftpClient ?: return@IconButton
                                scope.launch { loadDir(client, parentRemotePath(currentPath)) }
                            },
                            enabled = sftpClient != null && currentPath != "/" && currentPath != ".",
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Parent folder")
                        }
                        IconButton(
                            onClick = {
                                val client = sftpClient ?: return@IconButton
                                scope.launch { loadDir(client, homePath) }
                            },
                            enabled = sftpClient != null,
                        ) {
                            Icon(Icons.Default.Home, contentDescription = "Home folder")
                        }
                        IconButton(onClick = { showHidden = !showHidden }) {
                            Icon(
                                if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle hidden files",
                            )
                        }
                        IconButton(
                            onClick = { showMkdirDialog = true },
                            enabled = sftpClient != null,
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                        }
                        IconButton(
                            onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                            enabled = sftpClient != null && activeTransferJob == null,
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = "Upload file")
                        }
                        IconButton(
                            onClick = {
                                val client = sftpClient ?: return@IconButton
                                scope.launch { loadDir(client, currentPath) }
                            },
                            enabled = sftpClient != null,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                transferTitle?.let { title ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp))
                            val pct = transferProgress
                            if (pct != null) {
                                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (activeTransferJob != null) {
                            IconButton(onClick = { activeTransferJob?.cancel() }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel transfer")
                            }
                        }
                    }
                    HorizontalDivider()
                }

                errorBanner?.let { msg ->
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                statusBanner?.let { msg ->
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                if (refreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (loading && entries.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (visibleEntries.isEmpty()) {
                    Text(
                        "Empty directory",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(visibleEntries, key = { it.fullPath }) { item ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        item.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                },
                                supportingContent = {
                                    val sizeLabel = if (item.isDir) "Folder" else formatFileSize(item.size)
                                    Text(
                                        "$sizeLabel · ${formatOctal(item.permissions)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = when {
                                            item.isSymlink -> Icons.Default.Link
                                            item.isDir -> Icons.Default.Folder
                                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                        },
                                        contentDescription = null,
                                        tint = if (item.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = { selectedItem = item }) {
                                        Text("Actions")
                                    }
                                },
                                modifier = Modifier
                                    .background(
                                        if (item.name == highlightName) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                    )
                                    .clickable {
                                        if (item.isDir) {
                                            val client = sftpClient ?: return@clickable
                                            scope.launch { loadDir(client, item.fullPath) }
                                        } else {
                                            selectedItem = item
                                        }
                                    },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    selectedItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { selectedItem = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                if (!item.isDir) {
                    ListItem(
                        headlineContent = { Text("Preview as text") },
                        leadingContent = { Icon(Icons.Default.Visibility, contentDescription = null) },
                        modifier = Modifier.clickable {
                            selectedItem = null
                            previewFile(item)
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Download to device") },
                        leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                        modifier = Modifier.clickable {
                            selectedItem = null
                            pendingDownloadTarget = item
                            downloadLauncher.launch(item.name)
                        },
                    )
                    ListItem(
                        headlineContent = { Text("Share") },
                        leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                        modifier = Modifier.clickable {
                            selectedItem = null
                            shareFile(item)
                        },
                    )
                }
                ListItem(
                    headlineContent = { Text("Rename / Move") },
                    leadingContent = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                    modifier = Modifier.clickable {
                        selectedItem = null
                        renameTarget = item
                    },
                )
                ListItem(
                    headlineContent = { Text("Permissions (chmod ${formatOctal(item.permissions)})") },
                    leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                    modifier = Modifier.clickable {
                        selectedItem = null
                        chmodTarget = item
                    },
                )
                ListItem(
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingContent = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable {
                        selectedItem = null
                        deleteTarget = item
                    },
                )
            }
        }
    }

    pendingUpload?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingUpload = null },
            title = { Text("Replace ${request.name}?") },
            text = {
                Text("The existing remote file will be kept until the new upload completes. If the upload fails, the old file will be restored.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUpload = null
                        startUpload(request)
                    },
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUpload = null }) { Text("Cancel") }
            },
        )
    }

    if (previewBody != null) {
        AlertDialog(
            onDismissRequest = {
                previewTitle = null
                previewBody = null
            },
            title = { Text(previewTitle.orEmpty(), fontFamily = FontFamily.Monospace) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        previewBody.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(previewTitle, previewBody))
                        statusBanner = "Copied preview to clipboard"
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        previewTitle = null
                        previewBody = null
                    },
                ) {
                    Text("Close")
                }
            },
        )
    }

    if (showMkdirDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMkdirDialog = false },
            title = { Text("Create folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = folderName.trim()
                        val client = sftpClient
                        showMkdirDialog = false
                        if (clean.isNotEmpty() && !clean.contains('/') && client != null) {
                            val newPath = if (currentPath == "/") "/$clean" else "$currentPath/$clean"
                            scope.launch {
                                when (val res = client.mkdir(newPath)) {
                                    is SftpResult.Success -> {
                                        statusBanner = "Created $clean"
                                        loadDir(client, currentPath)
                                    }

                                    else -> errorBanner = "Create folder failed: ${res.errorText()}"
                                }
                            }
                        }
                    },
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMkdirDialog = false }) { Text("Cancel") }
            },
        )
    }

    renameTarget?.let { item ->
        var newPath by remember(item) { mutableStateOf(item.fullPath) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename or move") },
            text = {
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("Destination path") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dest = newPath.trim()
                        val client = sftpClient
                        renameTarget = null
                        if (dest.isNotEmpty() && dest != item.fullPath && client != null) {
                            scope.launch {
                                when (val res = client.rename(item.fullPath, dest)) {
                                    is SftpResult.Success -> {
                                        statusBanner = "Moved to $dest"
                                        loadDir(client, currentPath)
                                    }

                                    else -> errorBanner = "Rename failed: ${res.errorText()}"
                                }
                            }
                        }
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    chmodTarget?.let { item ->
        var octal by remember(item) { mutableStateOf(formatOctal(item.permissions).replace("-", "644")) }
        AlertDialog(
            onDismissRequest = { chmodTarget = null },
            title = { Text("Edit permissions") },
            text = {
                OutlinedTextField(
                    value = octal,
                    onValueChange = { if (it.length <= 3) octal = it.filter { c -> c in '0'..'7' } },
                    label = { Text("Octal mode (e.g. 600, 644, 755)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = octal.length == 3,
                    onClick = {
                        val mode = octal.toIntOrNull(8)
                        val client = sftpClient
                        chmodTarget = null
                        if (mode != null && client != null) {
                            scope.launch {
                                when (val res = client.setstat(item.fullPath, SftpAttributes(permissions = mode))) {
                                    is SftpResult.Success -> {
                                        statusBanner = "Updated mode to $octal"
                                        loadDir(client, currentPath)
                                    }

                                    else -> errorBanner = "chmod failed: ${res.errorText()}"
                                }
                            }
                        }
                    },
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { chmodTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${item.name}?") },
            text = {
                Text(
                    if (item.isDir) {
                        "This removes the directory only if it is empty."
                    } else {
                        "This permanently deletes ${item.fullPath} on the remote host."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val client = sftpClient
                        deleteTarget = null
                        if (client != null) {
                            scope.launch {
                                val res = if (item.isDir) client.rmdir(item.fullPath) else client.remove(item.fullPath)
                                when (res) {
                                    is SftpResult.Success -> {
                                        statusBanner = "Deleted ${item.name}"
                                        loadDir(client, currentPath)
                                    }

                                    else -> errorBanner = "Delete failed: ${res.errorText()}"
                                }
                            }
                        }
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}
