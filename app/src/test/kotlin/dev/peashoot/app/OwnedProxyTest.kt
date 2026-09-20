package dev.peashoot.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which proxy a window starts, given what there is to start. Everything #28 had to get right for an
 * installed app is in this choice: the jar the app packed, run on the app's own runtime, and never
 * one that the working directory — whatever launched the app — happened to point at.
 */
class OwnedProxyTest {
    private val dir = Files.createTempDirectory("peashoot-lookup")
    private val java =
        Path.of("C:", "Program Files", "Peashoot", "runtime", "bin", "java").toString()

    @Test
    fun `a runnable jar is preferred to the launcher script, and runs on the given runtime`() {
        val jar = jar("peashoot.jar", runnable = true)
        val script = plainFile(LAUNCHER)
        assertEquals(
            listOf(java, "-jar", jar.toString()),
            OwnedProxy.proxyCommand(java, listOf(jar), listOf(script)),
        )
    }

    @Test
    fun `a jar that is not there is passed over for one that is`() {
        val missing = dir.resolve("packaged.jar")
        val built = jar("peashoot.jar", runnable = true)
        assertEquals(
            listOf(java, "-jar", built.toString()),
            OwnedProxy.proxyCommand(java, listOf(missing, built), emptyList()),
        )
    }

    @Test
    fun `a jar naming no main class falls through to the script, which needs no runtime`() {
        val jar = jar("peashoot.jar", runnable = false)
        val script = plainFile(LAUNCHER)
        assertEquals(
            listOf(script.toString()),
            OwnedProxy.proxyCommand(java, listOf(jar), listOf(script)),
        )
    }

    @Test
    fun `nothing to run is nothing to start, and the window is told rather than left waiting`() {
        val missing = dir.resolve("peashoot.jar")
        assertNull(OwnedProxy.proxyCommand(java, listOf(missing), listOf(dir.resolve(LAUNCHER))))
    }

    @Test
    fun `a packaged app looks only where it packed a proxy`() {
        val resources = Path.of("C:", "Program Files", "Peashoot", "app", "resources").toString()
        assertEquals(
            listOf(Path.of(resources, "proxy", "peashoot.jar")),
            OwnedProxy.candidates("peashoot.jar", resources),
        )
    }

    @Test
    fun `a build looks at its own output, from the repository root and from app`() {
        assertEquals(
            listOf(
                Path.of("proxy", "build", "libs", "peashoot.jar"),
                Path.of("..", "proxy", "build", "libs", "peashoot.jar"),
            ),
            OwnedProxy.candidates("peashoot.jar", packaged = null),
        )
        assertEquals(
            listOf(
                Path.of("proxy", "build", "install", "proxy", "bin", LAUNCHER),
                Path.of("..", "proxy", "build", "install", "proxy", "bin", LAUNCHER),
            ),
            OwnedProxy.candidates(LAUNCHER, packaged = null),
        )
    }

    /** An empty jar, with a manifest that either names a main class or does not. */
    private fun jar(name: String, runnable: Boolean): Path {
        val path = dir.resolve(name)
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                if (runnable) {
                    mainAttributes[Attributes.Name.MAIN_CLASS] = "dev.peashoot.proxy.MainKt"
                }
            }
        JarOutputStream(Files.newOutputStream(path), manifest).close()
        return path
    }

    private fun plainFile(name: String): Path = Files.createFile(dir.resolve(name))
}
