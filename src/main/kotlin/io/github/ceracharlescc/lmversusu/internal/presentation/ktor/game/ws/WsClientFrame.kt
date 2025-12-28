package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface WsClientFrame

@Serializable
@SerialName("join_session")
internal data class WsJoinSession(
    val sessionId: String? = null,
    val opponentSpecId: String,
    val nickname: String,
    val locale: String? = null,
) : WsClientFrame

@Serializable
@SerialName("start_round_request")
internal data class WsStartRoundRequest(
    val sessionId: String,
    val playerId: String,
) : WsClientFrame

@Serializable
@SerialName("submit_answer")
internal data class WsSubmitAnswer(
    val sessionId: String,
    val playerId: String,
    val roundId: String,
    val nonceToken: String,
    val answer: Answer,
    val clientSentAtEpochMs: Long? = null,
) : WsClientFrame

@Serializable
@SerialName("ping")
internal data class WsPing(
    val sessionId: String? = null,
    val sentAtEpochMs: Long? = null,
) : WsClientFrame