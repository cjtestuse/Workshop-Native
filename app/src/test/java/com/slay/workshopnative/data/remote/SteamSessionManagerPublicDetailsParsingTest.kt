package com.slay.workshopnative.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamSessionManagerPublicDetailsParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class LongWrapper(
        @SerialName("value")
        @Serializable(with = SteamSessionManager.LenientNullableLongSerializer::class)
        val value: Long? = null,
    )

    @Test
    fun lenientNullableLongSerializer_returns_null_for_overflow_value() {
        val decoded = json.decodeFromString<LongWrapper>(
            """{"value":18446744073709551615}""",
        )

        assertNull(decoded.value)
    }

    @Test
    fun lenientNullableLongSerializer_accepts_regular_number_and_string() {
        val numberDecoded = json.decodeFromString<LongWrapper>(
            """{"value":123456789}""",
        )
        val stringDecoded = json.decodeFromString<LongWrapper>(
            """{"value":"987654321"}""",
        )

        assertEquals(123456789L, numberDecoded.value)
        assertEquals(987654321L, stringDecoded.value)
    }

    @Test
    fun publicWorkshopFileTypeSupport_keeps_collection_items() {
        assertTrue(SteamSessionManager.isSupportedPublicWorkshopFileType(2))
    }

    @Test
    fun clampWorkshopSubscriptions_caps_large_values() {
        assertEquals(Int.MAX_VALUE, SteamSessionManager.clampWorkshopSubscriptions(Long.MAX_VALUE))
        assertEquals(0, SteamSessionManager.clampWorkshopSubscriptions(-1))
    }
}
