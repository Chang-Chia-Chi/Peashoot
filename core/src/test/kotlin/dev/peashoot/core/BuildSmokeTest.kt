package dev.peashoot.core

import kotlin.test.Test
import kotlin.test.assertTrue

/** Proves the test pipeline runs on the JVM the build targets. Replaced by real tests in #5. */
class BuildSmokeTest {
    @Test
    fun `tests run on a jvm that satisfies the 21 target`() {
        assertTrue(Runtime.version().feature() >= 21, "expected JVM 21+, got ${Runtime.version()}")
    }
}
