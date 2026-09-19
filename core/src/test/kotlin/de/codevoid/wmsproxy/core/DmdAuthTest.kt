package de.codevoid.wmsproxy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DmdAuthTest {

    @Test
    fun `login body carries only email and password`() {
        val body = DmdAuth.loginBody("rider@example.com", "s3cret")
        assertTrue(body.contains("\"email\":\"rider@example.com\""))
        assertTrue(body.contains("\"password\":\"s3cret\""))
    }

    @Test
    fun `parses token and user from the auth envelope`() {
        val login = DmdAuth.parseLogin(
            """
            {"success":true,"data":{"token":"abc123","refresh_token":"r","user":
            {"id":"1","name":"Rider","email":"rider@example.com"}}}
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("abc123", login.token)
        assertEquals("Rider", login.name)
        assertEquals("rider@example.com", login.email)
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val login = DmdAuth.parseLogin(
            """{"success":true,"future":42,"data":{"token":"t","extra":true,"user":{"name":"R"}}}""",
        ).getOrThrow()
        assertEquals("t", login.token)
        assertEquals("R", login.name)
    }

    @Test
    fun `missing token is a failure carrying the server message`() {
        val error = DmdAuth.parseLogin("""{"success":false,"error":"Invalid credentials"}""")
            .exceptionOrNull()
        assertTrue(error is DmdAuthException)
        assertEquals("Invalid credentials", error?.message)
    }

    @Test
    fun `garbage payload fails rather than throws`() {
        val result = DmdAuth.parseLogin("not json at all")
        assertTrue(result.isFailure)
    }

    @Test
    fun `credentials round-trip through the codec`() {
        val original = DmdCredentials("a@b.c", "pw", "tok", "Name")
        val restored = DmdAuth.decodeCredentials(DmdAuth.encodeCredentials(original))
        assertEquals(original, restored)
    }

    @Test
    fun `unparseable credentials decode to null`() {
        assertNull(DmdAuth.decodeCredentials(""))
        assertNull(DmdAuth.decodeCredentials("{}"))
    }
}
