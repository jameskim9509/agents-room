import {
  type ReactNode,
  type RefObject,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

import type { RuntimeClientMessage } from '../../../core/src/messages.js';
import {
  CHARACTER_HIT_HEIGHT,
  CHARACTER_SITTING_OFFSET_PX,
  DESKTOP_DETAIL_PANEL_WIDTH,
  DESKTOP_PROJECT_PANEL_WIDTH,
} from '../constants.js';
import type { AgentsRoomBridge } from '../desktopBridge.js';
import type { OfficeState } from '../office/engine/officeState.js';
import { overlayProjection } from '../office/projection.js';
import { CharacterState } from '../office/types.js';
import { isE2E } from '../runtime.js';
import type { RuntimeStateStore } from '../runtimeState.js';
import { AgentLabels } from './AgentLabels.js';
import { deriveAgentViews } from './agentView.js';
import {
  agentSubject,
  markSent,
  permissionSubject,
  rejectionOf,
  type SentMarks,
} from './commandFeedback.js';
import { DetailPanel } from './DetailPanel.js';
import { ProjectPanel } from './ProjectPanel.js';
import type { RuntimeWorld } from './runtimeWorld.js';
import { useRuntimeState } from './useRuntimeState.js';

interface DesktopShellProps {
  store: RuntimeStateStore;
  bridge: AgentsRoomBridge;
  send: (command: RuntimeClientMessage) => void;
  world: RuntimeWorld;
  officeState: OfficeState;
  /** The office layout (and so its seats) is loaded: characters can be placed. */
  layoutReady: boolean;
  /** The world column's container, the one the canvas fills. */
  worldRef: RefObject<HTMLDivElement | null>;
  zoom: number;
  panRef: RefObject<{ x: number; y: number }>;
  /** The pixel world (canvas and its overlays), or the loading placeholder. */
  children: ReactNode;
}

/**
 * The desktop shell's main screen (ux.md 화면 구성, D7): three columns,
 * [ProjectPanel | pixel world | DetailPanel]. The world column keeps the
 * inherited office (zoom, pan, click); the side columns size with the window
 * between bounds, so the world stays usable when the window shrinks (SCRUM-22).
 *
 * The selected agent is the engine's selection (`selectedAgentId`, set by a
 * canvas click) mapped back to its agentId, so a character click and a card
 * click select the same thing (SCRUM-19). Opening a project selects its first
 * agent (ux.md 메인 화면).
 */
export function DesktopShell({
  store,
  bridge,
  send,
  world,
  officeState,
  layoutReady,
  worldRef,
  zoom,
  panRef,
  children,
}: DesktopShellProps) {
  const state = useRuntimeState(store);
  const views = useMemo(() => deriveAgentViews(state), [state]);

  // Every command goes out here, marked with what the store held then, so its
  // answer (a rejection, a check result) shows where it was asked (commandFeedback.ts).
  const [marks, setMarks] = useState<SentMarks>({});
  const sendCommand = useCallback(
    (command: RuntimeClientMessage) => {
      const mark = markSent(store.getState(), command);
      if (mark) setMarks((prev) => ({ ...prev, [mark[0]]: mark[1] }));
      send(command);
    },
    [store, send],
  );

  // The world follows the views, once its seats exist.
  useEffect(() => {
    if (layoutReady) world.apply(views);
  }, [layoutReady, views, world]);

  // The engine's selection, read every frame: a canvas click changes it outside React.
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);
  useEffect(() => {
    let frame = 0;
    const poll = () => {
      const agentId = world.agentOf(officeState.selectedAgentId);
      setSelectedAgentId((prev) => (prev === agentId ? prev : agentId));
      frame = requestAnimationFrame(poll);
    };
    frame = requestAnimationFrame(poll);
    return () => cancelAnimationFrame(frame);
  }, [officeState, world]);

  const select = (agentId: string | null) => {
    const id = agentId === null ? null : world.characterOf(agentId);
    officeState.selectedAgentId = id;
    officeState.cameraFollowId = id;
    setSelectedAgentId(id === null ? null : agentId);
  };

  // A newly opened project (or the snapshot's) opens its first agent in the Detail Panel.
  const autoSelectedFor = useRef<string | null>(null);
  const projectId = state.project?.projectId ?? null;
  const firstAgent = views[0]?.agent.agentId ?? null;
  useEffect(() => {
    if (!layoutReady || projectId === null || firstAgent === null) return;
    if (autoSelectedFor.current === projectId) return;
    if (world.characterOf(firstAgent) === null) return;
    autoSelectedFor.current = projectId;
    officeState.selectedAgentId = world.characterOf(firstAgent);
    officeState.cameraFollowId = officeState.selectedAgentId;
  }, [layoutReady, projectId, firstAgent, views, officeState, world]);

  // Korean UI: lets the browser pick Hangul glyphs for the font fallback (index.css).
  useEffect(() => {
    document.documentElement.lang = 'ko';
  }, []);

  // e2e observables (testHooks.ts): the runtime characters and where they are on screen.
  useEffect(() => {
    if (!isE2E) return;
    const hooks = (window.__pixelAgentsTestHooks ??= {});
    hooks.getRuntimeAgents = () => world.describe();
    hooks.getSelectedRuntimeAgent = () => world.agentOf(officeState.selectedAgentId);
    hooks.getCharacterClientPoint = (agentId) => {
      const id = world.characterOf(agentId);
      const ch = id === null ? undefined : officeState.characters.get(id);
      const el = worldRef.current;
      if (!ch || !el) return null;
      const rect = el.getBoundingClientRect();
      const project = overlayProjection(
        officeState.getLayout(),
        rect,
        zoom,
        panRef.current,
        window.devicePixelRatio || 1,
      );
      const sitting = ch.state === CharacterState.TYPE ? CHARACTER_SITTING_OFFSET_PX : 0;
      return {
        x: rect.left + project.toScreenX(ch.x),
        y: rect.top + project.toScreenY(ch.y + sitting - CHARACTER_HIT_HEIGHT / 2),
      };
    };
  }, [officeState, world, worldRef, zoom, panRef]);

  const selectedView = views.find((view) => view.agent.agentId === selectedAgentId) ?? null;
  const selectedRejection =
    selectedView && rejectionOf(state, marks, agentSubject(selectedView.agent.agentId));
  const promptRejection = selectedRejection?.command === 'sendPrompt' ? selectedRejection : null;

  return (
    <div className="w-full h-full flex bg-bg-dark" data-testid="desktop-shell">
      <aside
        className="desktop-type h-full shrink-0 bg-bg border-r-2 border-border min-h-0 overflow-hidden"
        style={{ width: DESKTOP_PROJECT_PANEL_WIDTH }}
      >
        <ProjectPanel
          state={state}
          views={views}
          marks={marks}
          selectedAgentId={selectedAgentId}
          onSelectAgent={select}
          bridge={bridge}
          send={sendCommand}
        />
      </aside>
      <main className="relative flex-1 min-w-0 h-full" data-testid="world-column">
        {children}
        {layoutReady && (
          <AgentLabels
            views={views}
            world={world}
            officeState={officeState}
            containerRef={worldRef}
            zoom={zoom}
            panRef={panRef}
          />
        )}
      </main>
      <aside
        className="desktop-type h-full shrink-0 bg-bg border-l-2 border-border min-h-0 overflow-hidden"
        style={{ width: DESKTOP_DETAIL_PANEL_WIDTH }}
      >
        <DetailPanel
          view={selectedView}
          projectPath={state.project?.path ?? null}
          send={sendCommand}
          promptRejection={promptRejection}
          permissionRejection={(permissionId) =>
            rejectionOf(state, marks, permissionSubject(permissionId))
          }
        />
      </aside>
    </div>
  );
}
