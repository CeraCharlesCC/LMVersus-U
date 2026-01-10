import { MAX_NICKNAME_LEN } from "../../core/state.js";

export const GamePhase = {
    LOBBY: "LOBBY",
    JOINING: "JOINING",
    SESSION_READY: "SESSION_READY",
    IN_ROUND: "IN_ROUND",
    ROUND_RESOLVED: "ROUND_RESOLVED",
    SESSION_RESOLVED: "SESSION_RESOLVED",
};

export const ActionType = {
    APP_INIT: "APP_INIT",
    PLAYER_SESSION_LOADED: "PLAYER_SESSION_LOADED",
    MODELS_LOADED: "MODELS_LOADED",
    LOBBY_TAB_SELECTED: "LOBBY_TAB_SELECTED",
    INTENT_START_MATCH: "INTENT_START_MATCH",
    INTENT_RECOVER_SESSION: "INTENT_RECOVER_SESSION",
    INTENT_START_ROUND: "INTENT_START_ROUND",
    INTENT_SUBMIT_ANSWER: "INTENT_SUBMIT_ANSWER",
    INTENT_NEXT: "INTENT_NEXT",
    INTENT_GIVE_UP: "INTENT_GIVE_UP",
    GIVE_UP_SUCCEEDED: "GIVE_UP_SUCCEEDED",
    GIVE_UP_FAILED: "GIVE_UP_FAILED",
    INTENT_SELECT_CHOICE: "INTENT_SELECT_CHOICE",
    INTENT_SET_FREE_MODE: "INTENT_SET_FREE_MODE",
    INTENT_UPDATE_FREE_TEXT: "INTENT_UPDATE_FREE_TEXT",
    INTENT_UPDATE_INT_VALUE: "INTENT_UPDATE_INT_VALUE",
    INTENT_CLEAR_ANSWER: "INTENT_CLEAR_ANSWER",
    INTENT_SET_TOP_TAB: "INTENT_SET_TOP_TAB",
    INTENT_TOGGLE_SCRATCHPAD: "INTENT_TOGGLE_SCRATCHPAD",
    INTENT_SCRATCHPAD_TEXT: "INTENT_SCRATCHPAD_TEXT",
    INTENT_CALC_EXPR: "INTENT_CALC_EXPR",
    INTENT_CALC_RESULT: "INTENT_CALC_RESULT",
    INTENT_CLEAR_SCRATCHPAD: "INTENT_CLEAR_SCRATCHPAD",
    INTENT_REVEAL_REASONING: "INTENT_REVEAL_REASONING",
    MATCH_END_DISMISSED: "MATCH_END_DISMISSED",
    LANDING_ACKED: "LANDING_ACKED",
    WS_OPENED: "WS_OPENED",
    WS_CLOSED: "WS_CLOSED",
    SERVER_EVENT_RECEIVED: "SERVER_EVENT_RECEIVED",
    TIMER_TICK: "TIMER_TICK",
    VS_TRANSITION_SWITCHED: "VS_TRANSITION_SWITCHED",
    VS_TRANSITION_DONE: "VS_TRANSITION_DONE",
};

export const EffectType = {
    WS_CONNECT: "WS_CONNECT",
    WS_SEND: "WS_SEND",
    WS_CLOSE: "WS_CLOSE",
    TOAST: "TOAST",
    SET_HASH: "SET_HASH",
    SAVE_NICKNAME: "SAVE_NICKNAME",
    PLAY_VS_TRANSITION: "PLAY_VS_TRANSITION",
    CANCEL_VS_TRANSITION: "CANCEL_VS_TRANSITION",
    START_TICKER: "START_TICKER",
    STOP_TICKER: "STOP_TICKER",
    TERMINATE_SESSION: "TERMINATE_SESSION",
};

const defaultRoundState = () => ({
    id: null,
    number: 0,
    questionId: null,
    prompt: "",
    choices: null,
    expectedAnswerType: null,
    releasedAt: 0,
    handicapMs: 0,
    deadlineAt: 0,
    nonceToken: null,
    selectedChoiceIndex: null,
    freeAnswerMode: "text",
    submitted: false,
    humanAnswer: null,
    answerText: "",
    answerInt: "",
    resolution: null,
});

const defaultLlmState = () => ({
    status: "IDLE",
    reasoningBuf: "",
    reasoningSeq: -1,
    reasoningEnded: false,
    reasoningTruncated: false,
    lastReasoningAt: 0,
    finalAnswer: null,
    confidenceScore: null,
    reasoningSummary: null,
    streamError: null,
    reasoningRevealed: false,
});

