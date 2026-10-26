import { useCallback, useSyncExternalStore } from 'react';

import { RUNTIME_STATE_RENDER_FALLBACK_MS } from '../constants.js';
import type { RuntimeState, RuntimeStateStore } from '../runtimeState.js';

/**
 * Subscribes `listener` to `store`, calling it at most once per animation
 * frame however many frames arrived in between (D24, SCRUM-22): a burst of
 * message chunks re-renders the panels once, not once per WebSocket frame.
 * The store itself takes every frame at once, so nothing is dropped, only the
 * re-render waits. A timer backs up the frame callback, which a hidden or
 * throttled window may delay indefinitely.
 */
function coalescedSubscribe(store: RuntimeStateStore, listener: () => void): () => void {
  let frame = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const flush = () => {
    if (frame) cancelAnimationFrame(frame);
    if (timer) clearTimeout(timer);
    frame = 0;
    timer = null;
    listener();
  };
  const unsubscribe = store.subscribe(() => {
    if (frame || timer) return;
    frame = requestAnimationFrame(flush);
    timer = setTimeout(flush, RUNTIME_STATE_RENDER_FALLBACK_MS);
  });
  return () => {
    unsubscribe();
    if (frame) cancelAnimationFrame(frame);
    if (timer) clearTimeout(timer);
  };
}

/** The renderer's runtime state, re-rendering at most once per frame. */
export function useRuntimeState(store: RuntimeStateStore): RuntimeState {
  const subscribe = useCallback(
    (listener: () => void) => coalescedSubscribe(store, listener),
    [store],
  );
  const getSnapshot = useCallback(() => store.getState(), [store]);
  return useSyncExternalStore(subscribe, getSnapshot);
}
