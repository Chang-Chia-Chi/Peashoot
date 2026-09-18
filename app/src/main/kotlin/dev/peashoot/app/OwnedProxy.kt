package dev.peashoot.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

/** Where jpackage puts what the app ships beside itself; unset when the app runs from Gradle. */
private const val PACKAGED_RESOURCES = "compose.application.resources.dir"

/** The launcher the proxy's `installDist` writes, under whichever name this OS runs. */
private val LAUNCHER =
    if (System.getProperty("os.name").startsWith("Windows")) "proxy.bat" else "proxy"

/**
 * A proxy this app started, and only ever one it started: a proxy that was already answering on the
 * port belongs to whoever ran it, and outlives the window. Closing stops the child, and so does the
 * shutdown hook, because a JVM leaving does not take its children with it.
 */
class OwnedProxy private constructor(private val process: Process) : AutoCloseable {
    private val hook = Thread(::stop)

    init {
        Runtime.getRuntime().addShutdownHook(hook)
    }

    override fun close() {
        // The hook would run again at exit against a process that is already gone; dropping it
        // also lets the JVM collect it, since a hook is held until the JVM ends.
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        stop()
    }

    /**
     * The launcher is a shell script, so the JVM doing the serving is its child, not the process
     * started here: killing only what was started leaves the proxy holding the port. The children
     * are listed before the parent dies, because a dead parent lists none.
     */
    private fun stop() {
        val descendants = process.descendants().toList()
        process.destroy()
        descendants.forEach(ProcessHandle::destroy)
    }

    companion object {
        /**
         * Starts the proxy against [home] and hands back the child. Fails naming every place it
         * looked, which is the only useful thing to say to someone whose app found no proxy.
         */
        fun start(home: Path): OwnedProxy {
            val candidates = launcherCandidates()
            val launcher =
                candidates.firstOrNull { Files.isRegularFile(it) }
                    ?: error(
                        "no proxy launcher found; run ./gradlew :proxy:installDist, or start the " +
                            "proxy yourself. Looked in: ${candidates.joinToString(", ")}"
                    )
            val process =
                ProcessBuilder(launcher.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(true)
                    .also { it.environment()["PEASHOOT_HOME"] = home.toString() }
                    .start()
            return OwnedProxy(process)
        }

        /**
         * Where a proxy launcher can be, most trustworthy first: the one packaged beside this app,
         * then the development build's install output, from the repository root or from `app/`.
         */
        internal fun launcherCandidates(): List<Path> =
            listOfNotNull(
                System.getProperty(PACKAGED_RESOURCES)?.let {
                    Path.of(it, "proxy", "bin", LAUNCHER)
                },
                Path.of("proxy", "build", "install", "proxy", "bin", LAUNCHER),
                Path.of("..", "proxy", "build", "install", "proxy", "bin", LAUNCHER),
            )
    }
}
