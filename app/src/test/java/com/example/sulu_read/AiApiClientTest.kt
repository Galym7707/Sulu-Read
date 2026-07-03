package com.example.sulu_read

import com.example.sulu_read.data.parseAiGenerateResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiApiClientTest {
    @Test
    fun parsesAiSuccessResponse() {
        val response = parseAiGenerateResponse(
            JSONObject(
                """
                {
                  "success": true,
                  "provider": "gemini",
                  "model": "gemini-3.5-flash",
                  "result": "Readable explanation"
                }
                """.trimIndent()
            )
        )

        assertTrue(response.success)
        assertEquals("gemini", response.provider)
        assertEquals("gemini-3.5-flash", response.model)
        assertEquals("Readable explanation", response.result)
        assertNull(response.error)
    }

    @Test
    fun parsesAiErrorResponse() {
        val response = parseAiGenerateResponse(
            JSONObject(
                """
                {
                  "success": false,
                  "error": "AI help is temporarily unavailable."
                }
                """.trimIndent()
            )
        )

        assertFalse(response.success)
        assertEquals("AI help is temporarily unavailable.", response.error)
        assertNull(response.provider)
        assertNull(response.model)
        assertNull(response.result)
    }

    @Test
    fun providerApiKeysAreNotInAndroidSource() {
        val androidSource = File("src/main/java")
        val sourceText = androidSource.walkTopDown()
            .filter { it.isFile }
            .joinToString(separator = "\n") { it.readText() }

        assertFalse(sourceText.contains("GEMINI_API_KEY"))
        assertFalse(sourceText.contains("GROQ_API_KEY"))
        assertFalse(sourceText.contains("generativelanguage.googleapis.com"))
        assertFalse(sourceText.contains("api.groq.com"))
    }
}
