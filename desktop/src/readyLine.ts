import { READY_LINE_PREFIX } from './constants.js';

/** Highest TCP port number. */
const MAX_PORT = 65_535;

/**
 * Splits a decoded text stream into lines. A chunk may end mid-line, so the
 * unterminated tail is carried into the next `push`. A trailing `\r` is
 * stripped from every line: Java's `println` writes CRLF on Windows.
 */
export class LineSplitter {
  private tail = '';

  push(chunk: string): string[] {
    const parts = (this.tail + chunk).split('\n');
    this.tail = parts.pop() ?? '';
    return parts.map(stripCarriageReturn);
  }

  /** The unterminated rest of the stream, once it has ended. */
  flush(): string | null {
    const rest = this.tail;
    this.tail = '';
    return rest === '' ? null : stripCarriageReturn(rest);
  }
}

function stripCarriageReturn(line: string): string {
  return line.endsWith('\r') ? line.slice(0, -1) : line;
}

/**
 * Reads the runtime's ready line, `AGENTS_ROOM_READY {"port":<int>}`, and
 * returns the port. Any other line, or a ready line whose JSON does not carry
 * a valid TCP port, returns `null`.
 */
export function parseReadyLine(line: string): number | null {
  const text = stripCarriageReturn(line);
  if (!text.startsWith(READY_LINE_PREFIX)) return null;
  let payload: unknown;
  try {
    payload = JSON.parse(text.slice(READY_LINE_PREFIX.length));
  } catch {
    return null;
  }
  if (typeof payload !== 'object' || payload === null) return null;
  const port = (payload as { port?: unknown }).port;
  if (typeof port !== 'number' || !Number.isInteger(port) || port < 1 || port > MAX_PORT) {
    return null;
  }
  return port;
}
