package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.TaildropStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TaildropApprovalSerializationTest {
    @Test fun decodesIndependentFileRequestsAndUnknownSizes() {
        val status = Json.decodeFromString(TaildropStatus.serializer(), """
            {"running":true,"session":"vpn","offers":[
              {"id":"a","name":"one.pdf","size":123,"sender":"MacBook"},
              {"id":"b","name":"two.zip","size":-1,"sender":"MacBook"}
            ]}
        """.trimIndent())
        assertEquals(listOf("a", "b"), status.offers.map { it.id })
        assertEquals(-1L, status.offers[1].size)
        assertTrue(status.files.isEmpty())
        assertTrue(status.incoming.isEmpty())
    }

    @Test fun emptyStatusHasNoImplicitlyApprovedRequests() {
        assertTrue(Json.decodeFromString(TaildropStatus.serializer(), "{}").offers.isEmpty())
    }
}
