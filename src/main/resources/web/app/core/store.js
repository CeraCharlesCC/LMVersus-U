export function createStore({ reducer, initialState, effectRunner }) {
    let state = initialState;
    const listeners = new Set();

    const getState = () => state;

    const dispatch = (action) => {
        const result = reducer(state, action);
        if (!result) return;
        const prev = state;
        state = result.state;
        listeners.forEach((listener) => listener(state, prev, action));
        const effects = result.effects || [];
        if (effects.length && effectRunner) {
            effectRunner(effects, dispatch, getState);
        }
    };

    const subscribe = (listener) => {
        listeners.add(listener);
        return () => listeners.delete(listener);
    };

    return { getState, dispatch, subscribe };
}
