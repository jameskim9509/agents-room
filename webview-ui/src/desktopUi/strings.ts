/**
 * The desktop UI's Korean copy (docs/ux.md 문구 목록). Jira strings are kept
 * letter for letter; the rest are ux.md's proposals (제안). A string missing
 * from ux.md is added there first, then here. Nothing else in desktopUi/
 * holds UI text.
 */
import type {
  AgentStatus,
  ErrorCode,
  PermissionOptionKind,
  ProjectErrorCode,
  Readiness,
  RejectReason,
  RunOutcome,
  Stage,
  ToolCallStatus,
} from '../../../core/src/messages.js';
import type { SessionState, ToolBehaviour } from './agentView.js';

export const T = {
  // ── Project panel (SCRUM-18, 31) ──
  projectHeading: '프로젝트',
  noProject: '프로젝트 없음',
  openProject: '프로젝트 열기',
  openingProject: '프로젝트를 여는 중',
  recentProjects: '최근 프로젝트',
  pickAnotherFolder: '다른 폴더를 선택하세요',
  stopAgentsFirst: '에이전트를 모두 종료한 뒤 다른 프로젝트를 여세요',
  filesHeading: '파일',
  filesEmpty: '빈 폴더',
  filesTruncated: '항목이 많아 일부만 표시합니다',
  agentsHeading: '에이전트',
  agentsBeforeProject: '프로젝트를 열면 에이전트가 표시됩니다',

  // ── Agent card and Detail Panel header ──
  startAgent: '에이전트 시작',
  stopAgent: '에이전트 종료',
  checkConnection: '연결 확인',
  checkingConnection: '연결 확인 중',
  readinessLabel: '설정 상태',
  statusLabel: '에이전트 상태',
  harnessLabel: '실행기',

  // ── Detail Panel (SCRUM-19, 21) ──
  selectAgent: '캐릭터나 에이전트 카드를 선택하세요',
  agentName: 'Agent명',
  connectionHeading: '연결 정보',
  modelProvider: '모델 제공자',
  model: '모델',
  modelHarnessDefault: '실행기 기본값',
  modelReported: '어댑터 보고',
  authDisplay: '인증 표시값',
  workingFolder: '작업 폴더 설정',
  enforcedRestriction: '실제 OS/Harness 강제 제한 여부',
  osRestrictionNone: 'OS 제한 없음',
  harnessRestrictionUnknown: '실제 제한 여부 확인 불가',
  harnessRestrictionMode: '실행기 모드',
  currentRunHeading: '현재 실행',
  taskRequest: '작업 요청',
  sessionState: '세션 상태',
  currentTool: '현재 Tool',
  noStatusInfo: '상태 정보 없음',
  unspecified: '미지정',
  recentEvents: '최근 이벤트',
  noEvents: '받은 이벤트 없음',
  reportedThought: '에이전트가 보고한 생각',
  plan: 'Plan',
  finalResponse: '최종 응답',
  runResult: '실행 결과',
  noResponse: '응답 없음',
  responsePartial: '앞부분을 받지 못한 응답입니다',
  contextUsage: '컨텍스트 사용량',
  cost: '비용',
  unfinishedTools: '끝나지 않은 Tool 호출',
  toolDetailsUnavailable: '이 실행기는 세부 활동을 제공하지 않습니다',
  subagentInfoUnavailable: '하위 Agent 정보 미제공',
  partialActivity: '일부 활동만 관찰 가능',
  startingStages: '시작 단계',
  completeBrowserLogin: '브라우저에서 ChatGPT 로그인을 완료하세요',
  errorStage: '단계',
  errorCause: '원인',
  errorAction: '조치',
  retryAction: '"에이전트 시작"으로 다시 시작하세요',
  commandRejected: '요청을 처리하지 못했습니다',

  // ── Prompt input ──
  promptPlaceholder: '작업을 입력하세요',
  sendPrompt: '보내기',
  promptOnlyWhenReady: '에이전트가 대기 상태일 때 보낼 수 있습니다',

  // ── Permission request (SCRUM-33 minimal, D16) ──
  permissionHeading: '권한 요청',
  requestingAgent: '요청 에이전트',
  requestTarget: '실제 명령 또는 대상',
  requestCwd: '작업 디렉터리',
  requestOptions: '승인 선택지',
  requestUnclear: '요청 정보가 불분명합니다',
} as const;

