export const MAX_NICKNAME_LEN = 16;
export const STORAGE_KEY_NICKNAME = "lmvu_nickname";
export const STORAGE_KEY_LANDING = "lmvu_landing_acked";

/** ---- App State ---- */
export const state = {
    playerId: null,
    issuedAt: null,

    mode: "LIGHTWEIGHT",
    models: { LIGHTWEIGHT: [], PREMIUM: [] },

    ws: null,
    wsOpen: false,

    sessionId: null,
    nickname: null,
    opponentSpecId: null,
    opponentDisplayName: null,
    opponentQuestionSetDisplayName: null,
    opponentDifficulty: null,
    players: { human: null, llm: null },

    // round
    inRound: false,
    roundId: null,
    roundNumber: 0,
    nonceToken: null,
    questionId: null,
    questionPrompt: "",
    choices: null,
    releasedAt: 0,
    handicapMs: 0,
    deadlineAt: 0,

    // answer
    selectedChoiceIndex: null,
    freeAnswerMode: "text", // "text" | "int"
    submitted: false,
    humanAnswer: null,

    // llm
    llmStatus: "IDLE",
    reasoningBuf: "",
    reasoningSeq: -1,
    reasoningEnded: false,
    reasoningSquaresShown: false,
    reasoningTruncated: false,
    lastReasoningAt: 0,
    finalAnswer: null,
    confidenceScore: null,
    reasoningSummary: null,
    streamError: null,
    roundResolveReason: null,
    reasoningRevealed: false,

    timers: {
        tickHandle: null,
        renderHandle: null,
    },

    ui: {
        reasoningPinnedToTop: true,
        programmaticScroll: false,
        matchEndVisible: false,
        lastResultDetails: null,
    },
};
