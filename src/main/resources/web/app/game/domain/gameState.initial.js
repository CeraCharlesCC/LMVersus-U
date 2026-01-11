/**
 * Initial state factories for game state.
 * Extracted from gameState.js for modularity.
 */

import { GamePhase } from "./gameState.types.js";

export const defaultRoundState = () => ({
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

export const defaultLlmState = () => ({
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

export const defaultWorkspaceState = (scratchOpen) => ({
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
        roundDisplayHidden: false,
        matchEnd: null,
        lobbyTab: "LIGHTWEIGHT",
        topTab: "question",
        landingAcked: false,
        reasoningPeeked: false,
        workspace: defaultWorkspaceState(false),
    },
});
