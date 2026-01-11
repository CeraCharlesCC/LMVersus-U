import { GamePhase, EffectType } from "./gameState.types.js";
import { defaultRoundState, defaultLlmState } from "./gameState.initial.js";

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

const resetForLobby = (state) => ({
    ...state,
    phase: GamePhase.LOBBY,
    screen: "LOBBY",
    session: {
        ...state.session,
        id: null,
        opponentSpecId: null,
        opponentDisplayName: null,
        opponentQuestionSetDisplayName: null,
        opponentQuestionSetDescription: null,
        opponentQuestionSetDescriptionI18nKey: null,
        opponentDifficulty: null,
        players: { human: null, llm: null },
        ended: false,
    },
    round: defaultRoundState(),
    llm: defaultLlmState(),
    ui: {
        ...state.ui,
        roundStartPending: false,
        vsTransitionPending: false,
        vsTransitionPlaying: false,
        autoStartRound: false,
        roundDisplayHidden: false,
        matchEnd: null,
        lobbyTab: state.ui.lobbyTab || "LIGHTWEIGHT",
        topTab: "question",
        reasoningPeeked: false,
    },
});

const resetRound = (state) => ({
    ...state,
    round: defaultRoundState(),
    llm: defaultLlmState(),
    ui: {
        ...state.ui,
        topTab: "question",
        reasoningPeeked: false,
    },
});

// ─────────────────────────────────────────────────────────────────────────────
// Per-event handler functions
// ─────────────────────────────────────────────────────────────────────────────

function onSessionError(state, msg) {
    return {
        state: {
            ...state,
            connection: { ...state.connection, netOk: false },
            ui: { ...state.ui, vsTransitionPending: false, vsTransitionPlaying: false },
        },
        effects: [
            { type: EffectType.TOAST, payload: { key: "session_error", code: msg.errorCode, message: msg.message } },
            { type: EffectType.CANCEL_VS_TRANSITION },
        ],
    };
}

function onSessionJoined(state, msg) {
    const shouldPlayTransition = state.ui.vsTransitionPending;
    const nextState = {
        ...state,
        phase: GamePhase.SESSION_READY,
        screen: shouldPlayTransition ? "LOBBY" : "GAME",
        session: {
            ...state.session,
            id: msg.sessionId,
            ended: false,
        },
        connection: {
            ...state.connection,
            netOk: true,
            toastOnJoin: false,
        },
        ui: {
            ...state.ui,
            matchEnd: null,
            roundStartPending: false,
            vsTransitionPlaying: shouldPlayTransition,
            autoStartRound: shouldPlayTransition,
        },
    };
    const effects = [{ type: EffectType.SET_HASH, payload: { sessionId: msg.sessionId } }];
    if (state.connection.toastOnJoin) {
        effects.push({ type: EffectType.TOAST, payload: { key: "session_joined" } });
    }
    if (shouldPlayTransition) {
        effects.push({
            type: EffectType.PLAY_VS_TRANSITION,
            payload: {
                humanName: state.player.nickname || "You",
                llmName: state.session.opponentDisplayName || "LLM",
            },
        });
    }
    return { state: nextState, effects };
}

function onPlayerJoined(state, msg) {
    const players = { ...state.session.players };
    if (msg.playerId === state.player.id) {
        players.human = { playerId: msg.playerId, nickname: msg.nickname };
    } else {
        players.llm = { playerId: msg.playerId, nickname: msg.nickname };
    }
    return { state: { ...state, session: { ...state.session, players } }, effects: [] };
}

function onRoundStarted(state, msg) {
    const freeAnswerMode =
        msg.expectedAnswerType === "integer" ? "int" : msg.expectedAnswerType === "free_text" ? "text" : "text";
    return {
        state: {
            ...resetRound(state),
            phase: GamePhase.IN_ROUND,
            screen: "GAME",
            session: { ...state.session, id: msg.sessionId },
            round: {
                ...defaultRoundState(),
                id: msg.roundId,
                number: msg.roundNumber,
                questionId: msg.questionId,
                prompt: msg.questionPrompt || "",
                choices: msg.choices ?? null,
                expectedAnswerType: msg.expectedAnswerType,
                freeAnswerMode,
                releasedAt: msg.releasedAtEpochMs || Date.now(),
                handicapMs: msg.handicapMs || 0,
                deadlineAt: msg.deadlineAtEpochMs || (msg.releasedAtEpochMs || Date.now()) + 60000,
                nonceToken: msg.nonceToken,
            },
            ui: { ...state.ui, roundStartPending: false },
        },
        effects: [{ type: EffectType.START_TICKER }],
    };
}

