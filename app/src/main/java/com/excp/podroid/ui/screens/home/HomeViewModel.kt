package com.excp.podroid.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.BuildConfig
import com.excp.podroid.R
import com.excp.podroid.data.repository.ApkRelease
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.data.repository.UpdateRepository
import com.excp.podroid.engine.EngineHolder
import com.excp.podroid.engine.VmState
import com.excp.podroid.service.PodroidService
import com.excp.podroid.ui.components.OwnUpdateGate
import com.excp.podroid.ui.components.UpdateUiState
import com.excp.podroid.util.NetworkUtils
import com.excp.podroid.util.UptimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

/**
 * Aggregated Home metadata used by the data sections (resources, network,
 * last-session). `Resources` is shown in the meta row in every state; the
 * Network/LastSession sections render conditionally based on vmState.
 */
data class HomeMeta(
    val ramMb: Int,
    val cpus: Int,
    val storageGb: Int,
    val sshEnabled: Boolean,
    val portForwardCount: Int,
    val lastBootDurationMs: Long,
) {
    val resourcesLabel: String = "${formatRam(ramMb)} · $cpus CPU · ${storageGb} GB"

    private fun formatRam(mb: Int): String =
        if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineHolder,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = engine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    /** True while a stop is tearing the VM down (Running/Starting -> Stopped). */
    val stopping: StateFlow<Boolean> = engine.stopping
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val bootStage: StateFlow<String> = engine.bootStage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // Pushed live by the guest over the host bridge (STATS verb), persisted via
    // SettingsRepository.setLastContainerCount and read back here as a Flow -
    // works identically on both backends, no polling needed.
    val backendId: StateFlow<String> = engine.backendIdFlow

    val engineSelection: StateFlow<com.excp.podroid.engine.EngineSelection> = settingsRepository.engineSelection
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.excp.podroid.engine.EngineSelection.AUTO)

    val containerCount: StateFlow<Int?> = settingsRepository.lastContainerCount
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Aggregated metadata for the Home data sections. */
    val meta: StateFlow<HomeMeta> = combine(
        settingsRepository.vmRamMb,
        settingsRepository.vmCpus,
        settingsRepository.storageSizeGb,
        settingsRepository.sshEnabled,
        portForwardRepository.rules.map { it.size }.distinctUntilChanged(),
        settingsRepository.lastBootDurationMs,
    ) { values ->
        HomeMeta(
            ramMb = values[0] as Int,
            cpus = values[1] as Int,
            storageGb = values[2] as Int,
            sshEnabled = values[3] as Boolean,
            portForwardCount = values[4] as Int,
            lastBootDurationMs = values[5] as Long,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeMeta(ramMb = 512, cpus = 2, storageGb = 8, sshEnabled = false, portForwardCount = 0, lastBootDurationMs = 0L),
    )

    // Ticker — drives uptime display refresh every second, but only while the VM
    // is Running. Gated via flatMapLatest on vmState so no ticks (and no HomeScreen
    // recompositions) happen while the VM is Idle/Starting/Stopped.
    val uptimeTicker: StateFlow<Long> = engine.state
        .map { it is VmState.Running }
        .distinctUntilChanged()
        .flatMapLatest { isRunning ->
            if (isRunning) flow {
                while (true) {
                    emit(System.currentTimeMillis() / 1000)
                    delay(1000)
                }
            } else flowOf(0L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** Phone IPv4 — cheap, lazily recomputed when the screen reads it. */
    fun phoneIp(): String = NetworkUtils.localIpv4(context)

    // ── In-app update from OUR OWN releases ─────────────────────────────────
    // The upstream silent prompt is gone on purpose: it asked ExTV/Podroid for
    // its latest release, so every user of this build got offered a version
    // they cannot install over ours. This one asks our fork and only speaks up
    // when something newer actually exists.
    private val _ownUpdate = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    /** Drives the launch-time update prompt (see the shared dialog in ui/components). */
    val ownUpdate: StateFlow<UpdateUiState> = _ownUpdate.asStateFlow()

    private var ownUpdateJob: Job? = null

    /**
     * True when AVF (pKVM) is present on this device but neither
     * MANAGE_VIRTUAL_MACHINE nor USE_CUSTOM_VIRTUAL_MACHINE have been granted
     * yet — and the user hasn't dismissed the banner.
     *
     * The AVF probe is fast (no IPC, no binder), so we derive it via a
     * simple map on the dismissed flow rather than a heavy combine.
     */
    private val _avfProbe = com.excp.podroid.engine.avf.AvfDiagnostics.probe(context)
    val showAvfHint: StateFlow<Boolean> = settingsRepository.avfHintDismissed
        .map { dismissed ->
            _avfProbe.featureSupported &&
                !_avfProbe.managePermissionGranted &&
                !dismissed
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when AVF is selected but the current pick fell back to QEMU
     *  because this device can't run it. Passive - no actions, just tells the
     *  user why the VM isn't running on the backend they picked. See #66. */
    val backendFallbackBanner: StateFlow<Boolean> = combine(
        engine.backendFallback,
        settingsRepository.engineSelection,
    ) { fallback, sel ->
        fallback != null && sel == com.excp.podroid.engine.EngineSelection.AVF
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when AVF was the active backend and the VM ended in Error
     *  (boot failure / crash). Drives the actionable failure surface. */
    val avfBootFailure: StateFlow<Boolean> = vmState
        .map { it is VmState.Error && engine.backendId == "avf" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Advice for the failure surface, based on the current vCPU setting. */
    val avfFailureAdvice: StateFlow<com.excp.podroid.engine.avf.AvfFailureGuidance.Advice> =
        settingsRepository.vmCpus
            .map { com.excp.podroid.engine.avf.AvfFailureGuidance.advise(it) }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                com.excp.podroid.engine.avf.AvfFailureGuidance.Advice.SWITCH_TO_QEMU,
            )

    /** True while an AVF VM is starting/running and the headless-GPU networking
     *  workaround failed to attach (see [com.excp.podroid.engine.avf.AvfReflect.GpuConfigOutcome.FAILED]),
     *  which predicts virtmgr routing the VM onto crosvm_minimal (no virtio-net).
     *  AvfEngine always requests networking, so every AVF VM is affected.
     *  Combined live (not sampled) so the flag appears as soon as the outcome
     *  is recorded during startup. */
    val avfNetWorkaroundFailed: StateFlow<Boolean> = combine(
        vmState,
        com.excp.podroid.engine.avf.AvfReflect.lastGpuConfigOutcome,
    ) { state, outcome ->
        engine.backendId == "avf" &&
            (state is VmState.Running || state is VmState.Starting) &&
            outcome == com.excp.podroid.engine.avf.AvfReflect.GpuConfigOutcome.FAILED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun useOneCoreAndRetry() {
        viewModelScope.launch {
            settingsRepository.setVmCpus(1)
            restartVm()
        }
    }

    fun switchToQemuAndRetry() {
        viewModelScope.launch {
            settingsRepository.setEngineSelection(com.excp.podroid.engine.EngineSelection.QEMU)
            restartVm()
        }
    }

    init {
        checkOwnUpdateOnLaunch()
    }

    /** Format "Up Xm Ys" / "Up Xh Ym" from the engine's →Running timestamp. */
    fun uptimeLabel(@Suppress("UNUSED_PARAMETER") tickerTrigger: Long): String? {
        val since = engine.runningSinceMs ?: return null
        val totalSec = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        return context.getString(R.string.up) + " " + UptimeFormatter.format(context, totalSec)
    }

    /**
     * Launch-time check, once per process.
     *
     * Silent on "nothing new": the prompt exists for real updates only. Any
     * failure (offline, rate limit, JSON change) is swallowed — a startup
     * check must never surface an error the user didn't ask for; the manual
     * button in Settings is the path that reports failures.
     */
    private fun checkOwnUpdateOnLaunch() {
        if (OwnUpdateGate.checked) return
        OwnUpdateGate.checked = true
        ownUpdateJob?.cancel()
        ownUpdateJob = viewModelScope.launch {
            val release = try {
                updateRepository.checkOwnRelease(BuildConfig.VERSION_NAME)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                android.util.Log.w("HomeViewModel", "own update check failed", e)
                null
            }
            if (release != null) _ownUpdate.value = UpdateUiState.Available(release)
        }
    }

    fun downloadOwnUpdate(release: ApkRelease) {
        if (_ownUpdate.value is UpdateUiState.Downloading) return
        ownUpdateJob?.cancel()
        _ownUpdate.value = UpdateUiState.Downloading(0, release)
        ownUpdateJob = viewModelScope.launch {
            try {
                val apk: File = updateRepository.downloadApk(release) { percent ->
                    _ownUpdate.value = UpdateUiState.Downloading(percent, release)
                }
                _ownUpdate.value = UpdateUiState.Ready(apk, release)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                android.util.Log.w("HomeViewModel", "own update download failed", e)
                _ownUpdate.value = UpdateUiState.Failed(
                    e.message ?: context.getString(R.string.unknown_error),
                )
            }
        }
    }

    /** Idles the dialog. Refuses mid-download: the dialog is the only progress UI. */
    fun clearOwnUpdateState() {
        if (_ownUpdate.value is UpdateUiState.Downloading) return
        _ownUpdate.value = UpdateUiState.Idle
    }

    fun dismissAvfHint() {
        viewModelScope.launch { settingsRepository.setAvfHintDismissed(true) }
    }

    fun startPodroid() = PodroidService.start(context)

    fun stopVm() = PodroidService.stop(context)

    fun restartVm() {
        PodroidService.stop(context)
        viewModelScope.launch {
            // Only start if we observed a terminal state within the timeout window.
            // A null result means QEMU is still tearing down — starting over it would
            // race two instances on the same socket files and storage.img.
            val reached = withTimeoutOrNull(10_000) {
                engine.state.first { state ->
                    state is VmState.Stopped || state is VmState.Idle || state is VmState.Error
                }
            }
            if (reached != null) {
                PodroidService.start(context)
            }
        }
    }
}
