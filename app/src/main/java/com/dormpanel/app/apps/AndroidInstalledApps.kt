package com.dormpanel.app.apps

import android.content.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class PreferencesFavoriteStore(context: Context, name: String = "app_favorites") : FavoriteStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun read(): Set<String> = preferences.getStringSet("components", emptySet())!!.toSet()
    override fun write(components: Set<String>) { preferences.edit().putStringSet("components", components.toSet()).apply() }
}

/** Owned by the retained DashboardViewModel. All delivery is on main; no polling. */
class AndroidInstalledApps(context: Context,
    private val discovery: (() -> List<InstalledApp>)? = null,
    launcher: ((String) -> Boolean)? = null,
    favorites: FavoriteStore = PreferencesFavoriteStore(context),
    settingsLauncher: ((String) -> Boolean)? = null,
) : InstalledAppSource {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var closed = false
    private var running = false
    private var pending = false
    var iconRevision = 0; private set
    private val state = AppState(ComponentName(appContext, com.dormpanel.app.MainActivity::class.java).flattenToString(),
        favorites, settingsLauncher = settingsLauncher ?: { packageName ->
            // Check for removal since discovery before asking system Settings to open details.
            runCatching {
                pm.getApplicationInfo(packageName, 0)
                appContext.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }, launcher = launcher ?: { identity ->
        val component = ComponentName.unflattenFromString(identity)
        if (component == null) false else try {
            appContext.startActivity(Intent.makeMainActivity(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    })
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    val icons = AppIcons(appContext)
    private val refreshTask = Runnable { enumerate() }
    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }
    override val apps get() = state.apps
    override fun isFavorite(component: String) = state.isFavorite(component)
    override fun toggleFavorite(component: String) = state.toggleFavorite(component)
    override fun addListener(listener: (List<InstalledApp>) -> Unit) = state.addListener(listener)
    override fun removeListener(listener: (List<InstalledApp>) -> Unit) = state.removeListener(listener)
    override fun launch(component: String): Boolean = state.launch(component).also { if (!it) refresh() }
    override fun openAppSettings(component: String): Boolean = state.openAppSettings(component).also { if (!it) refresh() }
    override fun refresh() {
        if (closed) return
        main.removeCallbacks(refreshTask)
        main.postDelayed(refreshTask, 180)
    }
    private fun enumerate() {
        if (closed) return
        if (running) { pending = true; return }
        running = true
        worker.execute {
            val result = runCatching {
                discovery?.invoke() ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { info ->
                        val activity = info.activityInfo
                        InstalledApp(ComponentName(activity.packageName, activity.name).flattenToString(),
                            runCatching { info.loadLabel(pm).toString() }.getOrDefault(activity.name), activity.packageName)
                    }
            }
            main.post {
                running = false
                if (!closed) {
                    icons.invalidate(); iconRevision++
                    result.onSuccess { apps -> state.update(apps.map { it.copy(iconRevision = iconRevision) }) }
                    if (pending) { pending = false; refresh() }
                }
            }
        }
    }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacksAndMessages(null); appContext.unregisterReceiver(receiver)
        worker.shutdownNow(); icons.close(); state.close()
    }
}

/** Visible targets only. Weak targets + tokens protect both recycling and detached views. */
class AppIcons(context: Context) {
    private val pm = context.applicationContext.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(128), java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    private val cache = LruCache<String, Drawable.ConstantState>(32)
    private val requests = java.util.WeakHashMap<ImageView, Runnable>()
    private var generation = 0
    private var closed = false
    fun bind(target: ImageView, identity: String) {
        cancel(target)
        val token = Any(); target.tag = token
        target.setImageResource(android.R.drawable.sym_def_app_icon)
        cache.get(identity)?.let { target.setImageDrawable(it.newDrawable(target.resources)); return }
        if (closed || identity.isEmpty()) return
        val reference = java.lang.ref.WeakReference(target)
        val revision = generation
        val request = Runnable {
            val drawable = runCatching { ComponentName.unflattenFromString(identity)?.let(pm::getActivityIcon) }.getOrNull()
            main.post {
                if (!closed && revision == generation) {
                    drawable?.constantState?.let { cache.put(identity, it) }
                    reference.get()?.takeIf { it.tag === token }?.let { view ->
                        requests.remove(view)
                        if (drawable != null && view.isAttachedToWindow) view.setImageDrawable(drawable)
                    }
                }
            }
        }
        requests[target] = request
        worker.execute(request)
    }
    fun cancel(target: ImageView) { target.tag = null; requests.remove(target)?.let(worker::remove) }
    fun invalidate() { generation++; cache.evictAll(); worker.queue.clear(); requests.clear() }
    fun close() { closed = true; invalidate(); worker.shutdownNow(); main.removeCallbacksAndMessages(null) }
}

/** Test source injection follows the isolated DashboardStores fixture pattern. */
object AppSources {
    var overrideFactory: ((Context) -> AndroidInstalledApps)? = null
    fun create(context: Context) = overrideFactory?.invoke(context) ?: AndroidInstalledApps(context)
}
