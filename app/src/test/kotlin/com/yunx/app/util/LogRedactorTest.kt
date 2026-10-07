package com.yunx.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {
    @Test
    fun removesPathQueryFragmentAndUserInfo() {
        val redacted = LogRedactor.url("https://user:pass@cdn.example:8443/private/a?sign=secret#token")
        assertEquals("https://cdn.example:8443", redacted)
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("private"))
        assertFalse(redacted.contains("user"))
    }

    @Test
    fun handlesInvalidAndRelativeValues() {
        assertEquals("<relative-url>", LogRedactor.url("segment.ts?token=secret"))
        assertEquals("<none>", LogRedactor.url(null))
    }

    @Test
    fun redactsUrlsAndKnownSecretAssignmentsFromExportedLines() {
        val line = LogRedactor.line(
            "download https://cdn.example/private.bin?sign=url-secret Cookie=session-secret access_token=jwt-secret"
        )
        assertFalse(line.contains("url-secret"))
        assertFalse(line.contains("session-secret"))
        assertFalse(line.contains("jwt-secret"))
        assertEquals("download https://cdn.example Cookie=<redacted> access_token=<redacted>", line)
    }
    @Test
    fun hidesJsonPasswordsAndEntireCookieAndBearerValues() {
        val json = LogRedactor.line("""{"password":"secret phrase","appToken":"token-value","refresh_token":"refresh-value"}""")
        for (secret in listOf("secret phrase","token-value","refresh-value")) assertFalse(json.contains(secret))
        val headers = LogRedactor.line("Cookie: sid=one; session=two | Authorization: Bearer three")
        for (secret in listOf("one","two","three")) assertFalse(headers.contains(secret))
    }
}
