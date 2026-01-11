// Types / Constants
export { GamePhase, ActionType, EffectType } from "./gameState.types.js";

// Initial state factories
export { createInitialState, defaultRoundState, defaultLlmState, defaultWorkspaceState } from "./gameState.initial.js";

// Reducer
export { reducer, isInRound } from "./gameState.reducer.js";

// Server events (if needed externally)
export { reduceServerEvent } from "./gameState.serverEvents.js";
