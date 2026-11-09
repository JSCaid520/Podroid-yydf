/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Foreground service that hosts the QEMU process for Podroid.
 * Holds a WakeLock to prevent the device from sleeping while the VM runs.
 */
package com.excp.podroid.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.excp.podroid.MainActivity
import com.excp.podroid.PodroidApplication
import com.excp.podroid.R
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.engine.usb.UsbPassthroughManager
import com.excp.podroid.util.NetworkUtils
import com.excp.podroid.x11.X11Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Runs [action], swallowing a denied foreground-service start instead of
 * letting it crash the caller. Both a cold [VmControlReceiver] dispatch and
 * a guest-requested [PodroidService.scheduleRestart] can call
 * [PodroidService.start] outside a foreground context, where Android may
 * deny it with a [android.app.ForegroundServiceStartNotAllowedException]
 * (API 31+, extends [IllegalStateException]; pre-31 the same denial surfaces
 * as a plain [IllegalStateException] from `startService()`). Never silent:
 * logs one warning naming the cure (launch Podroid so its START_VM activity
 * intent runs in the foreground, or exempt the app from battery
 * optimization).
 */
private fun runIgnoringBackgroundFgsDenial(tag: String, action: () -> Unit) {
    try {
        action()
    } catch (e: IllegalStateException) {
        val backgroundFgsDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            e is android.app.ForegroundServiceStartNotAllowedException
        Log.w(
            tag,
            "could not start/stop the VM from the background " +
                "(${if (backgroundFgsDenied) "foreground service start disallowed" else e.message}) - " +
                "launch Podroid so its START_VM activity intent runs in the foreground, " +
                "or exempt the app from battery optimization",
            e,
        )
    }
}

/**
 * Awaits application asset readiness and publishes a user-visible VM error if
 * readiness fails. The original exception is rethrown so the service's normal
 * startup-failure cleanup still releases the WakeLock and foreground service.
 */
internal suspend fun awaitAssetsReadyForVm(
    awaitReady: suspend () -> Unit,
    failureMessage: String,
    reportFailure: (String) -> Unit,
) {
    try {
        awaitReady()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        reportFailure(failureMessage)
        throw e
    }
}

@AndroidEntryPoint
class PodroidService : Service() {

    @Inject lateinit var engine: VmEngine
    // The same @Singleton as [engine]; injected by type for its UML fallback notices.
    @Inject lateinit var engineHolder: com.excp.podroid.engine.EngineHolder
    @Inject lateinit var portForwardRepository: PortForwardRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var usbPassthroughManager: UsbPassthroughManager
    @Inject lateinit var notificationPoster: com.excp.podroid.engine.hostbridge.AndroidNotificationPoster
    @Inject lateinit var headlessModeManager: com.excp.podroid.engine.hostbridge.HeadlessModeManager
    private var hostRequestServer: com.excp.podroid.engine.hostbridge.HostRequestServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var notificationBuilder: NotificationCompat.Builder? = null

    /** Always-on-top status bubble; only alive while the VM is up. */
    private var overlayBubble: OverlayBubble? = null

    /** Persisted rule count, mirrored for the bubble's status line. */
    @Volatile private var forwardCount = 0

