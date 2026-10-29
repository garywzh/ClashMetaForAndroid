package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.TailscaleForwardRule
import com.github.kr328.clash.core.model.TailscaleSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TailscaleSettingsSerializationTest {
    @Test fun settingsAlwaysSendFalseTogglesButNeverOverwriteForwardRules() {
        val encoded = Json.encodeToString(TailscaleSettings.serializer(), TailscaleSettings(false, false))
        assertTrue(encoded.contains("\"enabled\":false"))
        assertTrue(encoded.contains("\"acceptRoutes\":false"))
        assertFalse(encoded.contains("forwards"))
    }

    @Test fun bothForwardToggleValuesAreExplicitOnTheNativeWire() {
        for (enabled in listOf(true, false)) {
            val rule = TailscaleForwardRule("web", "Web", enabled, 8000, 8080)
            val encoded = Json.encodeToString(TailscaleForwardRule.serializer(), rule)
            assertTrue(encoded.contains("\"enabled\":$enabled"))
            assertEquals(rule, Json.decodeFromString(TailscaleForwardRule.serializer(), encoded))
        }
    }
}
