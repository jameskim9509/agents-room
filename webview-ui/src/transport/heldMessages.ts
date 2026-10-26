import { TRANSPORT_INBOUND_BUFFER_MAX } from '../constants.js';

/**
 * Fan-out to message subscribers that holds messages, in order, while nobody
 * is subscribed, and hands them to the first subscriber instead of dropping
 * them.
 *
 * Transports are created at module import, before React mounts, so a frame
 * that arrives right away (the desktop runtime's connect-time snapshot) or a
 * locally delivered asset frame would otherwise be lost. Once frames are held,
 * later ones queue behind them, so ordering survives a missing subscriber.
 */
export class HeldMessages<T> {
  private handlers: Array<(message: T) => void> = [];
  private held: T[] = [];
  private flushScheduled = false;
  private flushing = false;

  subscribe(handler: (message: T) => void): () => void {
    this.handlers.push(handler);
    this.scheduleFlush();
    return () => {
      this.handlers = this.handlers.filter((h) => h !== handler);
    };
  }

  /** Hand a message to the subscribers, or hold it. */
  dispatch(message: T): void {
    if (this.handlers.length === 0 || this.held.length > 0 || this.flushing) {
      this.held.push(message);
      if (this.held.length > TRANSPORT_INBOUND_BUFFER_MAX) {
        this.held.shift();
        console.warn('[Transport] Inbound buffer full, dropped the oldest held message');
      }
      this.scheduleFlush();
      return;
    }
    this.notify(message);
  }

  /** Drop every subscriber and every held message. */
  clear(): void {
    this.handlers = [];
    this.held = [];
  }

  /**
   * Flush on a microtask, not inside subscribe: React StrictMode subscribes,
   * unsubscribes and subscribes again in one commit, and the held messages
   * must reach the subscriber that stays.
   */
  private scheduleFlush(): void {
    if (this.flushScheduled || this.handlers.length === 0 || this.held.length === 0) return;
    this.flushScheduled = true;
    queueMicrotask(() => {
      this.flushScheduled = false;
      this.flushing = true;
      try {
        while (this.held.length > 0 && this.handlers.length > 0) {
          this.notify(this.held.shift()!);
        }
      } finally {
        this.flushing = false;
      }
    });
  }

  private notify(message: T): void {
    for (const handler of [...this.handlers]) {
      try {
        handler(message);
      } catch (err) {
        console.error('[Transport] Message handler failed', err);
      }
    }
  }
}