    /**
     * Mirror of SettingsRepository.overlayBubbleEnabled, so the state collectors
     * can decide synchronously without suspending. Both the bubble's own "hide"
     * action and the Settings switch write that pref - this field only follows it.
     */
    @Volatile private var overlayEnabled = true


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // One pref serves two entry points: the panel's "hide" action sets it to
        // false, the Settings switch sets it back to true. Watching it here keeps
        // both honest - hiding removes the window at once, re-enabling brings it
        // straight back while the VM is up instead of waiting for the next start.
        serviceScope.launch {
            settingsRepository.overlayBubbleEnabled.collect { enabled ->
                val was = overlayEnabled
                overlayEnabled = enabled
                if (!enabled) {
                    hideOverlay()
                } else if (!was && bubbleWantedByState()) {
                    ensureOverlay()?.refresh()
                }
            }
        }
        userStopRequested = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                userStopRequested = false
                // A start the user (or an automation intent) asked for deserves a
                // fresh budget; a start we scheduled ourselves must keep counting,
                // otherwise a boot-loop would reload the budget on every retry.
                if (!pendingAutoRestart) autoRestartAttempts = 0
                pendingAutoRestart = false
                restartInFlight = false
                // Android 14+ (API 34) requires the foregroundServiceType argument
                // when the manifest declares foregroundServiceType="specialUse";
                // otherwise Android throws MissingForegroundServiceTypeException.
                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                // Non-fatal diagnostic: on API 33+ a missing POST_NOTIFICATIONS
                // grant makes the persistent notification (and its Stop action)
                // invisible while the WakeLock is held. We do NOT gate VM start on
                // this, just log so the invisible-notification state is
                // diagnosable. (The setup screen requests the permission.)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "POST_NOTIFICATIONS not granted; foreground notification " +
                        "and its Stop action will be invisible while the VM holds the WakeLock")
                }
                // Always (re-)assert foreground within the start window — required
                // even on a redundant ACTION_START so the system doesn't fault us
                // for not calling startForeground.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Starting VM..."),
                    fgType,
                )
                // De-dup: if the VM is already active, do NOT re-acquire/relaunch.
                // launchPodroid relaunches the observers (resetting their
                // seenActive latch); doing that on a double-tap could strand the
                // teardown path. The engine's own start() is also a no-op here.
                val alreadyActive = engine.state.value is VmState.Starting ||
                    engine.state.value is VmState.Running
                if (!alreadyActive) {
                    acquireWakeLock()
                    launchPodroid()
                }
            }
            ACTION_STOP -> {
                userStopRequested = true
                // A restart may be in flight (guest "restart", auto-restart). The
                // explicit Stop wins: clear the marker so the shutdown observer
                // stops ignoring terminals and the poller aborts (see
                // scheduleRestart).
                restartInFlight = false
                val wasActive = engine.state.value is VmState.Starting ||
                    engine.state.value is VmState.Running
                engine.stop()
                if (wasActive) {
                    // engine.stop() kicks off an async best-effort guest flush
                    // (AVF syncAndWait, up to 8s) on the engine's own scope and
                    // returns immediately. Releasing the partial WakeLock here
                    // would let the CPU suspend mid-flush with the screen off,
                    // defeating the deliberate ext4 sync. Keep it held; the
                    // shutdown observer runs teardown() (release + stopSelf) once
                    // the engine reaches Stopped/Error.
                    Log.d(TAG, "ACTION_STOP: VM active — deferring teardown to state observer")
                } else {
                    // Stop while idle (e.g. a fresh service spun up only to stop):
                    // no terminal transition will arrive, so release + stop now so
                    // this doesn't linger as a started-but-never-foregrounded orphan.
                    releaseWakeLock()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            else -> {
                // Null/unrecognized action (e.g. a system redelivery): we never
                // called startForeground for this start, so just stop to avoid a
                // started-but-not-foregrounded service.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        notificationJob?.cancel()
        hostRequestServer?.stop()
        usbPassthroughManager.stop()
        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping Podroid out of recents must NOT stop the VM: this is a headless
        // server, and the whole point of the foreground service is to keep
        // containers reachable while the UI is gone. The VM is stopped only by an
        // explicit Stop (notification action, bubble, automation intent), and the
        // overlay bubble keeps the process counted as in-use after the swipe.
        Log.d(TAG, "task removed; keeping the VM and the foreground service alive")
        overlayBubble?.refresh()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Podroid::VmWakeLock"
            ).apply {
                @SuppressLint("WakelockTimeout")
                acquire() // VM must run indefinitely — timeout would kill it
            }
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            launch { observeStateForNotification() }
            launch { observeStateForShutdown() }
            launch { observeStateForOverlay() }
            launch {
                portForwardRepository.rules.collect { rules ->
                    forwardCount = rules.size
                    overlayBubble?.refresh()
                }
            }
            launch { observeUmlFallbackNotices() }
        }
    }

    /** Tells the user when a failing UML VM was restarted on another backend. */
    private suspend fun observeUmlFallbackNotices() {
        engineHolder.umlFallbackNotices.collect { backendId ->
            if (!notificationPoster.notificationsPermitted()) {
                Log.w(TAG, "UML fell back to $backendId; notifications not permitted, notice not posted")
                return@collect
            }
            val localized = com.excp.podroid.data.repository.LanguageManager.wrapContextForLocale(
                this, com.excp.podroid.data.repository.LanguageManager.getSavedLanguage(this),
            )
            notificationPoster.post(
                title = null,
                body = localized.getString(R.string.uml_fallback_notice, backendId.uppercase(java.util.Locale.ROOT)),
                priority = com.excp.podroid.engine.hostbridge.HostProtocol.PRIO_NORMAL,
                id = null,
            )
        }
    }

    /** Updates the notification text from a combined view of state + bootStage. */
    private suspend fun observeStateForNotification() {
        combine(engine.state, engine.bootStage) { state, stage -> state to stage }
            .collect { (state, stage) ->
                when (state) {
                    is VmState.Running  -> updateNotification("VM is running")
                    is VmState.Starting -> updateNotification(stage.ifEmpty { "Starting VM..." })
                    else -> {} // Terminal states handled by the shutdown observer
                }
            }
    }

    /**
     * Releases the wakelock and stops the service when the VM reaches a
     * terminal state.
     *
     * `Error`/`Stopped` are treated as always-actionable: an engine that goes
     * straight to `Error` without ever emitting `Starting` (e.g. a missing QEMU
     * binary), or a conflated `Idle→Starting→Error` that the Main collector only
     * observes as `Error`, must still release the no-timeout WakeLock and stop
     * the foreground service. The `seenActive` latch exists only to skip the
     * initial `Idle` replay before the VM has been launched, so the guard now
     * gates `Idle` alone — terminal failures are never silently swallowed.
     */
    private suspend fun observeStateForShutdown() {
        var seenActive = false
        // The engine is a process-lifetime @Singleton, so its StateFlow replays
        // the PRIOR cycle's retained terminal state (Stopped/Error) the instant
        // this fresh collector subscribes — before the engine.start() that
        // launchPodroid is about to run flips it to Starting. Acting on that
        // replayed value would teardown (stopSelf → onDestroy → serviceScope
        // .cancel) the very start we are kicking off, cancelling it as "Job was
        // cancelled" and leaving a new stale Error that poisons the next attempt
        // too — so the VM never restarts until the process is killed. Consume the
        // first emission as a baseline; only act on transitions that follow it.
        var baselineConsumed = false
        engine.state.collect { state ->
            if (!baselineConsumed) {
                baselineConsumed = true
                if (state is VmState.Starting || state is VmState.Running) seenActive = true
                return@collect
            }
            when (state) {
                is VmState.Starting, is VmState.Running -> seenActive = true
                is VmState.Idle -> {
                    if (!seenActive) return@collect
                    teardown()
                }
                is VmState.Stopped, is VmState.Error -> {
                    if (restartInFlight) {
                        // The stop this terminal state came from is our own
                        // restart; the replacement start is already scheduled.
                        Log.d(TAG, "terminal state during a scheduled restart; ignoring")
                        return@collect
                    }
                    // Unexpected exit (crash, OOM-killed QEMU, guest panic) while
                    // the user never asked to stop: put the VM back up instead of
                    // letting the server silently die. Bounded, so a VM that
                    // cannot boot (missing binary, bad config) still ends in a
                    // clean teardown with the error notification visible.
                    val uptimeMs = if (lastBootAtMs > 0) {
                        System.currentTimeMillis() - lastBootAtMs
                    } else {
                        0L
                    }
                    if (uptimeMs >= STEADY_UPTIME_MS) {
                        // It ran fine for minutes before dying, so treat this as a
                        // one-off and refill the budget.
                        autoRestartAttempts = 0
                    }
                    val unexpected = seenActive && !userStopRequested
                    if (unexpected && autoRestartAttempts < MAX_AUTO_RESTARTS) {
                        autoRestartAttempts++
                        pendingAutoRestart = true
                        Log.w(
                            TAG,
                            "VM exited unexpectedly (state=$state, uptime=${uptimeMs}ms); " +
                                "auto-restart $autoRestartAttempts/$MAX_AUTO_RESTARTS",
                        )
                        updateNotification(
                            "VM exited - restarting ($autoRestartAttempts/$MAX_AUTO_RESTARTS)",
                        )
                        scheduleRestart()
                    } else {
                        autoRestartAttempts = 0
                        pendingAutoRestart = false
                        teardown()
                    }
                }
            }
        }
    }

    /** Release the WakeLock, drop the foreground notification, and stop. */
    private fun teardown() {
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    /**
     * Arms USB passthrough once the VM is Running and tears it down when the VM
     * reaches a terminal state. Only launched when the feature is enabled, so
     * the BroadcastReceiver is live strictly while a VM session is up.
     */
    private suspend fun observeStateForUsb() {
        engine.state.collect { state ->
            when (state) {
                is VmState.Running -> usbPassthroughManager.start()
                is VmState.Stopped, is VmState.Idle, is VmState.Error ->
                    usbPassthroughManager.stop()
                else -> {}
            }
        }
    }

    /**
     * Keeps the status bubble in step with the VM: shown from the first sign of
     * life (so a slow boot can be watched and stopped) and removed once the VM
     * is gone. Also arms the auto-restart budget reset.
     */
    private suspend fun observeStateForOverlay() {
        engine.state.collect { state ->
            when (state) {
                is VmState.Running -> {
                    // Timestamped so the next unexpected exit can tell a healthy
                    // long run (budget refilled) from a boot-loop (kept depleted).
                    lastBootAtMs = System.currentTimeMillis()
                    if (overlayEnabled) ensureOverlay()?.refresh()
                }
                is VmState.Starting -> {
                    if (overlayEnabled) ensureOverlay()?.refresh()
                }
                else -> {
                    // Idle/Stopped/Error: nothing to show over.
                    hideOverlay()
                }
            }
        }
    }

    private fun ensureOverlay(): OverlayBubble? {
        overlayBubble?.let { if (it.isShowing()) return it }
        if (!overlayEnabled) {
            // Switched off in Settings (or hidden from the bubble's own menu).
            Log.i(TAG, "status bubble disabled; not showing it")
            return null
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted; no status bubble this session")
            return null
        }
        val bubble = OverlayBubble(
            context = this,
            statusLine = { buildOverlayStatus() },
            onOpenApp = {
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { Log.w(TAG, "could not open the app from the bubble", it) }
            },
            onToggleVm = { handleOverlayToggle() },
            onRestartForwards = { restartAllForwards() },
            onHide = {
                // Persist it. The Settings switch is the documented way back, and
                // a session-only flag would resurrect the ball on the next VM
                // start anyway - which is the opposite of what "hidden" means.
                serviceScope.launch { settingsRepository.setOverlayBubbleEnabled(false) }
                hideOverlay()
            },
        )
        overlayBubble = if (bubble.show()) bubble else null
        return overlayBubble
    }

    private fun hideOverlay() {
        overlayBubble?.hide()
        overlayBubble = null
    }

    /** True while the VM is up or coming up - the only states that show a bubble. */
    private fun bubbleWantedByState(): Boolean = when (engine.state.value) {
        is VmState.Running, is VmState.Starting -> true
        else -> false
    }

    private fun buildOverlayStatus(): String =
        "${stateLabel(engine.state.value)} · 转发 $forwardCount 条"

    private fun stateLabel(state: VmState): String = when (state) {
        is VmState.Running -> "VM 运行中"
        is VmState.Starting -> "VM 启动中"
        is VmState.Stopped -> "VM 已停止"
        is VmState.Error -> "VM 出错"
        is VmState.Idle -> "VM 未启动"
    }

    private fun handleOverlayToggle() {
        val active = engine.state.value is VmState.Running ||
            engine.state.value is VmState.Starting
        if (active) {
            userStopRequested = true
            PodroidService.stop(this)
        } else {
            userStopRequested = false
            PodroidService.start(this)
        }
    }

    /**
     * Re-issues every live forward (persisted user rules plus the implicit
     * SSH/VNC/audio ones) as remove-then-add over QMP. Used when a forward's
     * host-side socket died - port taken, QMP hiccup - without touching the VM:
     * QEMU re-binds the listener without a reboot.
     */
    private fun restartAllForwards() {
        serviceScope.launch {
            if (engine.state.value !is VmState.Running) {
                toastMessage("虚拟机未运行，无法重播转发")
                return@launch
            }
            val replayed = runCatching { engine.replayPortForwards() }
                .onFailure { Log.w(TAG, "forward replay failed", it) }
                .getOrDefault(0)
            toastMessage(
                if (replayed > 0) "已重播 $replayed 条转发" else "没有可重播的转发"
            )
            overlayBubble?.refresh()
        }
    }

    private fun toastMessage(text: String) {
        runCatching { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    private fun ensureHostBridge(): com.excp.podroid.engine.hostbridge.HostRequestServer {
        hostRequestServer?.let { return it }
        val dispatcher = com.excp.podroid.engine.hostbridge.HostRequestDispatcher(
            notifications = notificationPoster,
            addForward = { portForwardRepository.addRule(it) },
            removeForward = { portForwardRepository.removeRule(it) },
            listForwards = { portForwardRepository.getRulesSnapshot() },
            clearForwards = { portForwardRepository.clearRules() },
            openUrl = { handleOpenUrl(it) },
            power = { handlePowerRequest(it) },
            setHeadless = { handleHeadlessRequest(it) },
            setContainerCount = { settingsRepository.setLastContainerCount(it) },
            phoneAddresses = { withContext(Dispatchers.IO) { NetworkUtils.allLocalIpv4(this@PodroidService) } },
        )
        return com.excp.podroid.engine.hostbridge.HostRequestServer(
            openTransport = { engine.openHostTransport() },
            dispatcher = dispatcher,
            scope = serviceScope,
        ).also { hostRequestServer = it }
    }

    /** Always-on: starts the guest host bridge on Running, stops it on terminal. */
    private suspend fun observeStateForHostBridge() {
        engine.state.collect { state ->
            when (state) {
                is VmState.Running -> ensureHostBridge().start()
                is VmState.Stopped, is VmState.Idle, is VmState.Error -> {
                    hostRequestServer?.stop()
                    // Drop server mode so the black overlay doesn't linger over a
                    // stopped/crashed VM with no indication it died.
                    headlessModeManager.setActive(false)
                }
                else -> {}
            }
        }
    }

    private fun launchPodroid() {
        serviceScope.launch {
            startNotificationUpdates()
            withContext(Dispatchers.IO) {
                try {
                    // Asset extraction now runs off the main thread; the engine
                    // reads vmlinuz/initrd/squashfs synchronously in start(), so
                    // we MUST block here until extraction has fully completed or
                    // the VM could launch against a partial/missing file.
                    (application as? PodroidApplication)?.let { app ->
                        awaitAssetsReadyForVm(
                            awaitReady = { app.awaitAssetsReady() },
                            failureMessage = getString(R.string.asset_extraction_failed),
                            reportFailure = engine::reportStartFailure,
                        )
                    }

                    val rules = portForwardRepository.getRulesSnapshot().toMutableList()
                    val sshEnabled = settingsRepository.getSshEnabledSnapshot()

                    // Auto-inject SSH port forward when SSH is enabled
                    if (sshEnabled && rules.none { it.hostPort == SSH_HOST_PORT }) {
                        rules.add(com.excp.podroid.data.repository.PortForwardRule(SSH_HOST_PORT, 22, "tcp"))
                    }

                    // Always-on X11 viewer forwards. These are implicit, not user-managed —
                    // they back the in-app screen toggle and never appear in the PortForward UI.
                    // loopbackOnly = bind 127.0.0.1: the in-app viewer dials loopback, and
                    // Xvnc runs with no auth + PulseAudio streams raw PCM, so binding 0.0.0.0
                    // would hand any device on the same Wi-Fi a no-password root X session.
                    if (rules.none { it.hostPort == X11Constants.VNC_PORT }) {
                        rules.add(com.excp.podroid.data.repository.PortForwardRule(X11Constants.VNC_PORT, X11Constants.VNC_PORT, "tcp", loopbackOnly = true))
                    }
                    if (rules.none { it.hostPort == X11Constants.AUDIO_PORT }) {
                        rules.add(com.excp.podroid.data.repository.PortForwardRule(X11Constants.AUDIO_PORT, X11Constants.AUDIO_PORT, "tcp", loopbackOnly = true))
                    }

                    val config = VmConfig(
                        ramMb = settingsRepository.getVmRamMbSnapshot(),
                        cpus = settingsRepository.getVmCpusSnapshot(),
                        sshEnabled = sshEnabled,
                        androidIp = NetworkUtils.localIpv4(this@PodroidService),
                        dnsServers = NetworkUtils.dnsServers(this@PodroidService),
                        storageSizeGb = settingsRepository.getStorageSizeGbSnapshot(),
                        storageAccessEnabled = settingsRepository.getStorageAccessEnabledSnapshot(),
                        qemuExtraArgs = settingsRepository.getQemuExtraArgsSnapshot(),
                        kernelExtraCmdline = settingsRepository.getKernelExtraCmdlineSnapshot(),
                        verboseLogging = settingsRepository.getAvfVerboseLoggingSnapshot(),
                        x11Dpi = settingsRepository.getX11DpiSnapshot(),
                        usbPassthroughEnabled = settingsRepository.getUsbPassthroughEnabledSnapshot(),
                        bandwidthMbps = settingsRepository.getBandwidthMbpsSnapshot(),
                    )
                    serviceScope.launch { observeStateForHostBridge() }
                    if (config.usbPassthroughEnabled) {
                        serviceScope.launch { observeStateForUsb() }
                    }
                    engine.start(rules, config)
                } catch (c: CancellationException) {
                    throw c // a stop/teardown cancelled this launch; not a start failure
                } catch (e: Exception) {
                    Log.e(TAG, "VM failed to start", e)
                    // A Service-side throw here (failed asset extraction, a
                    // snapshot read, or engine.start()) can happen before the
                    // engine state ever leaves Idle. In that window the shutdown
                    // observer's seenActive latch is still false, so its Idle
                    // branch returns without teardown and nothing releases the
                    // no-timeout WakeLock or drops the foreground notification.
                    // Tear down here so a start failure cleans itself up.
                    // teardown() is idempotent (releaseWakeLock guards on isHeld),
                    // and this only runs on a thrown exception, never the success
                    // path. Hop back to Main since teardown touches the service.
                    withContext(Dispatchers.Main) { teardown() }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Podroid Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the status of the Podman VM"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Lazily build (and cache) the NotificationCompat.Builder + its PendingIntents
     * once per service lifetime. Boot streams ~5 state-change emits per second, so
     * recreating the Builder + two PendingIntents on every emit is pure churn.
     * After the first call, updateNotification() just mutates contentText on the
     * cached builder.
     */
    private fun getOrCreateNotificationBuilder(): NotificationCompat.Builder {
        notificationBuilder?.let { return it }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PodroidService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Podroid")
            .setSmallIcon(R.drawable.ic_vm_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
        notificationBuilder = builder
        return builder
    }

    private fun buildNotification(status: String): Notification {
        return getOrCreateNotificationBuilder()
            .setContentText(status)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun handleOpenUrl(url: String): String {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https")) {
            return com.excp.podroid.engine.hostbridge.HostProtocol.err("only http/https URLs are allowed")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            com.excp.podroid.engine.hostbridge.HostProtocol.ok()
        } catch (e: Throwable) {
            com.excp.podroid.engine.hostbridge.HostProtocol.err("no app available to open this URL")
        }
    }

    // HostRequestDispatcher.handleHeadless already rejects any action other
    // than on/off/status before calling this, so the else branch here can
    // never run.
    private fun handleHeadlessRequest(action: String): String = when (action) {
        "on" -> { headlessModeManager.setActive(true); com.excp.podroid.engine.hostbridge.HostProtocol.ok() }
        "off" -> { headlessModeManager.setActive(false); com.excp.podroid.engine.hostbridge.HostProtocol.ok() }
        "status" -> com.excp.podroid.engine.hostbridge.HostProtocol.ok(if (headlessModeManager.active.value) "on" else "off")
        else -> error("unreachable: action=$action")
    }

    // Reply returned now; the stop/restart is posted to the main looper so the
    // bridge flushes the response before the VM (and this service) tear down. The
    // Handler callbacks capture the app-scoped engine + applicationContext (NOT
    // `this`), so they survive this service's death.
    // HostRequestDispatcher.handlePower already rejects any action other than
    // stop/restart/status before calling this, so the else branch here can
    // never run.
    private fun handlePowerRequest(action: String): String {
        val proto = com.excp.podroid.engine.hostbridge.HostProtocol
        return when (action) {
            // Map explicitly, not via javaClass.simpleName: R8 obfuscates class
            // names in release builds, so simpleName returns garbage like "wc2".
            "status" -> proto.ok(when (engine.state.value) {
                is VmState.Idle -> "idle"
                is VmState.Starting -> "starting"
                is VmState.Running -> "running"
                is VmState.Stopped -> "stopped"
                is VmState.Error -> "error"
            })
            "stop" -> {
                val ctx = applicationContext
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ PodroidService.stop(ctx) }, 300)
                proto.ok()
            }
            "restart" -> { scheduleRestart(); proto.ok() }
            else -> error("unreachable: action=$action")
        }
    }

    private fun scheduleRestart() {
        // Marked before the delay so any terminal emission produced in the next
        // 300ms is recognized as "this restart", not a second crash.
        restartInFlight = true
        val ctx = applicationContext
        val eng = engine
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.postDelayed({
            PodroidService.stop(ctx)
            var tries = 0
            val poll = object : Runnable {
                override fun run() {
                    val s = eng.state.value
                    // Only Stopped/Error are genuine terminals after engine.stop().
                    // Idle is the engine's pre-start normalized state, so treating
                    // it as terminal could fire start() during a teardown blip.
                    val terminal = s is VmState.Stopped || s is VmState.Error
                    when {
                        terminal -> {
                            if (userStopRequested) {
                                // The user pressed Stop while we were waiting for
                                // the old VM to die: their stop wins.
                                restartInFlight = false
                                return
                            }
                            var started = false
                            runIgnoringBackgroundFgsDenial(TAG) {
                                PodroidService.start(ctx)
                                started = true
                            }
                            if (!started) {
                                // Denied (background FGS restriction, app force-
                                // stopped): clear the marker so the next crash is
                                // evaluated instead of silently ignored forever.
                                Log.w(TAG, "restart denied by the system; VM stays stopped")
                                restartInFlight = false
                            }
                        }
                        tries++ >= 40 -> {
                            Log.w(TAG, "restart: VM did not reach a stopped state in time (state=$s); starting anyway")
                            runIgnoringBackgroundFgsDenial(TAG) { PodroidService.start(ctx) }
                        }
                        else -> main.postDelayed(this, 250)
                    }
                }
            }
            main.postDelayed(poll, 500)
        }, 300)
    }

    companion object {
        private const val TAG = "PodroidService"
        private const val CHANNEL_ID = "podroid_service"
        private const val NOTIFICATION_ID = 1001

        /** Consecutive unexpected-exit restarts before giving up and tearing down. */
        private const val MAX_AUTO_RESTARTS = 3

        /** Uptime after which the auto-restart budget is considered healthy again. */
        private const val STEADY_UPTIME_MS = 5 * 60 * 1000L

        // Restart bookkeeping is deliberately process-wide, not per-instance: an
        // auto-restart goes stop -> stopSelf -> start, which builds a NEW service
        // instance. Instance fields would reset the budget on every retry and let
        // a boot-loop (bad config, missing binary) spin forever.
        @Volatile private var autoRestartAttempts = 0
        @Volatile private var lastBootAtMs = 0L
        @Volatile private var pendingAutoRestart = false

        /**
         * True only when the user (or an automation intent) asked for a stop. The
         * shutdown observer uses it to tell "the VM died on its own" (restart it)
         * from "the user wanted it stopped" (tear down for good). Process-wide for
         * the same reason as the budget: a stop can land while a restart is being
         * polled by a service instance that is already dying.
         */
        @Volatile private var userStopRequested = false

        /** True from scheduleRestart() until the replacement start() arrives. */
        @Volatile private var restartInFlight = false

        const val ACTION_START   = "com.excp.podroid.action.START"
        const val ACTION_STOP    = "com.excp.podroid.action.STOP"
        const val SSH_HOST_PORT  = 9922

        fun start(context: Context) {
            val intent = Intent(context, PodroidService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PodroidService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}

/**
 * Boot autostart + Tasker/automation intent entry point (#78). Exported with no
 * required permission (see the manifest declaration): automation apps can't hold
 * custom permissions, and every action here only starts/stops the VM through
 * [PodroidService]'s own companion, so there's nothing privileged to gate. Kept
 * dumb on purpose - no work beyond dispatching to the existing start()/stop().
 *
 * Fix round (device-observed): a cold background receiver may NOT be allowed
 * to start the foreground service - Android denies it with
 * "Background started FGS: Disallowed" and throws
 * ForegroundServiceStartNotAllowedException (API 31+, extends
 * IllegalStateException; pre-31 the same denial surfaces as a plain
 * IllegalStateException from startService()). BOOT_COMPLETED itself is
 * platform-exempt, but START_VM/STOP_VM fired cold are not. [dispatch] keeps
 * every call here from crashing the receiver process on that denial.
 */
class VmControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // The DataStore read is async; goAsync() keeps the receiver
                // (and process) alive long enough for it to complete, since a
                // BroadcastReceiver's onReceive is normally torn down the
                // instant it returns.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settingsRepository = SettingsRepository(context.applicationContext)
                        if (settingsRepository.getAutostartOnBootSnapshot()) {
                            dispatch { PodroidService.start(context.applicationContext) }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_START_VM -> dispatch { PodroidService.start(context.applicationContext) }
            ACTION_STOP_VM  -> dispatch { PodroidService.stop(context.applicationContext) }
        }
    }

    /** See [runIgnoringBackgroundFgsDenial] for what this guards against. */
    private fun dispatch(action: () -> Unit) = runIgnoringBackgroundFgsDenial(TAG, action)

    companion object {
        private const val TAG = "VmControlReceiver"
        const val ACTION_START_VM = "com.excp.podroid.action.START_VM"
        const val ACTION_STOP_VM  = "com.excp.podroid.action.STOP_VM"
    }
}
