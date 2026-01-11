/**
 * Game state module - barrel re-exports for backward compatibility.
 *
 * This file has been refactored into focused modules:
 * - gameState.types.js     - Constants (ActionType, EffectType, GamePhase)
 * - gameState.initial.js   - State factories (createInitialState, defaults)
 * - gameState.serverEvents.js - Server event dispatch table
 * - gameState.reducer.js   - Main reducer logic
 *
 * All existing imports continue to work through these re-exports.
 */

// Types / Constants
export { GamePhase, ActionType, EffectType } from "./gameState.types.js";

// Initial state factories
export { createInitialState, defaultRoundState, defaultLlmState, defaultWorkspaceState } from "./gameState.initial.js";

// Reducer
export { reducer, isInRound } from "./gameState.reducer.js";

// Server events (if needed externally)
export { reduceServerEvent } from "./gameState.serverEvents.js";
