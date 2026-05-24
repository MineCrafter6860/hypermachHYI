package com.kerem.hyi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun testIsNewerVersion() {
        // Newer versions
        assertTrue(UpdateChecker.isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.2.1", "1.2.0"))
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewerVersion("v2.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewerVersion("1.10.0", "1.2.0"))
        
        // Same or older versions
        assertFalse(UpdateChecker.isNewerVersion("1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.1.0", "1.2.0"))
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "v1.1.0"))
        
        // Edge cases
        assertTrue(UpdateChecker.isNewerVersion("1.1", "1.0.9"))
        assertFalse(UpdateChecker.isNewerVersion("1.0", "1.0.1"))
    }
}
