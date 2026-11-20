package dev.cosmo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Single entry point for every root command. Callers pass hardcoded strings (+ regex-validated package names) only. */
object RootShell {
    suspend fun exec(cmd: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor() }
        }.getOrDefault("")
    }
}