export const AGENT_STATUS_LABELS: Record<AgentStatus, string> = {
  offline: '꺼짐',
  starting: '시작 중',
  ready: '대기',
  running: '실행 중',
  awaiting_permission: '승인 대기',
  cancelling: '중지 중',
  error: '오류',
};

export const READINESS_LABELS: Record<Readiness, string> = {
  configured: '설정 완료',
  auth_required: '인증 필요',
  unsupported: '지원되지 않음',
  executable_missing: '에이전트 실행기 설치/연결 필요',
};

/** One guidance line under a readiness that is not `configured` (ux.md 설정 상태 표). */
export const READINESS_GUIDANCE: Partial<Record<Readiness, string>> = {
  auth_required: '"에이전트 시작"이 실행기의 로그인 절차를 엽니다',
  unsupported: '이 실행기와 모델 제공자·인증 방식 조합은 실행할 수 없습니다',
  executable_missing: '실행기를 설치하거나 연결한 뒤 프로젝트를 다시 여세요',
};

export const SESSION_STATE_LABELS: Record<SessionState, string> = {
  none: '세션 없음',
  open: '세션 열림',
  ended_stopped: '세션 종료됨(중지)',
  ended_exited: '세션 종료됨(비정상 종료)',
  ended_aborted: '세션 종료됨(중단)',
};

export const RUN_OUTCOME_LABELS: Record<RunOutcome, string> = {
  completed: '완료',
  cancelled: '취소됨',
  refused: '거절됨',
  limit_reached: '한도 도달',
  failed: '실행 실패',
};

/** A failed run whose adapter died (R7: never "완료"). */
export const RUN_FAILED_PROCESS_EXITED = '에이전트 프로세스가 종료됨';

export const UNFINISHED_TOOL_LABELS = {
  cancelled: '취소됨(클라이언트 판단)',
  incomplete: '미완료',
} as const;

/** Start stages in order (ux.md 상태 어휘, `starting`). */
export const START_STAGE_LABELS: Partial<Record<Stage, string>> = {
  spawn: '어댑터 실행',
  initialize: 'ACP 초기화',
  authenticate: '인증 확인',
  session_new: '세션 생성',
};

export const STAGE_LABELS: Record<Stage, string> = {
  spawn: '어댑터 실행',
  initialize: 'ACP 초기화',
  authenticate: '인증 확인',
  session_new: '세션 생성',
  prompt: '실행',
  running: '대기',
  config: '설정 확인',
  check: '연결 확인',
};

export const ERROR_CODE_LABELS: Record<ErrorCode, string> = {
  EXECUTABLE_MISSING: '에이전트 실행기 설치/연결 필요',
  ADAPTER_START_FAILED: '어댑터 실행 실패',
  PROTOCOL_UNSUPPORTED: 'ACP 초기화 실패: 지원하지 않는 ACP 버전',
  ACP_INIT_FAILED: 'ACP 초기화 실패',
  AUTH_REQUIRED: '인증 필요',
  AUTH_FAILED: '인증 실패',
  SESSION_CREATE_FAILED: '세션 생성 실패',
  PROMPT_FAILED: '실행 실패',
  PROCESS_EXITED: '에이전트 프로세스가 종료됨',
  PROTOCOL_ERROR: '잘못된 ACP 메시지',
  MODEL_INCOMPATIBLE: '지원되지 않음',
  BEDROCK_ACCESS_DENIED: 'Bedrock 모델 접근 거부',
  CHECK_UNAVAILABLE: '검사 불가',
  AWS_CREDENTIALS_UNAVAILABLE: 'AWS 자격증명을 불러오지 못함',
  AWS_CREDENTIALS_INVALID: 'AWS 자격증명이 올바르지 않음',
  BEDROCK_MODEL_UNAVAILABLE: 'Bedrock 모델을 쓸 수 없음',
  NETWORK: '네트워크 오류',
  THROTTLED: '요청 한도 초과',
  TIMEOUT: '시간 초과',
};

