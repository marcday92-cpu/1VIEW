package com.iptv.tv.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactorTest {
    @Test
    fun queryCredentialsAreRedacted() {
        val out = SecretRedactor.redact("GET http://host/player_api.php?username=marc&password=s3cret&action=x")
        assertFalse("s3cret" in out)
        assertFalse("username=marc" in out)
        assertTrue("action=x" in out)
    }

    @Test
    fun pathCredentialsAreRedactedForEveryStreamKind() {
        for (kind in listOf("live", "movie", "series", "timeshift")) {
            val out = SecretRedactor.redact("http://host/$kind/marc/s3cret/123.ts")
            assertFalse("s3cret" in out, kind)
            assertTrue("/$kind/[redacted]/[redacted]/" in out, kind)
        }
    }

    @Test
    fun describeWalksTheCauseChain() {
        val inner = IllegalStateException("password=abc")
        val outer = RuntimeException("outer", inner)
        val text = SecretRedactor.describe(outer)
        assertEquals("RuntimeException: outer <- IllegalStateException: password=[redacted]", text)
    }
}
