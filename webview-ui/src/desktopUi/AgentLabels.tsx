import { type RefObject, useEffect, useState } from 'react';

import {
  AGENT_LABEL_OFFLINE_OPACITY,
  AGENT_LABEL_RISE_WORLD,
  CHARACTER_SITTING_OFFSET_PX,
} from '../constants.js';
import type { OfficeState } from '../office/engine/officeState.js';
import { overlayProjection } from '../office/projection.js';
import { CharacterState } from '../office/types.js';
import { harnessBadge } from './agentControls.js';
import type { AgentView } from './agentView.js';
import type { RuntimeWorld } from './runtimeWorld.js';
import { AGENT_STATUS_LABELS } from './strings.js';

interface AgentLabelsProps {
  views: AgentView[];
  world: RuntimeWorld;
  officeState: OfficeState;
  containerRef: RefObject<HTMLDivElement | null>;
  zoom: number;
  panRef: RefObject<{ x: number; y: number }>;
}

/**
 * Every agent's name label and harness badge, always shown above its character
 * so several agents read apart (ux.md 여러 에이전트 구분, SCRUM-28), with its
 * status. Re-renders every animation frame to follow the characters, like the
 * inherited ToolOverlay; clicks pass through to the canvas.
 */
export function AgentLabels({
  views,
  world,
  officeState,
  containerRef,
  zoom,
  panRef,
}: AgentLabelsProps) {
  const [, setTick] = useState(0);
  useEffect(() => {
    let frame = 0;
    const tick = () => {
      setTick((n) => n + 1);
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, []);

  const el = containerRef.current;
  if (!el) return null;
  const project = overlayProjection(
    officeState.getLayout(),
    el.getBoundingClientRect(),
    zoom,
    panRef.current,
    window.devicePixelRatio || 1,
  );

  return (
    <div className="desktop-type absolute inset-0 pointer-events-none overflow-hidden">
      {views.map((view) => {
        const id = world.characterOf(view.agent.agentId);
        const ch = id === null ? undefined : officeState.characters.get(id);
        if (!ch) return null;
        const sittingOffset = ch.state === CharacterState.TYPE ? CHARACTER_SITTING_OFFSET_PX : 0;
        const selected = officeState.selectedAgentId === id;
        return (
          <div
            key={view.agent.agentId}
            className={`absolute -translate-x-1/2 -translate-y-full pixel-panel px-4 whitespace-nowrap text-2xs leading-tight flex items-center gap-4 ${
              selected ? 'border-accent!' : ''
            }`}
            style={{
              left: project.toScreenX(ch.x),
              top: project.toScreenY(ch.y + sittingOffset - AGENT_LABEL_RISE_WORLD),
              opacity: view.status === 'offline' ? AGENT_LABEL_OFFLINE_OPACITY : 1,
              zIndex: selected ? 42 : 41,
            }}
            data-testid="agent-label"
            data-agent-id={view.agent.agentId}
          >
            <span className="text-text">{view.agent.name}</span>
            <span className="text-text-muted">{harnessBadge(view)}</span>
            <span className="text-text-muted">· {AGENT_STATUS_LABELS[view.status]}</span>
          </div>
        );
      })}
    </div>
  );
}
