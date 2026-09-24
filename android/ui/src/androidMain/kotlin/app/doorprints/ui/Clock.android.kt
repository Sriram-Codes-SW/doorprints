package app.doorprints.ui

import android.os.SystemClock

internal actual fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
