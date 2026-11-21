/* paullmSSH additions are licensed under GPL-3.0-or-later. */
package org.connectbot.paullm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import java.io.IOException
import java.util.UUID

/**
 * Uploads through a unique remote staging file, then commits with rename. Existing regular files
 * are moved aside until the replacement is committed so cancellation or a failed rename can roll
 * back without losing the previous contents.
 */
internal suspend fun uploadSftpFileSafely(
    context: Context,
    client: SftpClient,
    source: Uri,
    destination: String,
    displayName: String,
    expectedBytes: Long,
    expectedTarget: SftpAttributes?,
    onProgress: (Long) -> Unit,
): String = withContext(Dispatchers.IO) {
    var temporaryPath: String? = null
    var backupPath: String? = null
    var oldFileMoved = false
    var committed = false
    var backupMoveAttempted = false
    var commitAttempted = false
    var uncertainRemoteMutation = false

    suspend fun requireSuccess(result: SftpResult<*>, operation: String) {
        if (result !is SftpResult.Success) throw IOException("$operation failed: ${result.errorText()}")
    }

    suspend fun currentTarget(): SftpAttributes? = when (val result = client.lstat(destination)) {
        is SftpResult.Success -> result.value.also { attrs ->
            val kind = (attrs.permissions ?: 0) and 0xF000
            if (kind == 0x4000 || kind == 0xA000) {
                throw IOException("A folder or symbolic link uses the upload destination")
            }
        }
        is SftpResult.ServerError -> if (result.statusCode == SftpStatusCode.NO_SUCH_FILE) null
        else throw IOException("Cannot inspect upload destination: ${result.errorText()}")
        else -> throw IOException("Cannot inspect upload destination: ${result.errorText()}")
    }

    suspend fun removeIfPresent(path: String) {
        when (val result = client.remove(path)) {
            is SftpResult.Success -> Unit
            is SftpResult.ServerError -> if (result.statusCode != SftpStatusCode.NO_SUCH_FILE) {
                throw IOException("Remove failed: ${result.errorText()}")
            }
            else -> throw IOException("Remove failed: ${result.errorText()}")
        }
    }

    try {
        val before = currentTarget()
        if (!sameTarget(before, expectedTarget)) {
            throw IOException("The remote destination changed while the upload was being prepared")
        }

        val parent = destination.substringBeforeLast('/', "").ifEmpty { "/" }
        temporaryPath = joinRemote(parent, ".paullmssh-upload-${UUID.randomUUID()}.part")
        val openResult = client.open(
            temporaryPath,
            setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.EXCLUDE),
        )
        val handle = when (openResult) {
            is SftpResult.Success -> openResult.value
            else -> throw IOException("Could not create remote staging file: ${openResult.errorText()}")
        }

        var written = 0L
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("Unable to read selected file")
            input.use { stream ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    requireSuccess(client.write(handle, written, buffer.copyOf(count)), "Write")
                    written += count
                    onProgress(written)
                }
            }
            if (expectedBytes >= 0 && written != expectedBytes) {
                throw IOException("The selected file changed or could not be fully read")
            }
            val stat = client.fstat(handle)
            if (stat !is SftpResult.Success || stat.value.size != written.toLong()) {
                throw IOException("Remote staging file size could not be verified")
            }
            expectedTarget?.permissions?.let { mode ->
                requireSuccess(client.fsetstat(handle, SftpAttributes(permissions = mode)), "Preserve file permissions")
            }
        } finally {
            requireSuccess(client.close(handle), "Close remote staging file")
        }

        val now = currentTarget()
        if (!sameTarget(now, expectedTarget)) {
            throw IOException("The remote destination changed during upload; staged data was not committed")
        }
        if (now != null) {
            backupPath = joinRemote(parent, ".paullmssh-backup-${UUID.randomUUID()}.bak")
            backupMoveAttempted = true
            when (val move = client.rename(destination, backupPath!!)) {
                is SftpResult.Success -> {
                    oldFileMoved = true
                    backupMoveAttempted = false
                }
                is SftpResult.ServerError -> {
                    backupMoveAttempted = false
                    throw IOException("Could not preserve previous file: ${move.errorText()}")
                }
                else -> {
                    uncertainRemoteMutation = true
                    throw IOException("The server response was lost while preserving the previous file")
                }
            }
        }

        commitAttempted = true
        when (val commitResult = client.rename(temporaryPath!!, destination)) {
            is SftpResult.Success -> {
                committed = true
                commitAttempted = false
            }
            is SftpResult.ServerError -> {
                commitAttempted = false
                throw IOException("Could not commit uploaded file: ${commitResult.errorText()}")
            }
            else -> {
                uncertainRemoteMutation = true
                throw IOException("The server response was lost while committing the upload")
            }
        }
        temporaryPath = null

        if (oldFileMoved) {
            try {
                removeIfPresent(backupPath!!)
                backupPath = null
            } catch (_: Exception) {
                return@withContext "Uploaded $displayName. Previous version remains at ${backupPath}"
            }
        }
        "Uploaded $displayName"
    } catch (cancelled: CancellationException) {
        if (commitAttempted || backupMoveAttempted) uncertainRemoteMutation = true
        if (!uncertainRemoteMutation) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!committed && oldFileMoved && backupPath != null) {
                    runCatching { client.rename(backupPath!!, destination) }
                }
                if (!committed && temporaryPath != null) runCatching { removeIfPresent(temporaryPath!!) }
            }
        }
        val message = if (uncertainRemoteMutation) {
            "Upload canceled during remote commit; check $destination and preserve any backup at $backupPath"
        } else "Upload canceled; the previous remote file is preserved"
        throw CancellationException(message, cancelled)
    } catch (failure: Exception) {
        var recoveryNote = ""
        withContext(NonCancellable + Dispatchers.IO) {
            if (uncertainRemoteMutation) {
                recoveryNote += "; remote commit state is uncertain, check $destination"
                if (backupPath != null) recoveryNote += "; previous file may be preserved at $backupPath"
                if (temporaryPath != null) recoveryNote += "; staging file may remain at $temporaryPath"
            } else if (!committed && oldFileMoved && backupPath != null) {
                val restore = runCatching { client.rename(backupPath!!, destination) }.getOrNull()
                if (restore is SftpResult.Success) {
                    oldFileMoved = false
                    backupPath = null
                } else {
                    recoveryNote += "; previous file is preserved at $backupPath"
                }
            }
            if (!committed && !uncertainRemoteMutation && temporaryPath != null) {
                val cleanup = runCatching { removeIfPresent(temporaryPath!!) }
                if (cleanup.isFailure) recoveryNote += "; incomplete upload may remain at $temporaryPath"
            }
        }
        throw IOException((failure.message ?: "Upload failed") + recoveryNote, failure)
    }
}

