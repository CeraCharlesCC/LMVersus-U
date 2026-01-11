import { createStore } from "../core/store.js";
import { createInitialState, reducer } from "./domain/gameState.js";
import { runEffects } from "./effects.js";

export const store = createStore({
    reducer,
    initialState: createInitialState(),
    effectRunner: runEffects,
});

export const dispatch = store.dispatch;
export const getState = store.getState;
export const subscribe = store.subscribe;
