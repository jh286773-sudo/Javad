package com.javad.signal

/**
 * Platform-independent policy for keeping monitoring active.
 *
 * Android service, boot receiver, network callbacks and persistent storage
 * must call this policy from their platform adapters. This class deliberately
 * contains no Android APIs so the signal engine remains a plain Kotlin/JVM module.
 */
enum class MonitoringStatus {
    STOPPED,
    RUNNING,
    WAITING_FOR_NETWORK,
    RECOVERY_REQUIRED
}

enum class MonitoringEvent {
    USER_START,
    USER_STOP,
    SERVICE_INTERRUPTED,
    NETWORK_LOST,
    NETWORK_RESTORED,
    DEVICE_RESTARTED
}

data class MonitoringSnapshot(
    val userRequestedMonitoring: Boolean,
    val networkAvailable: Boolean,
    val status: MonitoringStatus
) {
    /** The Android adapter should start/restart its foreground service when true. */
    val shouldRunService: Boolean
        get() = userRequestedMonitoring

    /** The Android adapter should reconnect to market data when true. */
    val shouldConnectToMarketData: Boolean
        get() = userRequestedMonitoring && networkAvailable

    /** Persist this flag and restore it after BOOT_COMPLETED. */
    val shouldResumeAfterDeviceRestart: Boolean
        get() = userRequestedMonitoring
}

/**
 * Monitoring is stopped only by an explicit USER_STOP event.
 * Service interruptions and network/device restarts never clear user intent.
 */
class MonitoringRecoveryPolicy(
    initiallyEnabled: Boolean = false,
    initiallyNetworkAvailable: Boolean = true
) {
    private var userRequestedMonitoring = initiallyEnabled
    private var networkAvailable = initiallyNetworkAvailable

    fun onEvent(event: MonitoringEvent): MonitoringSnapshot {
        when (event) {
            MonitoringEvent.USER_START -> userRequestedMonitoring = true
            MonitoringEvent.USER_STOP -> userRequestedMonitoring = false
            MonitoringEvent.SERVICE_INTERRUPTED -> Unit
            MonitoringEvent.NETWORK_LOST -> networkAvailable = false
            MonitoringEvent.NETWORK_RESTORED -> networkAvailable = true
            MonitoringEvent.DEVICE_RESTARTED -> Unit
        }

        val status = when {
            !userRequestedMonitoring -> MonitoringStatus.STOPPED
            !networkAvailable -> MonitoringStatus.WAITING_FOR_NETWORK
            event == MonitoringEvent.SERVICE_INTERRUPTED ||
                event == MonitoringEvent.DEVICE_RESTARTED ->
                MonitoringStatus.RECOVERY_REQUIRED
            else -> MonitoringStatus.RUNNING
        }

        return MonitoringSnapshot(
            userRequestedMonitoring = userRequestedMonitoring,
            networkAvailable = networkAvailable,
            status = status
        )
    }

    fun snapshot(): MonitoringSnapshot {
        val status = when {
            !userRequestedMonitoring -> MonitoringStatus.STOPPED
            !networkAvailable -> MonitoringStatus.WAITING_FOR_NETWORK
            else -> MonitoringStatus.RUNNING
        }
        return MonitoringSnapshot(userRequestedMonitoring, networkAvailable, status)
    }
}
