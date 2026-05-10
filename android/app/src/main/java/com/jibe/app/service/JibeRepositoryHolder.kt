package com.jibe.app.service

import com.jibe.app.data.repository.ConnectionRepository

/**
 * Holds the active [ConnectionRepository] for components that cannot receive constructor
 * injection (e.g. [JibeNotificationService]).
 */
object JibeRepositoryHolder {
    @Volatile var connectionRepository: ConnectionRepository? = null
}