/** The action line under an error (ux.md: 문구 아래에 원인과 조치를 한 줄씩). */
export const ERROR_ACTIONS: Partial<Record<ErrorCode, string>> = {
  EXECUTABLE_MISSING: '실행기를 설치하거나 연결한 뒤 프로젝트를 다시 여세요',
  AUTH_REQUIRED: '"에이전트 시작"으로 로그인을 다시 하세요',
  AUTH_FAILED: '"에이전트 시작"으로 로그인을 다시 하세요',
  MODEL_INCOMPATIBLE: '실행기와 모델 제공자·인증 방식 조합을 확인하세요',
};

export const PROJECT_ERROR_LABELS: Record<ProjectErrorCode, string> = {
  PATH_NOT_ABSOLUTE: '폴더 경로가 올바르지 않습니다',
  PATH_NOT_FOUND: '폴더를 찾을 수 없습니다',
  PATH_NOT_DIRECTORY: '폴더가 아닙니다',
  PATH_NOT_READABLE: '폴더를 읽을 수 없습니다',
};

export const REJECT_REASON_LABELS: Record<RejectReason, string> = {
  UNKNOWN_PROJECT: '알 수 없는 프로젝트',
  UNKNOWN_AGENT: '알 수 없는 에이전트',
  UNKNOWN_PERMISSION: '이미 끝난 권한 요청',
  INVALID_STATE: '지금 상태에서는 할 수 없음',
  INVALID_MESSAGE: '잘못된 요청',
  NOT_IMPLEMENTED: '아직 지원하지 않음',
};

/** Permission choices, labelled by kind only (ux.md 권한 요청 대화상자). */
export const PERMISSION_OPTION_LABELS: Record<PermissionOptionKind, string> = {
  allow_once: '이번만 허용',
  allow_always: '항상 허용',
  reject_once: '거부',
  reject_always: '항상 거부',
};

/** The Jira behaviour of a tool kind (ux.md 행동 매핑). */
export const TOOL_BEHAVIOUR_LABELS: Record<ToolBehaviour, string> = {
  search: '검색',
  read: '읽기',
  edit: '편집',
  execute: '실행',
  other: '기타',
};

export const TOOL_STATUS_LABELS: Record<ToolCallStatus, string> = {
  pending: '시작 전',
  in_progress: '진행 중',
  completed: '완료',
  failed: '실패',
};

/** Model provider names (architecture.md 용어집). Unknown ids show as they are. */
export const PROVIDER_LABELS: Record<string, string> = {
  openai: 'OpenAI',
  'amazon-bedrock': 'Amazon Bedrock (mock)',
};

/** Rows of 최근 이벤트 (ux.md 최근 이벤트 행). */
export const EVENT_LABELS = {
  status: '상태',
  starting: '시작 단계',
  sessionStarted: '세션 열림',
  error: '오류',
  authStatus: '인증 상태',
  runStarted: '작업 요청',
  runEnded: '실행 결과',
  userMessage: '사용자 메시지',
  agentMessage: '에이전트 메시지',
  toolCall: 'Tool 호출',
  toolCallUpdate: 'Tool 갱신',
  plan: 'Plan 보고',
  commands: '명령 목록 보고',
  mode: '모드',
  configOptions: '설정 선택지 보고',
  sessionInfo: '세션 제목',
  usage: '사용량',
  permissionRequested: '권한 요청',
  permissionResolved: '권한 응답',
  permissionCancelled: '취소됨',
  unknownUpdate: '알 수 없는 보고',
  exitCode: '종료 코드',
  items: '개',
} as const;
