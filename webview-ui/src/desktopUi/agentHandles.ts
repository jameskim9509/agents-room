/**
 * The engine names characters by integer (`Character.id`), the runtime names
 * agents by UUID (`agentId`, architecture.md 식별자). This maps one to the
 * other at the boundary so the engine stays as it is.
 *
 * Handles count up from 1 and are never reused in a renderer session: an agent
 * that leaves (another project opened) and comes back gets its old handle, and
 * no other agent ever gets it. Positive, so they never meet the engine's
 * sub-agent ids (from -1 down) or the greeter's (GREETER_ID).
 */
export class AgentHandles {
  private readonly byAgent = new Map<string, number>();
  private readonly byHandle = new Map<number, string>();
  private next = 1;

  /** The agent's handle, allocated on first use. */
  handleFor(agentId: string): number {
    let handle = this.byAgent.get(agentId);
    if (handle === undefined) {
      handle = this.next++;
      this.byAgent.set(agentId, handle);
      this.byHandle.set(handle, agentId);
    }
    return handle;
  }

  /** The agent of a character id, or null (not a runtime agent's character). */
  agentFor(handle: number | null): string | null {
    return handle === null ? null : (this.byHandle.get(handle) ?? null);
  }
}
