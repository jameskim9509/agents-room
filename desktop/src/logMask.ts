import { TOKEN_MASK } from './constants.js';

/** `token=<value>` in a query string or a log line, whoever's token it is. */
const TOKEN_PARAM = /(\btoken=)[^\s&"'<>]+/gi;

/**
 * Masks WebSocket tokens in one log line: every occurrence of a known token,
 * plus the value of any `token=` parameter. The runtime must not log its
 * token, so this is the shell's second line of defense, not the first.
 */
export function maskSecrets(line: string, tokens: Iterable<string>): string {
  let masked = line;
  for (const token of tokens) {
    if (token !== '') masked = masked.split(token).join(TOKEN_MASK);
  }
  return masked.replace(TOKEN_PARAM, `$1${TOKEN_MASK}`);
}
