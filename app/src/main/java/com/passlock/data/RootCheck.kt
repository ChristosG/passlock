package com.passlock.data

import android.os.Build
import java.io.File

/**
 * Best-effort detection of a rooted / tampered device. This is advisory only — a
 * determined attacker can defeat any on-device check — so PassLock warns rather than
 * blocks, leaving the decision to the user.
 */
object RootCheck {
    private val SUSPECT_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/app/Superuser.apk", "/data/local/su", "/data/local/bin/su",
        "/data/local/xbin/su", "/system/bin/magisk", "/sbin/magisk",
        "/data/adb/magisk", "/data/adb/modules",
    )

    fun isLikelyRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        return SUSPECT_PATHS.any { path -> runCatching { File(path).exists() }.getOrDefault(false) }
    }
}
