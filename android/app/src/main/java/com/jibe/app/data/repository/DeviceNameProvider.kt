package com.jibe.app.data.repository

/**
 * Supplies a human-readable device label for auth payloads (e.g. daemon pairing UI).
 *
 * Separated from [ConnectionRepository] so JVM unit tests do not touch Android static fields.
 */
fun interface DeviceNameProvider {
    fun deviceDisplayName(): String
}
