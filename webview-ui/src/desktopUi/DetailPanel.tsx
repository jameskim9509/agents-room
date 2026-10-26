import { type ReactNode, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';

import type {
  CommandRejected,
  PermissionRequestedData,
  RuntimeClientMessage,
} from '../../../core/src/messages.js';
import { Button } from '../components/ui/Button.js';
import { DETAIL_EVENTS_MAX_HEIGHT_PX, EVENTS_END_SLACK_PX } from '../constants.js';
import { agentIdentity, STATUS_DOT_CLASS } from './agentControls.js';
import type { AgentView } from './agentView.js';
import {
  currentToolText,
  lastRunToolTitle,
  providerText,
  rawInputPreview,
  reportedModel,
  reportedModeName,
  runStatus,
  type RunTone,
  usageText,
} from './detailText.js';
import { eventRows, type EventRowTone } from './eventRows.js';
import {
  AGENT_STATUS_LABELS,
  ERROR_ACTIONS,
  ERROR_CODE_LABELS,
  PERMISSION_OPTION_LABELS,
  READINESS_LABELS,
  REJECT_REASON_LABELS,
  SESSION_STATE_LABELS,
  STAGE_LABELS,
  START_STAGE_LABELS,
  T,
  TOOL_STATUS_LABELS,
  UNFINISHED_TOOL_LABELS,
} from './strings.js';

const RUN_TONE_CLASS: Record<RunTone, string> = {
  progress: 'text-status-active',
  waiting: 'text-status-permission',
  completed: 'text-status-success',
  cancelled: 'text-text-muted',
  warning: 'text-warning',
  error: 'text-status-error',
  none: 'text-text-muted',
};

const EVENT_TONE_CLASS: Record<EventRowTone, string> = {
  normal: 'text-text',
  muted: 'text-text-muted',
  active: 'text-status-active',
  permission: 'text-status-permission',
  success: 'text-status-success',
  warning: 'text-warning',
  error: 'text-status-error',
};

const START_ORDER = ['spawn', 'initialize', 'authenticate', 'session_new'] as const;

function Section({
  title,
  children,
  testId,
}: {
  title: string;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <section className="flex flex-col gap-4" data-testid={testId}>
      <h3 className="m-0 text-xs text-text-muted font-normal">{title}</h3>
      {children}
    </section>
  );
}

function Field({
  label,
  children,
  testId,
}: {
  label: string;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <div className="text-2xs leading-tight break-words">
      <span className="text-text-muted">{label}: </span>
      <span className="text-text" data-testid={testId}>
        {children}
      </span>
    </div>
  );
}

function StartingStages({ view, onStop }: { view: AgentView; onStop: () => void }) {
  const current = view.startingStage;
  return (
    <div className="flex flex-col gap-4" data-testid="detail-starting">
      <span className="text-2xs text-text-muted">{T.startingStages}</span>
      <ol className="m-0 p-0 list-none flex flex-wrap gap-4 text-2xs">
        {START_ORDER.map((stage) => (
          <li
            key={stage}
            className={`border-2 px-4 ${
              stage === current ? 'border-accent text-text' : 'border-border text-text-muted'
            }`}
            aria-current={stage === current ? 'step' : undefined}
          >
            {START_STAGE_LABELS[stage]}
          </li>
        ))}
      </ol>
      {current === 'authenticate' && (
        <div className="flex flex-col gap-4 border-2 border-status-permission p-6">
          <span className="text-xs text-text" data-testid="detail-login">
            {T.completeBrowserLogin}
          </span>
          <Button size="sm" className="self-start" onClick={onStop}>
            {T.stopAgent}
          </Button>
        </div>
      )}
    </div>
  );
}

function ErrorBlock({ view }: { view: AgentView }) {
  const error = view.lastError;
  if (!error) return null;
  return (
    <div
      className="border-2 border-danger p-6 flex flex-col gap-2 text-2xs"
      role="alert"
      data-testid="detail-error"
    >
      <span className="text-xs text-status-error" data-testid="detail-error-code">
        {ERROR_CODE_LABELS[error.code]}
      </span>
      <span>
        <span className="text-text-muted">{T.errorStage}: </span>
        {STAGE_LABELS[error.stage]}
      </span>
      <span className="break-words">
        <span className="text-text-muted">{T.errorCause}: </span>
        {error.message || T.unspecified}
      </span>
      <span>
        <span className="text-text-muted">{T.errorAction}: </span>
        {ERROR_ACTIONS[error.code] ?? T.retryAction}
      </span>
    </div>
  );
}

/** "요청을 처리하지 못했습니다: <reason>" under what the runtime refused (ux.md 거절된 명령). */
function Rejected({ rejection, testId }: { rejection: CommandRejected; testId: string }) {
  return (
    <span className="text-2xs text-status-error" role="alert" data-testid={testId}>
      {T.commandRejected}: {REJECT_REASON_LABELS[rejection.reason]}
    </span>
  );
}

function PermissionRequest({
  view,
  request,
  projectPath,
  answered,
  rejection,
  onAnswer,
}: {
  view: AgentView;
  request: PermissionRequestedData;
  projectPath: string | null;
  /** An answer went out and the runtime has not closed the request yet. */
  answered: boolean;
  /** The runtime refused the last answer: the buttons work again. */
  rejection: CommandRejected | null;
  onAnswer: (optionId: string) => void;
}) {
  const { toolCall, options } = request;
  const input = rawInputPreview(toolCall.rawInput);
  const unclear = !toolCall.title || options.length === 0;
  return (
    <div
      className="border-2 border-status-permission p-6 flex flex-col gap-4"
      data-testid="permission-request"
      data-permission-id={request.permissionId}
    >
      <span className="text-xs text-status-permission">{T.permissionHeading}</span>
      <Field label={T.requestingAgent}>{view.agent.name}</Field>
      <Field label={T.requestTarget} testId="permission-target">
        {toolCall.title ?? T.unspecified}
        {input && <span className="block text-text-muted break-all">{input}</span>}
      </Field>
      <Field label={T.requestCwd}>{projectPath ?? T.unspecified}</Field>
      {unclear && <span className="text-2xs text-warning">{T.requestUnclear}</span>}
      {rejection && <Rejected rejection={rejection} testId="permission-rejected" />}
      <div className="flex flex-col gap-2">
        <span className="text-2xs text-text-muted">{T.requestOptions}</span>
        <div className="flex flex-wrap gap-4">
          {options.map((option) => (
            <Button
              key={option.optionId}
              size="sm"
              variant={
                answered ? 'disabled' : option.kind.startsWith('allow') ? 'accent' : 'default'
              }
              disabled={answered}
              title={option.name}
              onClick={() => onAnswer(option.optionId)}
              data-option-kind={option.kind}
            >
              {PERMISSION_OPTION_LABELS[option.kind]}
            </Button>
          ))}
        </div>
      </div>
    </div>
  );
}

function EventList({ view }: { view: AgentView }) {
  const { events, answeredOptions, lastSeq } = view;
  const rows = useMemo(() => eventRows(events, answeredOptions), [events, answeredOptions]);
  const listRef = useRef<HTMLOListElement>(null);
  // Follow the newest row while the list is scrolled to its end, as a log does
  // (a streaming message grows its row, so follow every event, not every row).
  const stuckToEnd = useRef(true);
  useLayoutEffect(() => {
    const list = listRef.current;
    if (list && stuckToEnd.current) list.scrollTop = list.scrollHeight;
  }, [lastSeq]);
  if (rows.length === 0) return <span className="text-2xs text-text-muted">{T.noEvents}</span>;
  return (
    <ol
      ref={listRef}
      className="m-0 p-0 list-none flex flex-col gap-2 overflow-auto pixel-scrollbar"
      style={{ maxHeight: DETAIL_EVENTS_MAX_HEIGHT_PX }}
      onScroll={(e) => {
        const list = e.currentTarget;
        stuckToEnd.current =
          list.scrollTop + list.clientHeight >= list.scrollHeight - EVENTS_END_SLACK_PX;
      }}
      data-testid="detail-events"
    >
      {rows.map((row) => (
        <li
          key={row.seq}
          className={`text-2xs leading-tight break-words ${EVENT_TONE_CLASS[row.tone]}`}
          data-seq={row.seq}
          data-kind={row.kind}
        >
          {row.collapsed ? (
            <details>
              <summary className="cursor-pointer">{row.label}</summary>
              <span className="text-text-muted">{row.text}</span>
            </details>
          ) : (
            <>
              <span className="text-text-muted">{row.label}: </span>
              {row.text}
            </>
          )}
        </li>
      ))}
    </ol>
  );
}

function PromptInput({
  view,
  send,
  rejection,
}: {
  view: AgentView;
  send: (c: RuntimeClientMessage) => void;
  /** The runtime refused the last prompt: its text stays in the box. */
  rejection: CommandRejected | null;
}) {
  const [text, setText] = useState('');
  // The text sent and the run it went out after, until the runtime answers.
  const [sent, setSent] = useState<{ text: string; afterRun: string | null } | null>(null);
  const ready = view.status === 'ready';
  // The newest run the panel knows. A short run can start and end between two
  // renders (they are coalesced per frame), so "a new run" is a new id here,
  // not "a run in progress".
  const latestRun = view.runId ?? view.lastRun?.runId ?? null;
  const canSend = ready && sent === null && text.trim() !== '';
  const submit = () => {
    if (!canSend) return;
    send({ type: 'sendPrompt', agentId: view.agent.agentId, text });
    setSent({ text, afterRun: latestRun });
  };
  useEffect(() => {
    if (sent === null) return;
    if (latestRun !== sent.afterRun) {
      // Accepted: the input empties, unless the user already typed something else.
      setText((current) => (current === sent.text ? '' : current));
      setSent(null);
    } else if (view.status !== 'ready' && view.status !== 'running') {
      // The agent stopped or failed first: no run is coming for this text.
      setSent(null);
    }
  }, [sent, latestRun, view.status]);
  useEffect(() => {
    // Refused: the text stays, and can go again.
    if (rejection) setSent(null);
  }, [rejection]);
  return (
    <div
      className="flex flex-col gap-4 p-8 border-t-2 border-border shrink-0"
      data-testid="prompt-input"
    >
      <textarea
        className="w-full box-border resize-none bg-bg-dark text-text border-2 border-border rounded-none p-4 text-xs"
        rows={2}
        value={text}
        placeholder={T.promptPlaceholder}
        aria-label={T.promptPlaceholder}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            submit();
          }
        }}
      />
      <div className="flex items-center gap-6 min-w-0">
        <Button
          size="sm"
          variant={canSend ? 'accent' : 'disabled'}
          disabled={!canSend}
          onClick={submit}
          className="shrink-0 whitespace-nowrap"
        >
          {T.sendPrompt}
        </Button>
        {!ready && (
          <span className="text-2xs text-text-muted min-w-0">{T.promptOnlyWhenReady}</span>
        )}
      </div>
      {rejection && <Rejected rejection={rejection} testId="prompt-rejected" />}
    </div>
  );
}