function onLlmThinking(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    return { state: { ...state, llm: { ...state.llm, status: "THINKING" } }, effects: [] };
}

function onLlmReasoningDelta(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    if (typeof msg.seq === "number" && msg.seq <= state.llm.reasoningSeq) return { state, effects: [] };
    const nextStatus = state.llm.status === "IDLE" ? "THINKING" : state.llm.status;
    return {
        state: {
            ...state,
            llm: {
                ...state.llm,
                status: nextStatus,
                reasoningSeq: msg.seq ?? state.llm.reasoningSeq,
                reasoningBuf: state.llm.reasoningBuf + (msg.deltaText || ""),
                lastReasoningAt: Date.now(),
            },
        },
        effects: [],
    };
}

function onLlmReasoningTruncated(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    return { state: { ...state, llm: { ...state.llm, reasoningTruncated: true } }, effects: [] };
}

function onLlmReasoningEnded(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    return {
        state: { ...state, llm: { ...state.llm, reasoningEnded: true, status: "ANSWERING" } },
        effects: [],
    };
}

function onLlmAnswerLockIn(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    return { state: { ...state, llm: { ...state.llm, status: "LOCKIN" } }, effects: [] };
}

function onLlmFinalAnswer(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    return {
        state: {
            ...state,
            llm: {
                ...state.llm,
                finalAnswer: msg.finalAnswer || null,
                confidenceScore: typeof msg.confidenceScore === "number" ? msg.confidenceScore : null,
                reasoningSummary: msg.reasoningSummary || null,
            },
        },
        effects: [],
    };
}

function onLlmStreamError(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    return {
        state: { ...state, llm: { ...state.llm, streamError: msg.message || "stream error" } },
        effects: [],
    };
}

function onRoundResolved(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    return {
        state: {
            ...state,
            phase: GamePhase.ROUND_RESOLVED,
            round: { ...state.round, resolution: msg, submitted: state.round.submitted },
            llm: { ...state.llm },
        },
        effects: [{ type: EffectType.STOP_TICKER }],
    };
}

function onLlmReasoningReveal(state, msg) {
    if (msg.roundId !== state.round.id) return { state, effects: [] };
    return {
        state: {
            ...state,
            llm: { ...state.llm, reasoningRevealed: true, reasoningBuf: msg.fullReasoning || "" },
        },
        effects: [],
    };
}

function onSessionResolved(state, msg) {
    return {
        state: {
            ...state,
            phase: GamePhase.SESSION_RESOLVED,
            screen: "GAME",
            session: { ...state.session, ended: true },
            ui: { ...state.ui, matchEnd: { ...msg }, roundStartPending: false },
        },
        effects: [{ type: EffectType.STOP_TICKER }],
    };
}

function onSessionTerminated(state, msg) {
    if (state.phase === GamePhase.SESSION_RESOLVED || state.ui.matchEnd) {
        return {
            state,
            effects: [{ type: EffectType.WS_CLOSE }],
        };
    }
    return {
        state: resetForLobby(state),
        effects: [
            { type: EffectType.TOAST, payload: { key: "session_terminated", reason: msg.reason } },
            { type: EffectType.WS_CLOSE },
            { type: EffectType.SET_HASH },
        ],
    };
}

// ─────────────────────────────────────────────────────────────────────────────
// Dispatch table
// ─────────────────────────────────────────────────────────────────────────────

const serverEventHandlers = {
    session_error: onSessionError,
    session_joined: onSessionJoined,
    player_joined: onPlayerJoined,
    round_started: onRoundStarted,
    llm_thinking: onLlmThinking,
    llm_reasoning_delta: onLlmReasoningDelta,
    llm_reasoning_truncated: onLlmReasoningTruncated,
    llm_reasoning_ended: onLlmReasoningEnded,
    llm_answer_lock_in: onLlmAnswerLockIn,
    llm_final_answer: onLlmFinalAnswer,
    llm_stream_error: onLlmStreamError,
    round_resolved: onRoundResolved,
    llm_reasoning_reveal: onLlmReasoningReveal,
    session_resolved: onSessionResolved,
    session_terminated: onSessionTerminated,
};

// ─────────────────────────────────────────────────────────────────────────────
// Main export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reduces server events using a dispatch table pattern.
 * Adding a new event type is a one-function diff: add handler + add to table.
 */
export function reduceServerEvent(state, msg) {
    if (!msg?.type) return { state, effects: [] };
    const handler = serverEventHandlers[msg.type];
    return handler ? handler(state, msg) : { state, effects: [] };
}

// Export helpers for use by reducer
export { resetForLobby };
