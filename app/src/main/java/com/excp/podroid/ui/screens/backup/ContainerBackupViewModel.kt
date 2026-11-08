package com.excp.podroid.ui.screens.backup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.ContainerBackupFile
import com.excp.podroid.data.repository.ContainerBackupRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Progress of a whole-disk copy. Reuses one type for both directions so the UI
 * only needs a single progress block.
 */
data class DiskTransfer(
    val importing: Boolean,
    val doneBytes: Long,
    val totalBytes: Long,
    val active: Boolean = true,
    val failed: Boolean = false,
    val finished: Boolean = false,
) {
    val percent: Int
        get() = when {
            totalBytes <= 0L -> 0
            else -> ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        }
}

/** A disk image the user picked, waiting for the overwrite confirmation. */
data class PendingImport(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
)

data class ContainerBackupUiState(
    val vmRunning: Boolean = false,
    val storageAccessEnabled: Boolean = false,
    val guestPath: String = "/var/backups/podroid",
    val backupFiles: List<ContainerBackupFile> = emptyList(),
    val backupListError: Boolean = false,
    val containerName: String = "",
    val imageRef: String = "",
    val diskImageLabel: String = "",
    val transfer: DiskTransfer? = null,
    val pendingImport: PendingImport? = null,
)

@HiltViewModel
class ContainerBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ContainerBackupRepository,
    private val settingsRepository: SettingsRepository,
    private val engine: VmEngine,
) : ViewModel() {

    private val _containerName = MutableStateFlow("")
    private val _imageRef = MutableStateFlow("")
    private val _backupFiles = MutableStateFlow<List<ContainerBackupFile>>(emptyList())
    private val _backupListError = MutableStateFlow(false)
    private val _diskImageLabel = MutableStateFlow("")
    private val _transfer = MutableStateFlow<DiskTransfer?>(null)
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)

    val uiState: StateFlow<ContainerBackupUiState> = combine(
        combine(
            engine.state,
            settingsRepository.storageAccessEnabled,
            _containerName,
            _imageRef,
            _backupFiles,
        ) { vmState, storageAccess, container, image, files ->
            ContainerBackupUiState(
                vmRunning = vmState is VmState.Running,
                storageAccessEnabled = storageAccess,
                guestPath = repository.guestBackupPathLabel(storageAccess),
                backupFiles = files,
                containerName = container,
                imageRef = image,
            )
        },
        _backupListError,
        _diskImageLabel,
        _transfer,
        _pendingImport,
    ) { state, listError, diskLabel, transfer, pending ->
        state.copy(
            backupListError = listError,
            diskImageLabel = diskLabel,
            transfer = transfer,
            pendingImport = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContainerBackupUiState())

    init {
        refresh()
        refreshDiskImage()
    }

    fun refresh() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.listBackupFiles() }
            }
            result.onSuccess { files ->
                _backupFiles.value = files
                _backupListError.value = false
            }.onFailure {
                _backupListError.value = true
            }
            refreshDiskImage()
        }
    }

    fun refreshDiskImage() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { repository.storageImageBytes() }
            _diskImageLabel.value = if (bytes > 0) repository.formatSize(bytes) else ""
        }
    }

    fun setContainerName(value: String) {
        _containerName.value = value
    }

    fun setImageRef(value: String) {
        _imageRef.value = value
    }

    fun copyExportCommand(): Boolean {
        val name = _containerName.value.trim()
        if (name.isEmpty()) return false
        copyToClipboard(repository.exportCommand(name))
        return true
    }

    fun copySaveCommand(): Boolean {
        val ref = _imageRef.value.trim()
        if (ref.isEmpty()) return false
        copyToClipboard(repository.saveImageCommand(ref))
        return true
    }

    fun copyListCommand() {
        copyToClipboard(repository.listCommand())
    }

    fun copyAllCommand() {
        copyToClipboard("podroid-backup all")
    }

    /** Copies `podroid-backup import '<archive>'` for one archive (plan B). */
    fun copyRestoreCommand(archiveName: String): Boolean {
        if (archiveName.isBlank()) return false
        copyToClipboard(repository.restoreCommand(archiveName))
        return true
    }

    fun defaultDiskExportName(): String = repository.defaultDiskExportName()

    // --- plan A: whole VM disk image ---------------------------------------

    fun exportDisk(uri: Uri) {
        if (_transfer.value?.active == true) return
        _transfer.value = DiskTransfer(importing = false, doneBytes = 0, totalBytes = 0)
        viewModelScope.launch {
            // Seed the total up front so the progress bar is meaningful from the
            // first frame; the repository only reports every 64 MB.
            val total = withContext(Dispatchers.IO) { repository.storageImageBytes() }
            _transfer.value = DiskTransfer(importing = false, doneBytes = 0, totalBytes = total)
            val result = withContext(Dispatchers.IO) {
                repository.exportDiskTo(uri) { done, reported ->
                    _transfer.value = DiskTransfer(
                        importing = false,
                        doneBytes = done,
                        totalBytes = reported.coerceAtLeast(total),
                    )
                }
            }
            _transfer.value = result.fold(
                onSuccess = { bytes ->
                    DiskTransfer(false, bytes, bytes, active = false, finished = true)
                },
                onFailure = {
                    DiskTransfer(false, 0, 0, active = false, failed = true)
                },
            )
        }
    }

    /** Stores the picked file so the screen can ask before overwriting. */
    fun stageImport(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                repository.displayName(uri) ?: uri.lastPathSegment ?: "storage.img"
            }
            val size = withContext(Dispatchers.IO) { repository.documentSize(uri) }
            _pendingImport.value = PendingImport(uri, name, size)
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    fun confirmImport() {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        if (_transfer.value?.active == true) return
        _transfer.value = DiskTransfer(importing = true, doneBytes = 0, totalBytes = pending.sizeBytes)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.importDiskFrom(pending.uri) { done, total ->
                    _transfer.value = DiskTransfer(importing = true, doneBytes = done, totalBytes = total)
                }
            }
            _transfer.value = result.fold(
                onSuccess = { bytes ->
                    DiskTransfer(true, bytes, bytes, active = false, finished = true)
                },
                onFailure = {
                    DiskTransfer(true, 0, 0, active = false, failed = true)
                },
            )
            refreshDiskImage()
        }
    }

    fun dismissTransfer() {
        _transfer.value = null
    }

    fun formatSize(bytes: Long): String = repository.formatSize(bytes)

    fun formatDate(ms: Long): String = repository.formatDate(ms)

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("podroid-backup", text))
    }
}