internal suspend fun inspectSftpUploadTarget(
    client: SftpClient,
    destination: String,
): SftpAttributes? = when (val result = client.lstat(destination)) {
    is SftpResult.Success -> result.value.also { attrs ->
        val kind = (attrs.permissions ?: 0) and 0xF000
        if (kind == 0x4000 || kind == 0xA000) {
            throw IOException("A folder or symbolic link uses the upload destination")
        }
    }
    is SftpResult.ServerError -> if (result.statusCode == SftpStatusCode.NO_SUCH_FILE) null
    else throw IOException("Cannot inspect upload destination: ${result.errorText()}")
    else -> throw IOException("Cannot inspect upload destination: ${result.errorText()}")
}

private fun sameTarget(current: SftpAttributes?, expected: SftpAttributes?): Boolean = when {
    current == null || expected == null -> current == null && expected == null
    else -> current.size == expected.size && current.mtime == expected.mtime &&
        current.permissions == expected.permissions
}

private fun joinRemote(parent: String, child: String): String =
    if (parent == "/") "/$child" else "$parent/$child"

private fun <T> SftpResult<T>.errorText(): String = when (this) {
    is SftpResult.Success -> ""
    is SftpResult.ServerError -> message.ifBlank { statusCode.name }
    is SftpResult.ProtocolError -> message
    is SftpResult.IoError -> cause.message ?: cause.javaClass.simpleName
}
