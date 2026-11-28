package com.vitalauncher.app.recents

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Silent, zero-prompt Shizuku client for querying live Android Recents tasks.
 *
 * Never triggers a permission dialog: if Shizuku is not installed, not running, or has not already
 * been authorized for this app in Shizuku Manager, [queryRecentPackages] simply returns `null` so
 * the caller falls back to launcher-tracked recents.
 */
object ShizukuRecentsClient {

    @Volatile
    private var service: IRecentsService? = null

    @Volatile
    private var pendingConnect: CompletableDeferred<IRecentsService?>? = null

    @Volatile
    private var listenersRegistered = false

    private val lock = Any()
    private var boundArgs: Shizuku.UserServiceArgs? = null

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        clearService()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val iface = if (binder != null && binder.pingBinder()) {
                IRecentsService.Stub.asInterface(binder)
            } else {
                null
            }
            synchronized(lock) {
                service = iface
                pendingConnect?.complete(iface)
                pendingConnect = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearService()
        }
    }

    private fun ensureListenersRegistered() {
        if (listenersRegistered) return
        synchronized(lock) {
            if (listenersRegistered) return
            runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
            listenersRegistered = true
        }
    }

    private fun clearService() {
        synchronized(lock) {
            service = null
            pendingConnect?.complete(null)
            pendingConnect = null
        }
    }

    /** True only when Shizuku's binder is live AND the user has already granted access. */
    fun isAuthorized(): Boolean {
        return try {
            ensureListenersRegistered()
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Queries live recent package names (MRU order) via Shizuku, or returns `null` if Shizuku is
     * unavailable/unauthorized or the query fails. An empty list means Shizuku succeeded and there
     * are genuinely zero recent tasks (e.g., after "Clear All").
     */
    suspend fun queryRecentPackages(context: Context, maxCount: Int = 20): List<String>? =
        withContext(Dispatchers.IO) {
            if (!isAuthorized()) return@withContext null
            val svc = getOrBindService(context.applicationContext) ?: return@withContext null
            try {
                svc.getRecentPackages(maxCount)?.toList()
            } catch (_: Throwable) {
                clearService()
                null
            }
        }

    private suspend fun getOrBindService(appContext: Context): IRecentsService? {
        service?.let { existing ->
            if (runCatching { existing.asBinder().pingBinder() }.getOrDefault(false)) {
                return existing
            }
            clearService()
        }

        val deferred: CompletableDeferred<IRecentsService?>
        val shouldBind: Boolean
        synchronized(lock) {
            service?.let { return it }
            val existingPending = pendingConnect
            if (existingPending != null) {
                deferred = existingPending
                shouldBind = false
            } else {
                deferred = CompletableDeferred()
                pendingConnect = deferred
                shouldBind = true
            }
        }

        if (shouldBind) {
            try {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(appContext.packageName, ShizukuRecentsUserService::class.java.name)
                )
                    .daemon(false)
                    .processNameSuffix("recents")
                    .debuggable(false)
                    .version(1)
                boundArgs = args
                Shizuku.bindUserService(args, connection)
            } catch (_: Throwable) {
                clearService()
                return null
            }
        }

        return withTimeoutOrNull(1500L) { deferred.await() }
    }

    fun release() {
        val args = synchronized(lock) {
            val a = boundArgs
            boundArgs = null
            service = null
            pendingConnect?.complete(null)
            pendingConnect = null
            a
        }
        if (args != null) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
        if (listenersRegistered) {
            runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
            listenersRegistered = false
        }
    }
}

