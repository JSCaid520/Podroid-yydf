/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Lists container backup archives written by the guest `podroid-backup` tool
 * into Downloads/Podroid/backups when sharing is enabled, and moves the whole
 * VM disk image in and out of the app for a full-device migration.
 */
package com.excp.podroid.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.excp.podroid.util.ShellQuote
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ContainerBackupFile(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val absolutePath: String,
)

@Singleton
class ContainerBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val BACKUP_SUBDIR = "Podroid/backups"

        /** The persistent ext4 overlay both engines boot from. */
        const val STORAGE_IMAGE_NAME = "storage.img"

        /** ext4 superblock magic at offset 0x438, little endian (0xEF53). */
        private const val EXT4_MAGIC_OFFSET = 0x438
        private const val EXT4_MAGIC_LOW = 0x53.toByte()
        private const val EXT4_MAGIC_HIGH = 0xEF.toByte()

        private const val COPY_BUFFER = 4 * 1024 * 1024
        private const val IMPORT_MARGIN_BYTES = 256L * 1024 * 1024

        internal fun ensureBackupDirectory(dir: File): Boolean = runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            // Recheck even if mkdirs returned false: another caller may have
            // created the directory. An existing regular file is not usable.
            dir.isDirectory
        }.getOrDefault(false)
    }

    fun backupDirectory(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, BACKUP_SUBDIR)
    }

    fun guestBackupPathLabel(downloadsSharingEnabled: Boolean = isDownloadsReachable()): String =
        if (downloadsSharingEnabled) "/mnt/downloads/Podroid/backups"
        else "/var/backups/podroid"

    fun isDownloadsReachable(): Boolean {
        val dir = backupDirectory()
        // Create Downloads/Podroid/backups if absent: the app holds All-files
        // access whenever Downloads sharing is on, so a successful mkdirs both
        // confirms reachability and gives the guest an existing target under the
        // 9p/virtio-9p mount. Nothing else creates this dir, so without it every
        // backup would fall back to the guest-internal path and never surface in
        // Downloads.
        return ensureBackupDirectory(dir)
    }

    fun listBackupFiles(): List<ContainerBackupFile> {
        val dir = backupDirectory()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".tar") || it.name.endsWith(".tar.gz")) }
            ?.map { f ->
                ContainerBackupFile(
                    name = f.name,
                    sizeBytes = f.length(),
                    lastModifiedMs = f.lastModified(),
                    absolutePath = f.absolutePath,
                )
            }
            ?.sortedByDescending { it.lastModifiedMs }
            ?: emptyList()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    fun exportCommand(containerName: String): String {
        val q = ShellQuote.quote(containerName.trim())
        val fb = fallbackExportCommand(containerName)
        return "if command -v podroid-backup >/dev/null 2>&1; then podroid-backup export $q; else $fb; fi"
    }

    fun saveImageCommand(imageRef: String): String {
        val q = ShellQuote.quote(imageRef.trim())
        val root = guestBackupPathLabel()
        return "if command -v podroid-backup >/dev/null 2>&1; then podroid-backup save $q; else mkdir -p $root && podman save $q -o $root/\$(echo $q | tr -cd 'A-Za-z0-9._-')-\$(date +%Y%m%d-%H%M%S).tar; fi"
    }

    fun listCommand(): String = "podroid-backup list"

    fun fallbackExportCommand(containerName: String): String {
        val q = ShellQuote.quote(containerName.trim())
        val root = guestBackupPathLabel()
        return "mkdir -p $root && podman export $q -o $root/\$(echo $q | tr -cd 'A-Za-z0-9._-')-\$(date +%Y%m%d-%H%M%S).tar"
    }

    // --- whole-VM disk image (plan A: migrate everything to a new phone) ----

    fun storageImageFile(): File = File(context.filesDir, STORAGE_IMAGE_NAME)

    fun storageImageBytes(): Long = runCatching {
        storageImageFile().takeIf { it.isFile }?.length() ?: 0L
    }.getOrDefault(0L)

    fun freeSpaceBytes(): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)

    fun defaultDiskExportName(): String =
        "podroid-storage-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.img"

    /** Human-readable name of a picked document, for the confirmation dialog. */
    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    fun documentSize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * Copies the VM disk image to [uri]. The caller must stop the VM first: a
     * running guest keeps writing to the ext4 overlay, so any copy taken while
     * it runs is inconsistent by definition.
     */
    fun exportDiskTo(uri: Uri, onProgress: (done: Long, total: Long) -> Unit): Result<Long> = runCatching {
        val source = storageImageFile()
        if (!source.isFile) throw IOException("storage.img is missing")
        val total = source.length()
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("cannot open the destination")
        out.use { sink ->
            source.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    done += read
                    if (done - lastReport >= 64L * 1024 * 1024) {
                        lastReport = done
                        onProgress(done, total)
                    }
                }
                sink.flush()
                onProgress(done, total)
                done
            }
        }
    }

    /**
     * Replaces the VM disk image with [uri] (plan A restore). The previous image
     * is only renamed to `.prev` first and deleted after the copy succeeded, so
     * a failed import leaves the old image in place.
     */
    fun importDiskFrom(uri: Uri, onProgress: (done: Long, total: Long) -> Unit): Result<Long> = runCatching {
        val declared = documentSize(uri)
        if (declared > 0 && declared + IMPORT_MARGIN_BYTES > freeSpaceBytes()) {
            throw IOException("not enough free space for ${formatSize(declared)}")
        }
        verifyExt4(uri)

        val target = storageImageFile()
        val previous = File(target.parentFile, "$STORAGE_IMAGE_NAME.prev")
        if (previous.exists()) previous.delete()
        val hadPrevious = target.isFile && target.renameTo(previous)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open the picked file")
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        if (done - lastReport >= 64L * 1024 * 1024) {
                            lastReport = done
                            onProgress(done, declared.coerceAtLeast(done))
                        }
                    }
                    sink.flush()
                    onProgress(done, declared.coerceAtLeast(done))
                    if (done < 1024L * 1024) throw IOException("the picked file is too small")
                }
            }
        } catch (t: Throwable) {
            target.delete()
            if (hadPrevious) previous.renameTo(target)
            throw t
        }
        previous.delete()
        target.length()
    }

    /** Cheap guard against importing something that is not an ext4 image. */
    private fun verifyExt4(uri: Uri) {
        val head = ByteArray(EXT4_MAGIC_OFFSET + 2)
        context.contentResolver.openInputStream(uri)?.use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < head.size) throw IOException("the picked file is too small")
        } ?: throw IOException("cannot open the picked file")
        val low = head[EXT4_MAGIC_OFFSET]
        val high = head[EXT4_MAGIC_OFFSET + 1]
        if (low != EXT4_MAGIC_LOW || high != EXT4_MAGIC_HIGH) {
            throw IOException("not an ext4 disk image")
        }
    }

    /** Restore command for one archive (plan B), using the guest-side path. */
    fun restoreCommand(archiveName: String): String {
        val dir = guestBackupPathLabel()
        return "podroid-backup import ${ShellQuote.quote("$dir/$archiveName")}"
    }
}
