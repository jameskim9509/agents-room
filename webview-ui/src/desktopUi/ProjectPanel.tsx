import { useRef, useState } from 'react';

import type {
  CommandRejected,
  ProjectInfo,
  ProjectOpenFailed,
  RuntimeClientMessage,
} from '../../../core/src/messages.js';
import { Button } from '../components/ui/Button.js';
import type { AgentsRoomBridge } from '../desktopBridge.js';
import type { RuntimeState } from '../runtimeState.js';
import { AgentCard } from './AgentCard.js';
import type { AgentView } from './agentView.js';
import { agentSubject, checkOf, rejectionOf, type SentMarks } from './commandFeedback.js';
import { FileTree } from './FileTree.js';
import { PROJECT_ERROR_LABELS, REJECT_REASON_LABELS, T } from './strings.js';

/** The results held when an `openProject` went out: a newer one is its answer. */
interface OpenAttempt {
  project: ProjectInfo | null;
  failed: ProjectOpenFailed | null;
  rejected: CommandRejected | null;
}

/** The card's commands; a refused `sendPrompt` shows in the Detail Panel instead. */
const CARD_COMMANDS: ReadonlySet<string> = new Set(['startAgent', 'stopAgent', 'checkConnection']);

interface ProjectPanelProps {
  state: RuntimeState;
  views: AgentView[];
  /** What the store held when each agent's last command went out (commandFeedback.ts). */
  marks: SentMarks;
  selectedAgentId: string | null;
  onSelectAgent: (agentId: string) => void;
  bridge: AgentsRoomBridge;
  send: (command: RuntimeClientMessage) => void;
}

/**
 * The left column (ux.md 시작 상태, 메인 화면; SCRUM-18, 31): before a project,
 * "프로젝트 열기" and the recent projects; after, the project's name, real path
 * and minimal file tree. The agent cards come last in both.
 *
 * "프로젝트 열기" asks the shell's folder picker, then sends `openProject` with
 * the picked path; the runtime validates it. A cancelled picker (`null`)
 * changes nothing. A refused path keeps the screen as it was and shows the
 * code's copy with a way to pick again.
 */
export function ProjectPanel({
  state,
  views,
  marks,
  selectedAgentId,
  onSelectAgent,
  bridge,
  send,
}: ProjectPanelProps) {
  const [attempt, setAttempt] = useState<OpenAttempt | null>(null);
  const [picking, setPicking] = useState(false);
  // The picker is async: the attempt must start from the state current when it returns.
  const stateRef = useRef(state);
  stateRef.current = state;

  const open = (path: string) => {
    const now = stateRef.current;
    setAttempt({
      project: now.project,
      failed: now.projectOpenFailed,
      rejected: now.commandRejected,
    });
    send({ type: 'openProject', path });
  };

  const pick = async () => {
    if (picking) return;
    setPicking(true);
    try {
      const path = await bridge.pickProjectFolder();
      if (path !== null) open(path);
    } catch (err) {
      console.error('[Webview] The folder picker failed', err);
    } finally {
      setPicking(false);
    }
  };

  const answered =
    attempt === null ||
    state.project !== attempt.project ||
    state.projectOpenFailed !== attempt.failed ||
    state.commandRejected !== attempt.rejected;
  const opening = !answered;
  const rejectedOpen =
    attempt !== null &&
    state.commandRejected !== attempt.rejected &&
    state.commandRejected?.command === 'openProject'
      ? state.commandRejected
      : null;
  const failed = state.projectOpenFailed;
  const project = state.project;
  const recent = state.recentProjects.filter((p) => p.projectId !== project?.projectId);

  return (
    <div className="h-full flex flex-col gap-8 p-8 min-h-0" data-testid="project-panel">
      <section className="flex flex-col gap-6 shrink-0">
        <h2 className="m-0 text-sm text-text-muted font-normal">{T.projectHeading}</h2>
        {project ? (
          <div className="flex flex-col gap-2" data-testid="project-info">
            <span className="text-base text-text break-all" data-testid="project-name">
              {project.name}
            </span>
            <span className="text-2xs text-text-muted break-all" data-testid="project-path">
              {project.path}
            </span>
          </div>
        ) : (
          <span className="text-sm text-text" data-testid="no-project">
            {T.noProject}
          </span>
        )}
        <Button
          variant={opening || picking ? 'disabled' : project ? 'default' : 'accent'}
          size={project ? 'sm' : 'md'}
          disabled={opening || picking}
          onClick={() => void pick()}
          className="self-start"
        >
          {T.openProject}
        </Button>
        {opening && <span className="text-2xs text-text-muted">{T.openingProject}</span>}
        {failed && (
          <div
            className="border-2 border-danger p-6 text-2xs"
            role="alert"
            data-testid="project-error"
          >
            <div className="text-status-error">{PROJECT_ERROR_LABELS[failed.code]}</div>
            <div className="text-text-muted break-all">{failed.path}</div>
            <div className="text-text">{T.pickAnotherFolder}</div>
          </div>
        )}
        {rejectedOpen && (
          <div
            className="border-2 border-danger p-6 text-2xs"
            role="alert"
            data-testid="project-rejected"
          >
            <div className="text-status-error">
              {rejectedOpen.reason === 'INVALID_STATE'
                ? T.stopAgentsFirst
                : `${T.commandRejected}: ${REJECT_REASON_LABELS[rejectedOpen.reason]}`}
            </div>
          </div>
        )}
      </section>

      {!project && recent.length > 0 && (
        <section className="flex flex-col gap-4 shrink-0" data-testid="recent-projects">
          <h2 className="m-0 text-sm text-text-muted font-normal">{T.recentProjects}</h2>
          <ul className="m-0 p-0 list-none flex flex-col gap-4">
            {recent.map((p) => (
              <li key={p.projectId}>
                <button
                  className="w-full text-left bg-btn-bg hover:bg-btn-hover border-2 border-transparent rounded-none cursor-pointer px-6 py-2 text-text"
                  disabled={opening || picking}
                  onClick={() => open(p.path)}
                  title={p.path}
                >
                  <span className="block text-xs truncate">{p.name}</span>
                  <span className="block text-2xs text-text-muted truncate">{p.path}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {project && (
        <section className="flex flex-col gap-4 min-h-0 flex-1">
          <h2 className="m-0 text-sm text-text-muted font-normal shrink-0">{T.filesHeading}</h2>
          <FileTree files={project.files} truncated={project.filesTruncated} />
        </section>
      )}

      <section
        className={`flex flex-col gap-4 min-h-0 ${project ? 'shrink-0 max-h-[45%]' : 'flex-1'}`}
        data-testid="agent-list"
      >
        <h2 className="m-0 text-sm text-text-muted font-normal shrink-0">{T.agentsHeading}</h2>
        {views.length === 0 ? (
          <span className="text-2xs text-text-muted">{T.agentsBeforeProject}</span>
        ) : (
          <div className="flex flex-col gap-6 overflow-auto pixel-scrollbar min-h-0">
            {views.map((view) => {
              const { agentId } = view.agent;
              const rejected = rejectionOf(state, marks, agentSubject(agentId));
              return (
                <AgentCard
                  key={agentId}
                  view={view}
                  selected={agentId === selectedAgentId}
                  rejection={rejected && CARD_COMMANDS.has(rejected.command) ? rejected : null}
                  check={checkOf(state, marks, agentId)}
                  onSelect={() => onSelectAgent(agentId)}
                  onStart={() => send({ type: 'startAgent', agentId })}
                  onStop={() => send({ type: 'stopAgent', agentId })}
                  onCheck={() => send({ type: 'checkConnection', agentId })}
                />
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
