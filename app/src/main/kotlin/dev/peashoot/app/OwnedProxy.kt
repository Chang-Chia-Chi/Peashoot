package dev.peashoot.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/** Where jpackage puts what the app ships beside itself; unset when the app runs from Gradle. */
private const val PACKAGED_RESOURCES = "compose.application.resources.dir"

/** The fat jar the proxy builds; running it needs no script and leaves no process to chase. */
private const val PROXY_JAR = "peashoot.jar"

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

    /**
     * JVMs the launcher spawned, remembered while they could still be found. A script that has
     * exited, or exec'd away, lists no children at all, so asking only at the end would leave a
     * running proxy behind with nothing naming it.
     */
    private val spawned = ConcurrentHashMap.newKeySet<ProcessHandle>()

    init {
        Runtime.getRuntime().addShutdownHook(hook)
    }

    /** Notes the proxy's own process, once something has confirmed it is up. */
    fun track() {
        spawned += process.descendants().toList()
    }

    override fun close() {
        // The hook would run again at exit against a process that is already gone; dropping it
        // also lets the JVM collect it, since a hook is held until the JVM ends.
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        stop()
    }

    /**
     * Everything this launcher brought up. When the proxy runs from its jar the process here is the
     * JVM itself; when it runs from the install script the JVM is a child of it, and killing only
     * what was started would leave the proxy holding the port.
     */
    private fun stop() {
        val tree = spawned + process.descendants().toList()
        process.destroy()
        tree.forEach(ProcessHandle::destroy)
    }

    companion object {
        /**
         * Starts a proxy against [home] on [port] and hands back the child. The jar is preferred
         * over the install script: it is one process rather than a script wrapping a JVM. Fails
         * naming every place it looked, which is the only useful thing to say to someone whose app
         * found no proxy.
         */
        fun start(home: Path, port: Int): OwnedProxy {
            val jars = candidates(PROXY_JAR)
            val scripts = candidates(LAUNCHER)
            val command =
                jars.firstOrNull(::runnableJar)?.let {
                    listOf(javaCommand(), "-jar", it.toString())
                }
                    ?: scripts.firstOrNull(Files::isRegularFile)?.let { listOf(it.toString()) }
                    ?: error(
                        "no proxy to start; run ./gradlew :proxy:installDist, or start the proxy " +
                            "yourself. Looked in: ${(jars + scripts).joinToString(", ")}"
                    )
            val process =
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(true)
                    .also {
                        it.environment()["PEASHOOT_HOME"] = home.toString()
                        // The port the app will look on, so the child cannot bind a different one.
                        it.environment()["PEASHOOT_PORT"] = port.toString()
                    }
                    .start()
            return OwnedProxy(process)
        }

        /**
         * Where a proxy of the given file name can be, most trustworthy first: the one packaged
         * beside this app, then the development build's output, from the repository root or from
         * `app/`.
         */
        private fun candidates(name: String): List<Path> {
            val packaged = System.getProperty(PACKAGED_RESOURCES)
            val built =
                if (name == LAUNCHER) listOf("build", "install", "proxy", "bin")
                else listOf("build", "libs")
            return listOfNotNull(
                packaged?.let { Path.of(it, "proxy", name) },
                Path.of("proxy", *built.toTypedArray(), name),
                Path.of("..", "proxy", *built.toTypedArray(), name),
            )
        }

        /**
         * A jar only runs with `-jar` if it names a main class; otherwise the script is the way.
         */
        private fun runnableJar(path: Path): Boolean =
            Files.isRegularFile(path) &&
                runCatching {
                        JarFile(path.toFile()).use {
                            it.manifest?.mainAttributes?.getValue("Main-Class") != null
                        }
                    }
                    .getOrDefault(false)

        /** The JVM running this app, which is the one a child proxy should run on too. */
        private fun javaCommand(): String =
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    if (LAUNCHER.endsWith(".bat")) "java.exe" else "java",
                )
                .toString()
    }
}
