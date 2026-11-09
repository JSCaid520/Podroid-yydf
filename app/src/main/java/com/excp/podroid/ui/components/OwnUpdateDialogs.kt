package com.excp.podroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.excp.podroid.BuildConfig
import com.excp.podroid.R
import com.excp.podroid.data.repository.ApkRelease
import com.excp.podroid.ui.theme.PodroidTokens
import com.excp.podroid.util.ApkInstaller
import java.io.File
import java.util.Locale

/**
 * State machine for "is there a newer build of *our* fork?".
 *
 * Shared by the manual check in Settings and the launch prompt on Home so both
 * entry points behave identically. Deliberately says nothing about upstream:
 * the upstream feed is not installable from this build.
 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState

    /** Nothing newer published (also covers a failed check — see checkOwnUpdate). */
    data object UpToDate : UpdateUiState

    data class Available(val release: ApkRelease) : UpdateUiState
    data class Downloading(val percent: Int, val release: ApkRelease) : UpdateUiState
    data class Ready(val apk: File, val release: ApkRelease) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * Process-wide latch for the launch prompt.
 *
 * HomeViewModel is recreated whenever Home re-enters composition, so a
 * ViewModel-scoped flag would re-run the check (and pop the dialog again) on
 * every return to Home. One check per app start is the intended cadence.
 */
internal object OwnUpdateGate {
    @Volatile
    var checked: Boolean = false
}

/**
 * One dialog driven by [state].
 *
 * [UpdateUiState.Downloading] deliberately cannot be dismissed: the transfer
 * runs in the caller's viewModelScope, so closing the dialog would leave the
 * user with no progress feedback at all.
 */
@Composable
fun OwnUpdateDialogs(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: (ApkRelease) -> Unit,
    onInstalled: () -> Unit,
    onInstallBlocked: () -> Unit,
) {
    val context = LocalContext.current
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_available_title, state.release.version)) },
            text = {
                Text(
                    stringResource(
                        R.string.update_available_body,
                        BuildConfig.VERSION_NAME,
                        formatDownloadSize(state.release.sizeBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { onDownload(state.release) }) {
                    Text(stringResource(R.string.update_download))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading_title)) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    Text(
                        text = stringResource(R.string.update_downloading, state.percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )

        is UpdateUiState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_ready_title)) },
            text = { Text(stringResource(R.string.update_ready_body, state.release.version)) },
            confirmButton = {
                TextButton(onClick = {
                    val started = ApkInstaller.install(context, state.apk)
                    onInstalled()
                    // Not started means the system's "install unknown apps"
                    // screen was opened instead; the APK stays on disk, so a
                    // retry needs no re-download.
                    if (!started) onInstallBlocked()
                }) {
                    Text(stringResource(R.string.update_install))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )

        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(stringResource(R.string.update_failed_body, state.message)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            },
        )

        else -> Unit
    }
}

/** Renders the release asset size for the download prompt; 0 = GitHub omitted it. */
private fun formatDownloadSize(bytes: Long): String =
    if (bytes <= 0L) "—" else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
