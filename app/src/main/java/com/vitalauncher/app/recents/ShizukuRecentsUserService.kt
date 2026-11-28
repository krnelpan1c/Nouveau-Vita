package com.vitalauncher.app.recents

import android.content.Context
import android.content.Intent
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Lightweight Shizuku UserService running with shell (UID 2000) privileges to inspect Android's
 * live Recents task list via `dumpsys activity recents`. Only ever bound if the user has already
 * authorized this app in Shizuku Manager — no runtime prompt is ever shown.
 */
@Keep
class ShizukuRecentsUserService : IRecentsService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun getRecentPackages(maxCount: Int): List<String> {
        val limit = maxCount.coerceIn(1, 32)
        var process: Process? = null
        return try {
            process = ProcessBuilder("dumpsys", "activity", "recents")
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
            process.waitFor()
            parseRecentPackages(output, limit)
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { process?.destroy() }
        }
    }

    companion object {
        private val RECENT_HEADER_REGEX = Regex("""^\s*\*\s*Recent\s+#\d+:\s*(.+)$""")
        private val ACTIVITY_COMPONENT_REGEX = Regex("""(?:mActivityComponent|realActivity)=([A-Za-z0-9_.]+)/""")
        private val CMP_REGEX = Regex("""\bcmp=([A-Za-z0-9_.]+)/""")
        private val INTENT_COMP_REGEX = Regex("""\bI=([A-Za-z0-9_.]+)/""")
        private val AFFINITY_REGEX = Regex("""\bA=(?:\d+:)?([A-Za-z0-9_.]+)""")
        private val INTENT_FLAGS_REGEX = Regex("""\bflg=0x([0-9a-fA-F]+)""")

        /**
         * Parses `dumpsys activity recents` output and returns package names in MRU order from the
         * live `Recent tasks:` section. Tasks swiped away or cleared from Android Recents no longer
         * appear in this section, and tasks marked `inRecents=false` or carrying
         * `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` are ignored.
         */
        internal fun parseRecentPackages(dump: String, maxCount: Int): List<String> {
            val result = LinkedHashSet<String>()
            val lines = dump.lineSequence().iterator()

            var inRecentSection = false
            var currentHeader: String? = null
            var currentDetailPkg: String? = null
            var currentSkip = false

            fun flushCurrent() {
                val header = currentHeader ?: return
                if (!currentSkip) {
                    val pkg = currentDetailPkg ?: extractPackageFromHeader(header)
                    if (!pkg.isNullOrEmpty()) {
                        result.add(pkg)
                    }
                }
                currentHeader = null
                currentDetailPkg = null
                currentSkip = false
            }

            while (lines.hasNext() && result.size < maxCount) {
                val line = lines.next()
                val trimmed = line.trim()

                if (!inRecentSection) {
                    if (trimmed.equals("Recent tasks:", ignoreCase = true) ||
                        trimmed.startsWith("Recent tasks:", ignoreCase = true)
                    ) {
                        inRecentSection = true
                    }
                    continue
                }

                // A new top-level section ends the live "Recent tasks:" block.
                if (trimmed.isEmpty() ||
                    trimmed.startsWith("Visible recent tasks", ignoreCase = true) ||
                    trimmed.startsWith("Raw recent tasks", ignoreCase = true) ||
                    trimmed.startsWith("Persisted", ignoreCase = true)
                ) {
                    flushCurrent()
                    if (result.isNotEmpty()) break
                    continue
                }

                val headerMatch = RECENT_HEADER_REGEX.find(line)
                if (headerMatch != null) {
                    flushCurrent()
                    if (result.size >= maxCount) break
                    val header = headerMatch.groupValues[1]
                    currentHeader = header
                    currentDetailPkg = null
                    currentSkip = shouldSkipTaskHeader(header)
                    continue
                }

                if (currentHeader != null && !currentSkip) {
                    if (trimmed.contains("inRecents=false")) {
                        currentSkip = true
                        continue
                    }
                    if (trimmed.startsWith("intent=")) {
                        val flagsHex = INTENT_FLAGS_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
                        val flags = flagsHex?.toLongOrNull(16) ?: 0L
                        if ((flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.toLong()) != 0L) {
                            currentSkip = true
                            continue
                        }
                    }
                    if (currentDetailPkg == null) {
                        val compMatch = ACTIVITY_COMPONENT_REGEX.find(line)
                        if (compMatch != null) {
                            currentDetailPkg = compMatch.groupValues[1]
                        } else {
                            val cmpMatch = CMP_REGEX.find(line)
                            if (cmpMatch != null) {
                                currentDetailPkg = cmpMatch.groupValues[1]
                            }
                        }
                    }
                }
            }
            flushCurrent()
            return result.take(maxCount)
        }

        private fun shouldSkipTaskHeader(header: String): Boolean {
            return header.contains("type=home") ||
                header.contains("type=recents") ||
                header.contains("type=assistant") ||
                header.contains("type=dream")
        }

        private fun extractPackageFromHeader(header: String): String? {
            INTENT_COMP_REGEX.find(header)?.groupValues?.getOrNull(1)?.let { return it }
            ACTIVITY_COMPONENT_REGEX.find(header)?.groupValues?.getOrNull(1)?.let { return it }
            CMP_REGEX.find(header)?.groupValues?.getOrNull(1)?.let { return it }
            AFFINITY_REGEX.find(header)?.groupValues?.getOrNull(1)?.let { return it }
            return null
        }
    }
}
