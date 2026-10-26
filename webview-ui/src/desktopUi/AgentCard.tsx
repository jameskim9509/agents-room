import type { CommandRejected, ConnectionChecked } from '../../../core/src/messages.js';
import { Button } from '../components/ui/Button.js';
import { agentIdentity, canCheck, canStart, canStop, STATUS_DOT_CLASS } from './agentControls.js';
import type { AgentView } from './agentView.js';
import {
  AGENT_STATUS_LABELS,
  ERROR_CODE_LABELS,
  READINESS_GUIDANCE,
  READINESS_LABELS,
  REJECT_REASON_LABELS,
  STAGE_LABELS,
  T,
} from './strings.js';

interface AgentCardProps {
  view: AgentView;
  selected: boolean;
  /** The runtime refused this card's last start, stop or check (ux.md 거절된 명령). */
  rejection: CommandRejected | null;
  /** The card's last "연결 확인": running, or its result (null: none asked, or none to show). */
  check: { pending: true } | { pending: false; result: ConnectionChecked | null } | null;
  onSelect: () => void;
  onStart: () => void;
  onStop: () => void;
  onCheck: () => void;
}

/** "연결 확인" result line: what the check saw, in the card's own words (ux.md 설정 상태). */
function checkResultText(result: ConnectionChecked): string {
  const parts = [READINESS_LABELS[result.readiness]];
  if (result.error) {
    parts.push(
      `${ERROR_CODE_LABELS[result.error.code]} (${T.errorStage}: ${STAGE_LABELS[result.error.stage]})`,
    );
  }
  if (result.authStatus) parts.push(result.authStatus.label);
  return parts.join(' · ');
}

/**
 * One agent of the project (ux.md 메인 화면, SCRUM-18, 31). A click anywhere
 * on the card selects the agent; its name row is the button that does so for
 * the keyboard, next to (not around) the start, stop and check buttons.
 */
export function AgentCard({
  view,
  selected,
  rejection,
  check,
  onSelect,
  onStart,
  onStop,
  onCheck,
}: AgentCardProps) {
  const { agent } = view;
  const identity = agentIdentity(view);
  const guidance = READINESS_GUIDANCE[agent.readiness];
  const checking = check?.pending === true;
  const startable = canStart(view) && !checking;
  const stoppable = canStop(view);
  const checkable = canCheck(view) && !checking;
  // A result is about the agent at rest: once it starts, the card's own state says more.
  const result = check && !check.pending && canCheck(view) ? check.result : null;
  return (
    <div
      onClick={onSelect}
      className={`border-2 p-6 flex flex-col gap-4 cursor-pointer ${
        selected ? 'border-accent bg-active-bg' : 'border-border bg-bg hover:bg-btn-hover'
      }`}
      data-testid="agent-card"
      data-agent-id={agent.agentId}
      data-status={view.status}
      data-selected={selected}
    >
      <button
        type="button"
        aria-pressed={selected}
        onClick={(e) => {
          e.stopPropagation();
          onSelect();
        }}
        className="flex items-center gap-6 min-w-0 p-0 bg-transparent border-0 text-left cursor-pointer"
        data-testid="agent-card-select"
      >
        <span
          className={`w-8 h-8 shrink-0 ${STATUS_DOT_CLASS[view.status]} ${
            view.status === 'running' ? 'pixel-pulse' : ''
          }`}
        />
        <span
          className="text-sm text-text truncate"
          title={identity.nameTitle ?? undefined}
          data-testid="agent-card-name"
        >
          {identity.name}
        </span>
        {identity.badge !== null && (
          <span
            className="text-2xs text-text-muted border-2 border-border px-4 shrink-0"
            title={T.harnessLabel}
            data-testid="agent-card-harness"
          >
            {identity.badge}
          </span>
        )}
      </button>
      <div className="text-2xs leading-tight">
        <div>
          <span className="text-text-muted">{T.readinessLabel}: </span>
          <span data-testid="agent-card-readiness">{READINESS_LABELS[agent.readiness]}</span>
        </div>
        {guidance && <div className="text-text-muted">{guidance}</div>}
        <div>
          <span className="text-text-muted">{T.statusLabel}: </span>
          <span data-testid="agent-card-status">{AGENT_STATUS_LABELS[view.status]}</span>
        </div>
      </div>
      <div className="flex gap-4 flex-wrap">
        <Button
          size="sm"
          variant={startable ? 'accent' : 'disabled'}
          disabled={!startable}
          className="text-xs!"
          onClick={(e) => {
            e.stopPropagation();
            onStart();
          }}
        >
          {T.startAgent}
        </Button>
        <Button
          size="sm"
          variant={stoppable ? 'default' : 'disabled'}
          disabled={!stoppable}
          className="text-xs!"
          onClick={(e) => {
            e.stopPropagation();
            onStop();
          }}
        >
          {T.stopAgent}
        </Button>
        <Button
          size="sm"
          variant={checkable ? 'default' : 'disabled'}
          disabled={!checkable}
          className="text-xs!"
          onClick={(e) => {
            e.stopPropagation();
            onCheck();
          }}
        >
          {T.checkConnection}
        </Button>
      </div>
      {checking && (
        <div className="text-2xs text-text-muted" data-testid="agent-card-checking">
          {T.checkingConnection}
        </div>
      )}
      {result && (
        <div className="text-2xs leading-tight" data-testid="agent-card-check">
          <span className="text-text-muted">{T.checkConnection}: </span>
          {checkResultText(result)}
        </div>
      )}
      {rejection && (
        <div
          className="text-2xs leading-tight text-status-error"
          role="alert"
          data-testid="agent-card-rejected"
        >
          {T.commandRejected}: {REJECT_REASON_LABELS[rejection.reason]}
        </div>
      )}
    </div>
  );
}
