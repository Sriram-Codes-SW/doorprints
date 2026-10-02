package app.doorprints.drive

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** The activity on screen, for the phone's own check (a prompt needs one). Kept weakly; null when the app is in the background. */
object ForegroundActivity : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    val current: Activity? get() = ref?.get()

    fun register(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityResumed(activity: Activity) {
        ref = WeakReference(activity)
        DriveRedirects.resumed()
    }

    override fun onActivityPaused(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
