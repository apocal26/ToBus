package com.example

import com.example.data.model.DataSourceMode
import com.example.data.model.LivePollInterval
import com.example.data.model.UserWidgetSettings
import com.example.data.network.GttRealtimeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSourceAndSettingsTest {

    @Test
    fun defaultSettingsHaveNoHardcodedLineAndAutoDataSource() {
        val defaults = UserWidgetSettings()
        assertEquals("", defaults.selectedLine)
        assertFalse(defaults.hasSelectedLine)
        assertEquals(DataSourceMode.AUTO, defaults.dataSourceMode)
        assertFalse(defaults.dataSourceMode.isFixed)
        assertEquals(LivePollInterval.SEC_25, defaults.livePollInterval)
        assertEquals(25_000L, defaults.livePollInterval.millis)
        assertFalse(defaults.keepScreenOn)
        assertTrue(defaults.showServiceAlerts)
        assertFalse(defaults.hideInactiveOnMap)
    }

    @Test
    fun hasSelectedLineReflectsNonBlankUserChoice() {
        assertFalse(UserWidgetSettings(selectedLine = "   ").hasSelectedLine)
        assertTrue(UserWidgetSettings(selectedLine = "4").hasSelectedLine)
        assertTrue(UserWidgetSettings(selectedLine = "SF2").hasSelectedLine)
    }

    @Test
    fun dataSourceModeFromIdAndMatoStreamRoutingWork() {
        assertEquals(DataSourceMode.AUTO, DataSourceMode.fromId(null))
        assertEquals(DataSourceMode.AUTO, DataSourceMode.fromId("unknown"))
        assertEquals(DataSourceMode.MATO_LIVE, DataSourceMode.fromId("mato_live"))
        assertEquals(DataSourceMode.GTT_OFFICIAL, DataSourceMode.fromId("gtt_official"))
        assertEquals(DataSourceMode.BUSONE_PROXY, DataSourceMode.fromId("busone_proxy"))

        assertFalse(DataSourceMode.AUTO.isFixed)
        assertTrue(DataSourceMode.MATO_LIVE.isFixed)
        assertTrue(DataSourceMode.GTT_OFFICIAL.isFixed)
        assertTrue(DataSourceMode.BUSONE_PROXY.isFixed)

        assertTrue(GttRealtimeRepository.shouldRunMatoStream(DataSourceMode.AUTO))
        assertTrue(GttRealtimeRepository.shouldRunMatoStream(DataSourceMode.MATO_LIVE))
        assertFalse(GttRealtimeRepository.shouldRunMatoStream(DataSourceMode.GTT_OFFICIAL))
        assertFalse(GttRealtimeRepository.shouldRunMatoStream(DataSourceMode.BUSONE_PROXY))
    }

    @Test
    fun livePollIntervalFromSecondsResolvesCleanly() {
        assertEquals(LivePollInterval.SEC_15, LivePollInterval.fromSeconds(15))
        assertEquals(LivePollInterval.SEC_25, LivePollInterval.fromSeconds(25))
        assertEquals(LivePollInterval.SEC_45, LivePollInterval.fromSeconds(45))
        assertEquals(LivePollInterval.SEC_25, LivePollInterval.fromSeconds(999))
        assertEquals(15_000L, LivePollInterval.SEC_15.millis)
        assertEquals(45_000L, LivePollInterval.SEC_45.millis)
    }
}