const defaultWorkspaceState = (scratchOpen) => ({
    scratchOpen,
    scratchText: "",
    calcExpr: "",
    calcResult: "",
});

export const createInitialState = (now = Date.now()) => ({
    phase: GamePhase.LOBBY,
    screen: "LOBBY",
    mode: "LIGHTWEIGHT",
    models: { LIGHTWEIGHT: [], PREMIUM: [] },
    player: {
        id: null,
        issuedAt: null,
        nickname: null,
    },
    connection: {
        status: "closed",
        netOk: false,
        toastOnJoin: false,
    },
    session: {
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
    timers: {
        now,
        ticking: false,
    },
    ui: {
        roundStartPending: false,
        vsTransitionPending: false,
        vsTransitionPlaying: false,
        autoStartRound: false,
        matchEnd: null,
        lobbyTab: "LIGHTWEIGHT",
        topTab: "question",
        landingAcked: false,
        workspace: defaultWorkspaceState(false),
    },
});

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
        matchEnd: null,
        lobbyTab: state.ui.lobbyTab || "LIGHTWEIGHT",
        topTab: "question",
    },
});

const resetRound = (state) => ({
    ...state,
    round: defaultRoundState(),
    llm: defaultLlmState(),
    ui: {
        ...state.ui,
        topTab: "question",
    },
});

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
                ui: { ...state.ui, roundStartPending: true },
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
                state,
                effects: [{ type: EffectType.TERMINATE_SESSION }],
            };
        }
        case ActionType.GIVE_UP_SUCCEEDED: {
            return {
                state: resetForLobby(state),
                effects: [{ type: EffectType.WS_CLOSE }, { type: EffectType.SET_HASH }],
            };
        }
        case ActionType.GIVE_UP_FAILED: {
            return {
                state,
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
        case ActionType.INTENT_REVEAL_REASONING: {
            return {
                state: { ...state, llm: { ...state.llm, reasoningRevealed: true } },
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

function reduceServerEvent(state, msg) {
    if (!msg || !msg.type) return { state, effects: [] };
    const type = msg.type;

    if (type === "session_error") {
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

    if (type === "session_joined") {
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

    if (type === "player_joined") {
        const players = { ...state.session.players };
        if (msg.playerId === state.player.id) {
            players.human = { playerId: msg.playerId, nickname: msg.nickname };
        } else {
            players.llm = { playerId: msg.playerId, nickname: msg.nickname };
        }
        return { state: { ...state, session: { ...state.session, players } }, effects: [] };
    }

    if (type === "round_started") {
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

    if (type === "llm_thinking") {
        if (msg.roundId !== state.round.id) return { state, effects: [] };
        return { state: { ...state, llm: { ...state.llm, status: "THINKING" } }, effects: [] };
    }

    if (type === "llm_reasoning_delta") {
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

    if (type === "llm_reasoning_truncated") {
        if (msg.roundId !== state.round.id) return { state, effects: [] };
        return { state: { ...state, llm: { ...state.llm, reasoningTruncated: true } }, effects: [] };
    }

    if (type === "llm_reasoning_ended") {
        if (msg.roundId !== state.round.id) return { state, effects: [] };
        return {
            state: { ...state, llm: { ...state.llm, reasoningEnded: true, status: "ANSWERING" } },
            effects: [],
        };
    }

    if (type === "llm_answer_lock_in") {
        if (msg.roundId !== state.round.id) return { state, effects: [] };
        return { state: { ...state, llm: { ...state.llm, status: "LOCKIN" } }, effects: [] };
    }

    if (type === "llm_final_answer") {
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

    if (type === "llm_stream_error") {
        if (msg.roundId !== state.round.id) return { state, effects: [] };
        return {
            state: { ...state, llm: { ...state.llm, streamError: msg.message || "stream error" } },
            effects: [],
        };
    }

    if (type === "round_resolved") {
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

    if (type === "llm_reasoning_reveal") {
        if (msg.roundId !== state.round.id) return { state, effects: [] };
        return {
            state: {
                ...state,
                llm: { ...state.llm, reasoningRevealed: true, reasoningBuf: msg.fullReasoning || "" },
            },
            effects: [],
        };
    }

    if (type === "session_resolved") {
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

    if (type === "session_terminated") {
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

    return { state, effects: [] };
}

export const isInRound = (state) => state.phase === GamePhase.IN_ROUND;
