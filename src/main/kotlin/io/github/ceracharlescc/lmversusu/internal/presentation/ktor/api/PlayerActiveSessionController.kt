package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.github.ceracharlescc.lmversusu.internal.infrastructure.game.ActiveSessionSnapshot
import io.github.ceracharlescc.lmversusu.internal.infrastructure.game.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class PlayerActiveSessionController @Inject constructor(
    private val sessionManager: SessionManager,
) {
    fun getActiveSession(
        playerId: Uuid,
        activeSessionIdHint: Uuid?,
    ): ActiveSessionSnapshot? {
        return sessionManager.getActiveSession(playerId, activeSessionIdHint)
    }

    fun terminateActiveSession(playerId: Uuid): Uuid? {
        return sessionManager.terminateActiveSessionByOwner(playerId)
    }
}
