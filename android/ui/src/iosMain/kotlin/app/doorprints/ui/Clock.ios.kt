package app.doorprints.ui

import platform.Foundation.NSProcessInfo

// The system uptime; whether it should count sleep on iOS is for the iOS shell (ADR-23 CMP-8). Compile-only for now.
internal actual fun elapsedRealtimeMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