interface DetailPanelProps {
  view: AgentView | null;
  projectPath: string | null;
  send: (command: RuntimeClientMessage) => void;
  /** The runtime refused this agent's last prompt. */
  promptRejection: CommandRejected | null;
  /** The runtime refused the last answer to a permission request. */
  permissionRejection: (permissionId: string) => CommandRejected | null;
}

/**
 * The right column for the selected agent (ux.md Detail Panel; SCRUM-19, 21):
 * header, then what needs the user now (the start stages with the sign-in
 * step, the error, the permission requests), connection info (collapsed), the
 * current run, recent events, Plan, the final response and the run result,
 * usage, then the task input. Only what the runtime reported is shown (ux.md
 * R1–R12).
 */
export function DetailPanel({
  view,
  projectPath,
  send,
  promptRejection,
  permissionRejection,
}: DetailPanelProps) {
  // Answered permission requests, until the runtime resolves them (no double answer).
  const [answered, setAnswered] = useState<ReadonlySet<string>>(new Set());
  // What needs the user leads the panel; when something new of it appears (a
  // start, an error, a permission request), scroll back up to it, wherever the
  // user had scrolled the run's events to (ux.md Sprint 0 구현, Detail Panel 순서).
  const bodyRef = useRef<HTMLDivElement>(null);
  const urgent = view
    ? [
        view.status === 'starting' ? 'starting' : '',
        view.lastError?.code ?? '',
        ...view.pendingPermissions.map((p) => p.permissionId),
      ].join('|')
    : '';
  useLayoutEffect(() => {
    if (urgent.replaceAll('|', '') !== '' && bodyRef.current) bodyRef.current.scrollTop = 0;
  }, [urgent]);
  if (!view) {
    return (
      <div
        className="h-full flex items-center justify-center p-8 text-center"
        data-testid="detail-panel"
      >
        <span className="text-xs text-text-muted">{T.selectAgent}</span>
      </div>
    );
  }
  const { agent } = view;
  const identity = agentIdentity(view);
  const tool = currentToolText(view);
  const run = runStatus(view);
  const model = reportedModel(view);
  const mode = reportedModeName(view);
  const usage = usageText(view);
  const stop = () => send({ type: 'stopAgent', agentId: agent.agentId });
  const answer = (permissionId: string, optionId: string) => {
    setAnswered((prev) => new Set(prev).add(permissionId));
    send({ type: 'respondPermission', permissionId, optionId });
  };

  return (
    <div
      className="h-full flex flex-col min-h-0"
      data-testid="detail-panel"
      data-agent-id={agent.agentId}
    >
      <header className="p-8 border-b-2 border-border flex flex-col gap-4 shrink-0">
        <span className="text-2xs text-text-muted">{T.agentName}</span>
        <div className="flex items-center gap-6 min-w-0">
          <span className={`w-8 h-8 shrink-0 ${STATUS_DOT_CLASS[view.status]}`} />
          <span
            className="text-base text-text truncate"
            title={identity.nameTitle ?? undefined}
            data-testid="detail-agent-name"
          >
            {identity.name}
          </span>
          {identity.badge !== null && (
            <span
              className="text-2xs text-text-muted border-2 border-border px-4 shrink-0"
              title={T.harnessLabel}
              data-testid="detail-agent-harness"
            >
              {identity.badge}
            </span>
          )}
        </div>
        <div className="text-2xs flex flex-wrap gap-x-8">
          <span>
            <span className="text-text-muted">{T.readinessLabel}: </span>
            {READINESS_LABELS[agent.readiness]}
          </span>
          <span>
            <span className="text-text-muted">{T.statusLabel}: </span>
            <span data-testid="detail-status">{AGENT_STATUS_LABELS[view.status]}</span>
          </span>
        </div>
      </header>

      <div
        ref={bodyRef}
        className="flex-1 min-h-0 overflow-auto pixel-scrollbar p-8 flex flex-col gap-12"
      >
        {view.status === 'starting' && <StartingStages view={view} onStop={stop} />}
        <ErrorBlock view={view} />

        {/* Urgent first: until the SCRUM-33 dialog, the answer buttons lead the panel. */}
        {view.pendingPermissions.length > 0 && (
          <div className="flex flex-col gap-6" data-testid="detail-permissions">
            {view.pendingPermissions.map((request) => {
              const rejection = permissionRejection(request.permissionId);
              return (
                <PermissionRequest
                  key={request.permissionId}
                  view={view}
                  request={request}
                  projectPath={projectPath}
                  answered={answered.has(request.permissionId) && rejection === null}
                  rejection={rejection}
                  onAnswer={(optionId) => answer(request.permissionId, optionId)}
                />
              );
            })}
          </div>
        )}

        {/* Collapsed at first, so the current run stays in view on a small window. */}
        <details data-testid="detail-connection">
          <summary className="cursor-pointer text-xs text-text-muted">
            {T.connectionHeading}
          </summary>
          <div className="flex flex-col gap-4 mt-4">
            <Field label={T.modelProvider}>{providerText(agent.providerId)}</Field>
            <Field label={T.model}>
              {agent.modelId ?? T.modelHarnessDefault}
              {model && ` (${T.modelReported}: ${model})`}
            </Field>
            <Field label={T.authDisplay}>{view.authStatus?.label ?? T.unspecified}</Field>
            <Field label={T.workingFolder}>{projectPath ?? T.unspecified}</Field>
            <Field label={T.enforcedRestriction}>
              {T.osRestrictionNone} ·{' '}
              {mode ? `${T.harnessRestrictionMode}: ${mode}` : T.harnessRestrictionUnknown}
            </Field>
          </div>
        </details>

        <Section title={T.currentRunHeading} testId="detail-current-run">
          <Field label={T.taskRequest} testId="detail-prompt">
            {view.prompt ?? T.unspecified}
          </Field>
          <Field label={T.sessionState} testId="detail-session-state">
            {SESSION_STATE_LABELS[view.sessionState]}
          </Field>
          <Field label={T.currentTool} testId="detail-current-tool">
            {tool ? `${tool.title} · ${tool.behaviour} · ${tool.status}` : T.noStatusInfo}
          </Field>
          <Field label={T.runResult} testId="detail-run-status">
            <span className={RUN_TONE_CLASS[run.tone]} data-tone={run.tone}>
              {run.text}
            </span>
          </Field>
          {(!agent.reportsToolDetails || !agent.reportsSubagentSessions) && (
            <div className="flex flex-col gap-2 text-2xs text-warning" data-testid="detail-notices">
              {!agent.reportsToolDetails && <span>{T.toolDetailsUnavailable}</span>}
              {!agent.reportsSubagentSessions && (
                <span>
                  {T.subagentInfoUnavailable} · {T.partialActivity}
                </span>
              )}
            </div>
          )}
        </Section>

        <Section title={T.recentEvents}>
          <EventList view={view} />
        </Section>

        {view.plan && (
          <Section title={T.plan} testId="detail-plan">
            <ol className="m-0 pl-16 text-2xs">
              {view.plan.map((entry, i) => (
                <li key={i}>
                  {entry.content}{' '}
                  <span className="text-text-muted">
                    (
                    {entry.status in TOOL_STATUS_LABELS
                      ? TOOL_STATUS_LABELS[entry.status as keyof typeof TOOL_STATUS_LABELS]
                      : entry.status}
                    )
                  </span>
                </li>
              ))}
            </ol>
          </Section>
        )}

        {view.lastRun && view.runId === null && (
          <Section title={T.finalResponse} testId="detail-final">
            {view.responsePartial && (
              <span className="text-2xs text-warning" data-testid="detail-response-partial">
                {T.responsePartial}
              </span>
            )}
            {(view.response !== null || !view.responsePartial) && (
              <span
                className="text-xs text-text whitespace-pre-wrap break-words"
                data-testid="detail-response"
              >
                {view.response ?? T.noResponse}
              </span>
            )}
            {view.lastRun.unfinishedToolCalls.length > 0 && (
              <Field label={T.unfinishedTools}>
                {view.lastRun.unfinishedToolCalls.map((call) => (
                  <span key={call.toolCallId} className="block">
                    {lastRunToolTitle(view, call.toolCallId)} ·{' '}
                    {UNFINISHED_TOOL_LABELS[call.derivedStatus]}
                  </span>
                ))}
              </Field>
            )}
          </Section>
        )}

        {usage && (
          <Section title={T.contextUsage}>
            <span className="text-2xs">{usage}</span>
          </Section>
        )}
      </div>

      <PromptInput key={agent.agentId} view={view} send={send} rejection={promptRejection} />
    </div>
  );
}
