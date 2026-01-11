import { MAX_NICKNAME_LEN } from "../../core/state.js";
import { ActionType, EffectType, GamePhase } from "./gameState.types.js";
import { createInitialState, defaultRoundState, defaultLlmState } from "./gameState.initial.js";
import { reduceServerEvent, resetForLobby } from "./gameState.serverEvents.js";

// ─────────────────────────────────────────────────────────────────────────────
// Local helpers
// ─────────────────────────────────────────────────────────────────────────────

const buildChoiceAnswer = (state) => {
    if (state.round.selectedChoiceIndex == null) return null;
    return { type: "multiple_choice", choiceIndex: state.round.selectedChoiceIndex };
};

const buildFreeTextAnswer = (state) => {
    const text = String(state.round.answerText || "").trim();
    if (!text) return null;
    return { type: "free_text", text };
};

const buildIntegerAnswer = (state) => {
    const raw = String(state.round.answerInt || "").trim();
    if (!raw || !/^-?\d+$/.test(raw)) return null;
    return { type: "integer", value: parseInt(raw, 10) };
};

const validateNickname = (nickname) => {
    const trimmed = String(nickname || "").trim();
    if (!trimmed) return { ok: false, reason: "nickname_required" };
    if (trimmed.length > MAX_NICKNAME_LEN) return { ok: false, reason: "nickname_too_long" };
    for (const ch of trimmed) {
        if (/[\u0000-\u001F\u007F]/.test(ch)) return { ok: false, reason: "nickname_invalid_chars" };
    }
    return { ok: true, nickname: trimmed };
};

const resolveOpponentMeta = (models, opponentSpecId) => {
    const flat = [...(models.LIGHTWEIGHT || []), ...(models.PREMIUM || [])];
    return flat.find((m) => m.id === opponentSpecId) || null;
};

const allowRoundStart = (state) =>
    state.session.id &&
    state.connection.status === "open" &&
    !state.ui.roundStartPending &&
    (state.phase === GamePhase.SESSION_READY || state.phase === GamePhase.ROUND_RESOLVED);

const deriveAnswer = (state) => {
    if (Array.isArray(state.round.choices) && state.round.choices.length) {
        return { answer: buildChoiceAnswer(state), reason: "answer_choose_option" };
    }
    if (state.round.freeAnswerMode === "int") {
        return { answer: buildIntegerAnswer(state), reason: "answer_invalid_int" };
    }
    return { answer: buildFreeTextAnswer(state), reason: "answer_empty_text" };
};

// ─────────────────────────────────────────────────────────────────────────────
// Main Reducer
// ─────────────────────────────────────────────────────────────────────────────

