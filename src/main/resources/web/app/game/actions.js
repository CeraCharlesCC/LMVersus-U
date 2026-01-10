import { newCommandId } from "../core/utils.js";
import { ActionType } from "./domain/gameState.js";
import { dispatch } from "./store.js";

export const actions = {
    initApp: ({ nickname, landingAcked }) =>
        dispatch({ type: ActionType.APP_INIT, payload: { nickname, landingAcked } }),
    playerSessionLoaded: ({ playerId, issuedAtEpochMs }) =>
        dispatch({ type: ActionType.PLAYER_SESSION_LOADED, payload: { playerId, issuedAtEpochMs } }),
    modelsLoaded: ({ light, premium }) => dispatch({ type: ActionType.MODELS_LOADED, payload: { light, premium } }),
    selectLobbyTab: (mode) => dispatch({ type: ActionType.LOBBY_TAB_SELECTED, payload: { mode } }),
    startMatch: ({ mode, nickname, opponentSpecId, locale }) =>
        dispatch({
            type: ActionType.INTENT_START_MATCH,
            payload: { mode, nickname, opponentSpecId, locale },
        }),
    recoverSession: (payload) => dispatch({ type: ActionType.INTENT_RECOVER_SESSION, payload }),
    startRound: () =>
        dispatch({
            type: ActionType.INTENT_START_ROUND,
            payload: { commandId: newCommandId() },
        }),
    submitAnswer: () =>
        dispatch({
            type: ActionType.INTENT_SUBMIT_ANSWER,
            payload: { commandId: newCommandId(), now: Date.now() },
        }),
    next: () =>
        dispatch({
            type: ActionType.INTENT_NEXT,
            payload: { commandId: newCommandId() },
        }),
    giveUp: () => dispatch({ type: ActionType.INTENT_GIVE_UP }),
    selectChoice: (index) =>
        dispatch({ type: ActionType.INTENT_SELECT_CHOICE, payload: { index } }),
    setFreeAnswerMode: (mode) =>
        dispatch({ type: ActionType.INTENT_SET_FREE_MODE, payload: { mode } }),
    updateFreeText: (text) =>
        dispatch({ type: ActionType.INTENT_UPDATE_FREE_TEXT, payload: { text } }),
    updateIntValue: (value) =>
        dispatch({ type: ActionType.INTENT_UPDATE_INT_VALUE, payload: { value } }),
    clearAnswer: () => dispatch({ type: ActionType.INTENT_CLEAR_ANSWER }),
    setTopTab: (tab) => dispatch({ type: ActionType.INTENT_SET_TOP_TAB, payload: { tab } }),
    toggleScratchpad: () => dispatch({ type: ActionType.INTENT_TOGGLE_SCRATCHPAD }),
    updateScratchpadText: (text) =>
        dispatch({ type: ActionType.INTENT_SCRATCHPAD_TEXT, payload: { text } }),
    updateCalcExpr: (expr) =>
        dispatch({ type: ActionType.INTENT_CALC_EXPR, payload: { expr } }),
    updateCalcResult: (result) =>
        dispatch({ type: ActionType.INTENT_CALC_RESULT, payload: { result } }),
    clearScratchpad: () => dispatch({ type: ActionType.INTENT_CLEAR_SCRATCHPAD }),
    revealReasoning: () => dispatch({ type: ActionType.INTENT_REVEAL_REASONING }),
    dismissMatchEnd: () => dispatch({ type: ActionType.MATCH_END_DISMISSED }),
    ackLanding: () => dispatch({ type: ActionType.LANDING_ACKED }),
};
