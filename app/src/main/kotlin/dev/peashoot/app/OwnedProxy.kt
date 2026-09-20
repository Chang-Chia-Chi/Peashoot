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
internal val LAUNCHER =
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
                proxyCommand(javaCommand(), jars, scripts)
                    ?: error(
                        "no proxy to start; run ./gradlew :proxy:fatJar, or start the proxy " +
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
         * What to run, given where a jar and a launcher could be: a runnable jar first, because it
         * is one process rather than a script wrapping a JVM, and the first of each list that is
         * really there. Null when none of them is, which is what [start] turns into its message.
         */
        internal fun proxyCommand(
            java: String,
            jars: List<Path>,
            scripts: List<Path>,
        ): List<String>? =
            jars.firstOrNull(::runnableJar)?.let { listOf(java, "-jar", it.toString()) }
                ?: scripts.firstOrNull(Files::isRegularFile)?.let { listOf(it.toString()) }

        /**
         * Where a proxy of the given file name can be, most trustworthy first.
         *
         * An installed app looks only where it packed one. The development paths below are relative
         * to the working directory, which for an installed app is whatever happened to launch it —
         * a desktop shortcut, a shell, a file manager — and a `proxy/build/libs/peashoot.jar`
         * planted under any of those by someone who could write there would then be run with this
         * app's privileges. From Gradle there is no packaged copy and those paths are the build's
         * own output, reached from the repository root or from `app/`.
         */
        internal fun candidates(
            name: String,
            packaged: String? = System.getProperty(PACKAGED_RESOURCES),
        ): List<Path> {
            if (packaged != null) return listOf(Path.of(packaged, "proxy", name))
            val built =
                if (name == LAUNCHER) listOf("build", "install", "proxy", "bin")
                else listOf("build", "libs")
            return listOf(
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