export function reducer(state, action) {
    if (!action) return { state, effects: [] };
    switch (action.type) {
        case ActionType.APP_INIT: {
            const nickname = action.payload?.nickname || null;
            const landingAcked = action.payload?.landingAcked || false;
            return {
                state: {
                    ...state,
                    player: { ...state.player, nickname },
                    ui: { ...state.ui, landingAcked },
                },
                effects: [],
            };
        }
        case ActionType.PLAYER_SESSION_LOADED: {
            return {
                state: {
                    ...state,
                    player: {
                        ...state.player,
                        id: action.payload?.playerId || null,
                        issuedAt: action.payload?.issuedAtEpochMs || null,
                    },
                },
                effects: [],
            };
        }
        case ActionType.MODELS_LOADED: {
            return {
                state: {
                    ...state,
                    models: {
                        LIGHTWEIGHT: action.payload?.light || [],
                        PREMIUM: action.payload?.premium || [],
                    },
                },
                effects: [],
            };
        }
        case ActionType.LOBBY_TAB_SELECTED: {
            const mode = action.payload?.mode;
            if (!mode) return { state, effects: [] };
            const nextMode = mode === "LIGHTWEIGHT" || mode === "PREMIUM" ? mode : state.mode;
            return {
                state: {
                    ...state,
                    mode: nextMode,
                    ui: { ...state.ui, lobbyTab: mode },
                },
                effects: [],
            };
        }
        case ActionType.INTENT_START_MATCH: {
            const nicknameCheck = validateNickname(action.payload?.nickname);
            if (!nicknameCheck.ok) {
                return {
                    state,
                    effects: [
                        {
                            type: EffectType.TOAST,
                            payload: { key: nicknameCheck.reason, maxLen: MAX_NICKNAME_LEN },
                        },
                    ],
                };
            }
            const opponentSpecId = String(action.payload?.opponentSpecId || "").trim();
            if (!opponentSpecId) {
                return {
                    state,
                    effects: [{ type: EffectType.TOAST, payload: { key: "opponent_required" } }],
                };
            }
            const mode = action.payload?.mode || state.mode;
            const opponentModel = resolveOpponentMeta(state.models, opponentSpecId);
            const displayName = opponentModel?.metadata?.displayName || opponentModel?.id || "LLM";
            const nextState = {
                ...state,
                phase: GamePhase.JOINING,
                screen: "LOBBY",
                mode,
                player: { ...state.player, nickname: nicknameCheck.nickname },
                session: {
                    ...state.session,
                    id: null,
                    opponentSpecId,
                    opponentDisplayName: displayName,
                    opponentQuestionSetDisplayName: opponentModel?.metadata?.questionSetDisplayName || null,
                    opponentQuestionSetDescription: opponentModel?.metadata?.questionSetDescription || null,
                    opponentQuestionSetDescriptionI18nKey:
                        opponentModel?.metadata?.questionSetDescriptionI18nKey || null,
                    opponentDifficulty: opponentModel?.metadata?.difficulty || null,
                    players: { human: null, llm: null },
                    ended: false,
                },
                ui: {
                    ...state.ui,
                    roundStartPending: false,
                    vsTransitionPending: true,
                    vsTransitionPlaying: false,
                    autoStartRound: false,
                    matchEnd: null,
                    lobbyTab: mode,
                },
                connection: {
                    ...state.connection,
                    status: "opening",
                    toastOnJoin: true,
                },
            };
            return {
                state: nextState,
                effects: [
                    {
                        type: EffectType.SAVE_NICKNAME,
                        payload: { nickname: nicknameCheck.nickname },
                    },
                    {
                        type: EffectType.WS_CONNECT,
                        payload: {
                            sessionId: null,
                            opponentSpecId,
                            nickname: nicknameCheck.nickname,
                            locale: action.payload?.locale || "en",
                            toastOnOpen: true,
                            toastOnClose: true,
                        },
                    },
                ],
            };
        }
        case ActionType.INTENT_RECOVER_SESSION: {
            const payload = action.payload || {};
            if (!payload.sessionId || !payload.opponentSpecId) return { state, effects: [] };
            return {
                state: {
                    ...state,
                    phase: GamePhase.JOINING,
                    screen: "LOBBY",
                    player: { ...state.player, nickname: payload.nickname || state.player.nickname },
                    session: {
                        ...state.session,
                        id: payload.sessionId,
                        opponentSpecId: payload.opponentSpecId,
                        opponentDisplayName: payload.opponentDisplayName || state.session.opponentDisplayName,
                        opponentQuestionSetDisplayName: payload.opponentQuestionSetDisplayName || null,
                        opponentQuestionSetDescription: payload.opponentQuestionSetDescription || null,
                        opponentQuestionSetDescriptionI18nKey: payload.opponentQuestionSetDescriptionI18nKey || null,
                        opponentDifficulty: payload.opponentDifficulty || null,
                        players: { human: null, llm: null },
                        ended: false,
                    },
                    connection: {
                        ...state.connection,
                        status: "opening",
                        toastOnJoin: false,
                    },
                    ui: {
                        ...state.ui,
                        vsTransitionPending: false,
                        vsTransitionPlaying: false,
                        autoStartRound: false,
                        matchEnd: null,
                    },
                },
                effects: [
                    {
                        type: EffectType.WS_CONNECT,
                        payload: {
                            sessionId: payload.sessionId,
                            opponentSpecId: payload.opponentSpecId,
                            nickname: payload.nickname || state.player.nickname,
                            locale: payload.locale || "en",
                            toastOnOpen: false,
                            toastOnClose: true,
                        },
                    },
                ],
            };
        }
        case ActionType.INTENT_START_ROUND: {
            if (!state.session.id) {
                return { state, effects: [{ type: EffectType.TOAST, payload: { key: "start_round_no_session" } }] };
            }
            if (state.ui.roundStartPending) {
                return { state, effects: [] };
            }
            if (state.connection.status !== "open") {
                return { state, effects: [{ type: EffectType.TOAST, payload: { key: "start_round_no_ws" } }] };
            }
            if (!(state.phase === GamePhase.SESSION_READY || state.phase === GamePhase.ROUND_RESOLVED)) {
                return { state, effects: [] };
            }
            const commandId = action.payload?.commandId;
            const nextState = {
                ...state,
                round: defaultRoundState(),
                llm: defaultLlmState(),
                ui: { ...state.ui, roundStartPending: true, reasoningPeeked: false },
            };
            return {
                state: nextState,
                effects: [
                    {
                        type: EffectType.WS_SEND,
                        payload: {
                            type: "start_round_request",
                            sessionId: state.session.id,
                            playerId: state.player.id,
                            commandId,
                        },
                    },
                ],
            };
        }
        case ActionType.INTENT_SUBMIT_ANSWER: {
            const now = action.payload?.now || Date.now();
            if (state.phase !== GamePhase.IN_ROUND || !state.round.id || !state.round.nonceToken) {
                return { state, effects: [{ type: EffectType.TOAST, payload: { key: "submit_not_ready" } }] };
            }
            if (now > state.round.deadlineAt) {
                return { state, effects: [] };
            }
            if (state.round.submitted) return { state, effects: [] };
            const { answer, reason } = deriveAnswer(state);
            if (!answer) {
                return { state, effects: [{ type: EffectType.TOAST, payload: { key: reason } }] };
            }
            return {
                state: {
                    ...state,
                    round: {
                        ...state.round,
                        submitted: true,
                        humanAnswer: answer,
                    },
                },
                effects: [
                    {
                        type: EffectType.WS_SEND,
                        payload: {
                            type: "submit_answer",
                            sessionId: state.session.id,
                            playerId: state.player.id,
                            roundId: state.round.id,
                            commandId: action.payload?.commandId,
                            nonceToken: state.round.nonceToken,
                            answer,
                            clientSentAtEpochMs: now,
                        },
                    },
                ],
            };
        }
        case ActionType.INTENT_NEXT: {
            if (state.phase === GamePhase.SESSION_RESOLVED) {
                return {
                    state: resetForLobby(state),
                    effects: [{ type: EffectType.WS_CLOSE }, { type: EffectType.SET_HASH }],
                };
            }
            if (state.phase === GamePhase.ROUND_RESOLVED) {
                if (!allowRoundStart(state)) {
                    return { state, effects: [] };
                }
                return reducer(state, {
                    type: ActionType.INTENT_START_ROUND,
                    payload: { commandId: action.payload?.commandId },
                });
            }
            return { state, effects: [] };
        }
        case ActionType.INTENT_GIVE_UP: {
            if (!state.session.id) return { state, effects: [] };
            return {
                state: { ...state, ui: { ...state.ui, roundDisplayHidden: true } },
                effects: [{ type: EffectType.TERMINATE_SESSION }],
            };
        }
        case ActionType.GIVE_UP_SUCCEEDED: {
            return {
                state: resetForLobby(state),
                effects: [{ type: EffectType.STOP_TICKER }, { type: EffectType.WS_CLOSE }, { type: EffectType.SET_HASH }],
            };
        }
        case ActionType.GIVE_UP_FAILED: {
            return {
                state: { ...state, ui: { ...state.ui, roundDisplayHidden: false } },
                effects: [{ type: EffectType.TOAST, payload: { key: "give_up_failed", message: action.payload?.message } }],
            };
        }
        case ActionType.INTENT_SELECT_CHOICE: {
            if (state.phase !== GamePhase.IN_ROUND || state.round.submitted) return { state, effects: [] };
            return {
                state: {
                    ...state,
                    round: { ...state.round, selectedChoiceIndex: action.payload?.index ?? null },
                },
                effects: [],
            };
        }
        case ActionType.INTENT_SET_FREE_MODE: {
            const mode = action.payload?.mode;
            if (!mode) return { state, effects: [] };
            return {
                state: { ...state, round: { ...state.round, freeAnswerMode: mode } },
                effects: [],
            };
        }
        case ActionType.INTENT_UPDATE_FREE_TEXT: {
            return {
                state: { ...state, round: { ...state.round, answerText: action.payload?.text || "" } },
                effects: [],
            };
        }
        case ActionType.INTENT_UPDATE_INT_VALUE: {
            return {
                state: { ...state, round: { ...state.round, answerInt: action.payload?.value || "" } },
                effects: [],
            };
        }
        case ActionType.INTENT_CLEAR_ANSWER: {
            return {
                state: {
                    ...state,
                    round: {
                        ...state.round,
                        selectedChoiceIndex: null,
                        answerText: "",
                        answerInt: "",
                    },
                },
                effects: [],
            };
        }
        case ActionType.INTENT_SET_TOP_TAB: {
            const tab = action.payload?.tab;
            if (!tab) return { state, effects: [] };
            return { state: { ...state, ui: { ...state.ui, topTab: tab } }, effects: [] };
        }
        case ActionType.INTENT_TOGGLE_SCRATCHPAD: {
            const open = !state.ui.workspace.scratchOpen;
            return {
                state: {
                    ...state,
                    ui: { ...state.ui, workspace: { ...state.ui.workspace, scratchOpen: open } },
                },
                effects: [],
            };
        }
        case ActionType.INTENT_SCRATCHPAD_TEXT: {
            return {
                state: {
                    ...state,
                    ui: { ...state.ui, workspace: { ...state.ui.workspace, scratchText: action.payload?.text || "" } },
                },
                effects: [],
            };
        }
        case ActionType.INTENT_CALC_EXPR: {
            return {
                state: {
                    ...state,
                    ui: { ...state.ui, workspace: { ...state.ui.workspace, calcExpr: action.payload?.expr || "" } },
                },
                effects: [],
            };
        }
        case ActionType.INTENT_CALC_RESULT: {
            return {
                state: {
                    ...state,
                    ui: { ...state.ui, workspace: { ...state.ui.workspace, calcResult: action.payload?.result || "" } },
                },
                effects: [],
            };
        }
        case ActionType.INTENT_CLEAR_SCRATCHPAD: {
            return {
                state: {
                    ...state,
                    ui: {
                        ...state.ui,
                        workspace: { ...state.ui.workspace, scratchText: "", calcExpr: "", calcResult: "" },
                    },
                },
                effects: [],
            };
        }
        case ActionType.INTENT_PEEK_REASONING: {
            return {
                state: { ...state, ui: { ...state.ui, reasoningPeeked: true } },
                effects: [],
            };
        }
        case ActionType.MATCH_END_DISMISSED: {
            return { state: { ...state, ui: { ...state.ui, matchEnd: null } }, effects: [] };
        }
        case ActionType.LANDING_ACKED: {
            return { state: { ...state, ui: { ...state.ui, landingAcked: true } }, effects: [] };
        }
        case ActionType.WS_OPENED: {
            return {
                state: {
                    ...state,
                    connection: { ...state.connection, status: "open", netOk: true },
                },
                effects: [],
            };
        }
        case ActionType.WS_CLOSED: {
            const effects = [];
            if (action.payload?.toastOnClose) {
                effects.push({ type: EffectType.TOAST, payload: { key: "ws_closed" } });
            }
            return {
                state: {
                    ...state,
                    connection: { ...state.connection, status: "closed", netOk: false },
                },
                effects,
            };
        }
        case ActionType.TIMER_TICK: {
            return {
                state: { ...state, timers: { ...state.timers, now: action.payload?.now || Date.now() } },
                effects: [],
            };
        }
        case ActionType.VS_TRANSITION_SWITCHED: {
            return {
                state: { ...state, screen: "GAME", ui: { ...state.ui, vsTransitionPlaying: true } },
                effects: [],
            };
        }
        case ActionType.VS_TRANSITION_DONE: {
            return {
                state: {
                    ...state,
                    ui: {
                        ...state.ui,
                        vsTransitionPending: false,
                        vsTransitionPlaying: false,
                        autoStartRound: false,
                    },
                },
                effects: [],
            };
        }
        case ActionType.SERVER_EVENT_RECEIVED: {
            return reduceServerEvent(state, action.payload);
        }
        default:
            return { state, effects: [] };
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Selectors
// ─────────────────────────────────────────────────────────────────────────────

export const isInRound = (state) => state.phase === GamePhase.IN_ROUND;
