package com.javad.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitoringRecoveryPolicyTest {
    @Test
    fun monitoringRemainsRequestedAfterServiceInterruption() {
        val policy = MonitoringRecoveryPolicy()
        policy.onEvent(MonitoringEvent.USER_START)
        val snapshot = policy.onEvent(MonitoringEvent.SERVICE_INTERRUPTED)
        assertEquals(MonitoringStatus.RECOVERY_REQUIRED, snapshot.status)
        assertTrue(snapshot.shouldRunService)
        assertTrue(snapshot.shouldResumeAfterDeviceRestart)
    }

    @Test
    fun networkLossWaitsAndNetworkRestorationReconnects() {
        val policy = MonitoringRecoveryPolicy(initiallyEnabled = true)
        val offline = policy.onEvent(MonitoringEvent.NETWORK_LOST)
        assertEquals(MonitoringStatus.WAITING_FOR_NETWORK, offline.status)
        assertTrue(offline.shouldRunService)
        assertFalse(offline.shouldConnectToMarketData)
        val online = policy.onEvent(MonitoringEvent.NETWORK_RESTORED)
        assertEquals(MonitoringStatus.RUNNING, online.status)
        assertTrue(online.shouldConnectToMarketData)
    }

    @Test
    fun onlyExplicitUserStopDisablesMonitoring() {
        val policy = MonitoringRecoveryPolicy(initiallyEnabled = true)
        policy.onEvent(MonitoringEvent.DEVICE_RESTARTED)
        val snapshot = policy.onEvent(MonitoringEvent.USER_STOP)
        assertEquals(MonitoringStatus.STOPPED, snapshot.status)
        assertFalse(snapshot.shouldRunService)
        assertFalse(snapshot.shouldResumeAfterDeviceRestart)
    }
}
