package dev.peashoot.app

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The control plane's pure halves: what an answer from the proxy reads as, and when a draft may be
 * saved. No window and no socket, which is where #23's rules live so that they can be asserted
 * rather than inferred from the state of a button.
 */
class ControlReadingTest {
    @Test
    fun `a draft is saved only once it is tested, and a risky one only when it is confirmed`() {
        // Nothing tested: not offered, whatever else is true.
        assertEquals("test this draft before saving it", saveBlocked("{}", null, false, false))
        // Tested, but the box has moved on since.
        assertEquals("test this draft before saving it", saveBlocked("{}", "{ }", true, true))
        // Tested and clean: the first press saves it, and a confirmation is not asked for.
        assertNull(saveBlocked("{}", "{}", risky = false, confirming = false))
        // Tested and risky: once to arm, once to mean it.
        assertContains(
            saveBlocked("{}", "{}", risky = true, confirming = false).orEmpty(),
            "press save again",
        )
        assertNull(saveBlocked("{}", "{}", risky = true, confirming = true))
    }

    @Test
    fun `a rule test reads as lines, and an answer nobody can read is not a clean test`() {
        val clean = checkNotNull(testLines("""{"tested":0,"collisions":[],"splits":[]}"""))
        assertEquals(
            listOf("0 recordings tested", "nothing would collide and nothing would split"),
            clean.lines,
        )
        assertFalse(clean.risky)

        val collided =
            checkNotNull(
                testLines(
                    """{"tested":3,"collisions":[{"fingerprint":"abcdef0123456789",""" +
                        """"exchangeIds":["a","b"]}],"splits":[]}"""
                )
            )
        assertEquals(listOf("3 recordings tested", "collision abcdef012345: a, b"), collided.lines)
        assertTrue(collided.risky)

        val split =
            checkNotNull(
                testLines(
                    """{"tested":1,"collisions":[],"splits":""" +
                        """[{"fingerprint":"ff00","groups":[["a"],["b","c"]]}]}"""
                )
            )
        assertEquals(listOf("1 recordings tested", "split ff00: a | b, c"), split.lines)
        assertTrue(split.risky)

        // Unreadable is not clean: a null here is what keeps save unoffered.
        assertNull(testLines("not json at all"))
        assertNull(testLines("""{"collisions":[],"splits":[]}"""), "no count is no answer")
    }

    @Test
    fun `an export answer says how much, what was stripped, and where it went`() {
        val dry =
            checkNotNull(
                exportLines(
                    """{"name":"demo","dryRun":true,"path":null,"exchanges":2,"redactions":1,""" +
                        """"preview":[{"exchangeId":"x","where":"request body",""" +
                        """"matched":"sk-not... (28 chars)","becomes":"[REDACTED]"}]}"""
                )
            )
        assertEquals(
            listOf(
                "2 exchanges, 1 redactions",
                "request body: sk-not... (28 chars) becomes [REDACTED]",
            ),
            dry.lines,
        )
        assertNull(dry.path, "a dry run wrote nothing to name")

        val written =
            checkNotNull(
                exportLines(
                    """{"name":"demo","dryRun":false,"path":"/tmp/demo.jsonl","exchanges":0,""" +
                        """"redactions":0,"preview":[]}"""
                )
            )
        assertEquals("nothing in them matches a redaction rule", written.lines[1])
        assertEquals("/tmp/demo.jsonl", written.path)
        assertContains(written.lines, "written to /tmp/demo.jsonl")

        assertNull(exportLines("""{"exchanges":1}"""), "half an answer is no answer")
    }

    @Test
    fun `routes read as the proxy listed them, and an unreadable answer is not an empty table`() {
        val read =
            checkNotNull(
                routesOf("""{"default":{"mode":"record","strict":false,"cassette":null}}""")
            )
        assertEquals(listOf(RouteRow("default", "record", false, null)), read)
        assertEquals(
            RouteRow("default", "replay", true, "demo"),
            routeOf("default", """{"mode":"replay","strict":true,"cassette":"demo"}"""),
        )
        // Empty is a proxy with no routes; null is an answer this window could not read, and the
        // two must not be drawn as the same thing.
        assertEquals(emptyList(), routesOf("{}"))
        assertNull(routesOf("nonsense"))
        assertNull(routesOf("""{"default":{"strict":true}}"""), "a route says its mode")
        assertNull(routeOf("default", "[]"))
    }

    @Test
    fun `a refusal reaches the user in the proxy's words, and an unreachable proxy says so`() {
        assertEquals(
            "rules.json: no such rule: replacements",
            problemDetail(
                """{"type":"about:blank","title":"Bad Request",""" +
                    """"detail":"rules.json: no such rule: replacements","status":400}"""
            ),
        )
        // Something that is not a problem object is still shown, and an empty answer says so.
        assertEquals("<html>gateway</html>", problemDetail("<html>gateway</html>"))
        assertEquals("the proxy gave no reason", problemDetail(""))

        assertEquals(
            "the proxy refused to save the rules (400): rules.json: replace[0] must be an object",
            whyNot("save the rules", Refused(400, "rules.json: replace[0] must be an object")),
        )
        assertEquals(
            "the proxy could not be reached to save the rules: connection refused",
            whyNot("save the rules", IOException("connection refused")),
        )
    }
}
