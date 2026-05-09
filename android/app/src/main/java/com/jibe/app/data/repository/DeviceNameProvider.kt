package com.jibe.app.data.repository

/** Fallback when [android.os.Build.MODEL] is null or blank. */
const val DEFAULT_DEVICE_DISPLAY_NAME = "Android"

/**
 * Supplies a human-readable device label for auth payloads (e.g. daemon pairing UI).
 *
 * Separated from [ConnectionRepository] so JVM unit tests do not touch Android static fields.
 */
fun interface DeviceNameProvider {
    fun deviceDisplayName(): String
}
