package dev.peashoot.core

import java.nio.file.Path

/** The control API's bearer token, in the data directory. */
const val TOKEN_FILE = "token"

/**
 * The data directory: `PEASHOOT_HOME`, else `.peashoot` under the user's home. Here rather than in
 * the proxy because the app has to find the same directory to read the same token out of it, and
 * two copies of this rule would be one restart away from disagreeing.
 */
fun homeDir(env: (String) -> String? = System::getenv): Path =
    env("PEASHOOT_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".peashoot")
