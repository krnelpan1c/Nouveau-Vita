package com.vitalauncher.app

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses Android 12+ ADPF ([PerformanceHintManager]) to keep both the UI thread and HWUI's
 * `RenderThread` scheduled on big/prime cores during app-return resume and 120 Hz pager swipes.
 *
 * Without an explicit hint session, returning from a foreground app can leave `RenderThread`
 * stranded on a little efficiency core (since Qualcomm's `SilkyScrolls` scroll-boost heuristic only
 * detects classic `View` scrollers, not Compose `VerticalPager`).
 */
object PerformanceBooster {

    // 6.0 ms target gives comfortable headroom inside a 120 Hz (8.33 ms) VSync period.
    private const val TARGET_WORK_DURATION_NS = 6_000_000L
    private const val RESUME_REPORT_DURATION_NS = 7_500_000L
    private const val SCROLL_REPORT_DURATION_NS = 6_500_000L

    private val initializing = AtomicBoolean(false)

    @Volatile
    private var hintSession: PerformanceHintManager.Session? = null

    fun ensureInitialized(context: Context, uiTid: Int = Process.myTid()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (hintSession != null || !initializing.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        Thread {
            try {
                val phm = appContext.getSystemService(PerformanceHintManager::class.java)
                if (phm != null) {
                    val renderTid = findThreadTidByName("RenderThread")
                    val primaryTids = if (renderTid > 0 && renderTid != uiTid) {
                        intArrayOf(uiTid, renderTid)
                    } else {
                        intArrayOf(uiTid)
                    }
                    val created = runCatching {
                        phm.createHintSession(primaryTids, TARGET_WORK_DURATION_NS)
                    }.getOrNull() ?: runCatching {
                        phm.createHintSession(intArrayOf(uiTid), TARGET_WORK_DURATION_NS)
                    }.getOrNull()

                    hintSession = created
                    created?.reportActualWorkDuration(RESUME_REPORT_DURATION_NS)
                }
            } catch (_: Throwable) {
                // Best-effort hint only; devices without ADPF support simply ignore it.
            } finally {
                if (hintSession == null) initializing.set(false)
            }
        }.apply {
            name = "VitaPerfBoosterInit"
            isDaemon = true
            start()
        }
    }

    /** Signals active frame work as the launcher resumes from a backgrounded state. */
    fun boostForResume() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            hintSession?.reportActualWorkDuration(RESUME_REPORT_DURATION_NS)
        } catch (_: Throwable) {
        }
    }

    /** Signals ongoing 120 Hz scroll work while a pager gesture/fling is active. */
    fun boostScrollTick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            hintSession?.reportActualWorkDuration(SCROLL_REPORT_DURATION_NS)
        } catch (_: Throwable) {
        }
    }

    fun close() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val current = hintSession
        hintSession = null
        initializing.set(false)
        try {
            current?.close()
        } catch (_: Throwable) {
        }
    }

    private fun findThreadTidByName(targetName: String): Int {
        val taskDir = File("/proc/self/task")
        val entries = taskDir.listFiles() ?: return -1
        for (entry in entries) {
            val tid = entry.name.toIntOrNull() ?: continue
            val comm = runCatching { File(entry, "comm").readText().trim() }.getOrNull()
            if (comm == targetName) return tid
        }
        return -1
    }
}

