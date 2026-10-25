/**
 * AUTO-GENERATED FROM core/asyncapi.yaml. DO NOT EDIT MANUALLY.
 *
 * Run `npm run asyncapi:generate` to regenerate.
 *
 * Source of truth: the yaml at core/asyncapi.yaml.
 * Editors and clients in any language can consume the spec directly.
 */

export type ServerMessage =
  | ProviderCapabilities
  | AgentCreated
  | AgentClosed
  | AgentSelected
  | ExistingAgents
  | LegacyAgentStatus
  | AgentToolStart
  | AgentToolDone
  | AgentToolsClear
  | AgentToolPermission
  | AgentToolPermissionClear
  | SubagentToolStart
  | SubagentToolDone
  | SubagentClear
  | SubagentToolPermission
  | AgentTeamInfo
  | AgentContextUsage
  | LayoutLoaded
  | FurnitureAssetsLoaded
  | CharacterSpritesLoaded
  | PetSpritesLoaded
  | FloorTilesLoaded
  | WallTilesLoaded
  | CarpetTilesLoaded
  | SettingsLoaded
  | HooksStatus
  | HooksConsentRequest
  | ExternalAssetDirectoriesUpdated
  | AreaMappingsLoaded
  | WorkspaceFolders
  | AgentDiagnostics
  | Snapshot
  | ProjectOpened
  | ProjectOpenFailed
  | AgentsUpdated
  | ConnectionChecked
  | CommandRejected
  | AgentEventMessage;

export type ClientMessage =
  | WebviewReady
  | LaunchAgent
  | FocusAgent
  | CloseAgent
  | SaveAgentSeats
  | SaveLayout
  | SetSoundEnabled
  | SetLastSeenVersion
  | SetAlwaysShowLabels
  | SetGhostHeadlessAgents
  | SetHooksEnabled
  | HooksConsentResponse
  | SetHooksInfoShown
  | SetWatchAllSessions
  | ExportLayout
  | ImportLayout
  | OpenSessionsFolder
  | AddExternalAssetDirectory
  | RemoveExternalAssetDirectory
  | SaveAreaMappings
  | SetShowAreas
  | RequestDiagnostics
  | OpenProject
  | RelocateProject
  | SaveAgentConfig
  | CheckConnection
  | StartAgent
  | StopAgent
  | SendPrompt
  | CancelRun
  | RespondPermission;

export interface ProviderCapabilities {
  type: 'providerCapabilities';
  readingTools: string[];
  subagentToolNames: string[];
}

export interface AgentCreated {
  type: 'agentCreated';
  id: number;
  folderName?: string;
  isExternal?: boolean;
  palette?: number;
  hueShift?: number;
}

export interface AgentClosed {
  type: 'agentClosed';
  id: number;
}

export interface AgentSelected {
  type: 'agentSelected';
  id: number;
}

export interface ExistingAgents {
  type: 'existingAgents';
  agents: number[];
  agentMeta: Record<string, AgentSeatMeta>;
  folderNames: Record<string, string>;
  externalAgents: Record<string, boolean>;
}

export interface AgentSeatMeta {
  palette?: number;
  hueShift?: number;
  seatId?: string;
}

export interface LegacyAgentStatus {
  type: 'agentStatus';
  id: number;
  status: AgentActivityStatus;
  awaitingInput?: boolean;
}

export type AgentActivityStatus = 'active' | 'waiting';

export interface AgentToolStart {
  type: 'agentToolStart';
  id: number;
  toolId: string;
  status: string;
  toolName?: string;
  permissionActive?: boolean;
  runInBackground?: boolean;
  isTeammateSpawn?: boolean;
}

export interface AgentToolDone {
  type: 'agentToolDone';
  id: number;
  toolId: string;
}

export interface AgentToolsClear {
  type: 'agentToolsClear';
  id: number;
}

export interface AgentToolPermission {
  type: 'agentToolPermission';
  id: number;
}

export interface AgentToolPermissionClear {
  type: 'agentToolPermissionClear';
  id: number;
}

export interface SubagentToolStart {
  type: 'subagentToolStart';
  id: number;
  parentToolId: string;
  toolId: string;
  status: string;
}

export interface SubagentToolDone {
  type: 'subagentToolDone';
  id: number;
  parentToolId: string;
  toolId: string;
}

export interface SubagentClear {
  type: 'subagentClear';
  id: number;
  parentToolId: string;
}

export interface SubagentToolPermission {
  type: 'subagentToolPermission';
  id: number;
  parentToolId: string;
}

export interface AgentTeamInfo {
  type: 'agentTeamInfo';
  id: number;
  teamName?: string;
  agentName?: string;
  isTeamLead?: boolean;
  leadAgentId?: number;
  teamUsesTmux?: boolean;
}

export interface AgentContextUsage {
  type: 'agentContextUsage';
  id: number;
  contextTokens: number;
  maxContextTokens: number;
}

export interface LayoutLoaded {
  type: 'layoutLoaded';
  layout: Record<string, any> | null;
  wasReset?: boolean;
}

export interface FurnitureAssetsLoaded {
  type: 'furnitureAssetsLoaded';
  catalog: FurnitureAssetMessage[];
  sprites: Record<string, string[][]>;
}

export interface FurnitureAssetMessage {
  id: string;
  name: string;
  label: string;
  category: string;
  file: string;
  width: number;
  height: number;
  footprintW: number;
  footprintH: number;
  isDesk: boolean;
  canPlaceOnWalls: boolean;
  groupId?: string;
  canPlaceOnSurfaces?: boolean;
  backgroundTiles?: number;
  orientation?: string;
  state?: string;
  mirrorSide?: boolean;
  rotationScheme?: string;
  animationGroup?: string;
  frame?: number;
}

export interface CharacterSpritesLoaded {
  type: 'characterSpritesLoaded';
  characters: CharacterSpriteSet[];
}

export interface CharacterSpriteSet {
  down: string[][][];
  up: string[][][];
  right: string[][][];
}

export interface PetSpritesLoaded {
  type: 'petSpritesLoaded';
  pets: PetSpriteFrameSet[];
  petNames: string[];
}

export interface PetSpriteFrameSet {
  walkDown: string[][][];
  idleDown: string[][][];
  walkUp: string[][][];
  idleUp: string[][][];
  walkRight: string[][][];
}

export interface FloorTilesLoaded {
  type: 'floorTilesLoaded';
  sprites: string[][][];
}

export interface WallTilesLoaded {
  type: 'wallTilesLoaded';
  sets: string[][][][];
}

export interface CarpetTilesLoaded {
  type: 'carpetTilesLoaded';
  sets: string[][][][];
}

export interface SettingsLoaded {
  type: 'settingsLoaded';
  soundEnabled: boolean;
  lastSeenVersion: string;
  extensionVersion: string;
  watchAllSessions: boolean;
  alwaysShowLabels: boolean;
  ghostHeadlessAgents: boolean;
  hooksEnabled: boolean;
  hooksInfoShown: boolean;
  externalAssetDirectories: string[];
  showAreas: boolean;
}

export interface HooksStatus {
  type: 'hooksStatus';
  providerId: string;
  installed: boolean;
}

export interface HooksConsentRequest {
  type: 'hooksConsentRequest';
  providerId: string;
  headline: string;
  disclosure: string;
}

export interface ExternalAssetDirectoriesUpdated {
  type: 'externalAssetDirectoriesUpdated';
  dirs: string[];
}

export interface AreaMappingsLoaded {
  type: 'areaMappingsLoaded';
  mappings: Record<string, string[]>;
}

export interface WorkspaceFolders {
  type: 'workspaceFolders';
  folders: WorkspaceFolder[];
}

export interface WorkspaceFolder {
  name: string;
  path: string;
}

export interface AgentDiagnostics {
  type: 'agentDiagnostics';
  agents: Record<string, any>[];
}

export interface Snapshot {
  type: 'snapshot';
  currentProjectId: string | null;
  recentProjects: RecentProject[];
  project?: ProjectInfo;
  agents: AgentSnapshot[];
}

export interface RecentProject {
  projectId: string;
  name: string;
  path: string;
}

export interface ProjectInfo {
  projectId: string;
  name: string;
  path: string;
  files: FileEntry[];
  filesTruncated: boolean;
}

export interface FileEntry {
  relativePath: string;
  directory: boolean;
}

export interface AgentSnapshot {
  agent: AgentSummary;
  status: AgentStatus;
  sessionId: string | null;
  runId: string | null;
  lastSeq: number;
  toolCalls: ToolCallState[];
  pendingPermissions: PermissionRequestedData[];
  prompt?: string;
  lastRun?: RunEndedData;
  session?: SessionSnapshot;
  sessionEnd?: SessionEndedData;
  lastError?: AgentErrorData;
  authStatus?: AuthStatus;
  recentEvents: AgentEvent[];
}

export interface AgentSummary {
  agentId: string;
  projectId: string;
  name: string;
  harnessId: string;
  providerId: string;
  modelId: string | null;
  authMethodId: string;
  readiness: Readiness;
  palette: number;
  hueShift: number;
  reportsToolDetails: boolean;
  reportsSubagentSessions: boolean;
}

export type Readiness = 'configured' | 'auth_required' | 'unsupported' | 'executable_missing';

export type AgentStatus =
  'offline' | 'starting' | 'ready' | 'running' | 'awaiting_permission' | 'cancelling' | 'error';

export interface ToolCallState {
  toolCallId: string;
  title?: string;
  kind?: ToolKind;
  status?: ToolCallStatus;
  locations: ToolCallLocation[];
  content?: Record<string, any>[];
  rawInput?: any;
  rawOutput?: any;
  vendorMeta?: Record<string, any>;
}

export type ToolKind =
  | 'read'
  | 'edit'
  | 'delete'
  | 'move'
  | 'search'
  | 'execute'
  | 'think'
  | 'fetch'
  | 'switch_mode'
  | 'other';

export type ToolCallStatus = 'pending' | 'in_progress' | 'completed' | 'failed';

export interface ToolCallLocation {
  path: string;
  line?: number;
}

export interface PermissionRequestedData {
  permissionId: string;
  toolCall: ToolCallState;
  options: PermissionOption[];
}

export interface PermissionOption {
  optionId: string;
  name: string;
  kind: PermissionOptionKind;
}

export type PermissionOptionKind = 'allow_once' | 'allow_always' | 'reject_once' | 'reject_always';

export interface RunEndedData {
  runId: string;
  outcome: RunOutcome;
  stopReason?: string;
  errorCode?: ErrorCode;
  unfinishedToolCalls: UnfinishedToolCall[];
}

export type RunOutcome = 'completed' | 'cancelled' | 'refused' | 'limit_reached' | 'failed';

export type ErrorCode =
  | 'EXECUTABLE_MISSING'
  | 'ADAPTER_START_FAILED'
  | 'PROTOCOL_UNSUPPORTED'
  | 'ACP_INIT_FAILED'
  | 'AUTH_REQUIRED'
  | 'AUTH_FAILED'
  | 'SESSION_CREATE_FAILED'
  | 'PROMPT_FAILED'
  | 'PROCESS_EXITED'
  | 'PROTOCOL_ERROR'
  | 'MODEL_INCOMPATIBLE'
  | 'AWS_CREDENTIALS_UNAVAILABLE'
  | 'AWS_CREDENTIALS_INVALID'
  | 'BEDROCK_ACCESS_DENIED'
  | 'BEDROCK_MODEL_UNAVAILABLE'
  | 'NETWORK'
  | 'THROTTLED'
  | 'TIMEOUT'
  | 'CHECK_UNAVAILABLE';

export interface UnfinishedToolCall {
  toolCallId: string;
  derivedStatus: DerivedToolStatus;
}

export type DerivedToolStatus = 'cancelled' | 'incomplete';

export interface SessionSnapshot {
  agentInfo?: AgentInfo;
  modes?: Record<string, any>;
  configOptions?: Record<string, any>[];
  currentModeId?: string;
  sessionInfo?: SessionInfoData;
  availableCommands?: AvailableCommand[];
  plan?: PlanEntry[];
  usage?: UsageData;
}

export interface AgentInfo {
  name: string;
  version: string;
  title?: string;
}

export interface SessionInfoData {
  title?: string;
  updatedAt?: string;
}

export interface AvailableCommand {
  name: string;
  description: string;
}

export interface PlanEntry {
  content: string;
  priority: string;
  status: string;
}

export interface UsageData {
  used: number;
  size: number;
  cost?: Cost;
}

export interface Cost {
  amount: number;
  currency: string;
}

export interface SessionEndedData {
  reason: SessionEndReason;
  exitCode?: number;
  signal?: string;
}

export type SessionEndReason = 'stopped' | 'aborted' | 'exited';

export interface AgentErrorData {
  code: ErrorCode;
  stage: Stage;
  message: string;
}

export type Stage =
  | 'spawn'
  | 'initialize'
  | 'authenticate'
  | 'session_new'
  | 'prompt'
  | 'running'
  | 'config'
  | 'check';

export interface AuthStatus {
  kind: string;
  label: string;
}

export interface AgentStatusChangedEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'agentStatusChanged';
  data: AgentStatusChangedData;
}

export interface AgentStatusChangedData {
  status: AgentStatus;
  previous: AgentStatus;
}

export interface AgentStartingEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'agentStarting';
  data: AgentStartingData;
}

export interface AgentStartingData {
  stage: Stage;
}

export interface SessionStartedEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'sessionStarted';
  data: SessionStartedData;
}

export interface SessionStartedData {
  sessionId: string;
  agentInfo?: AgentInfo;
  modes?: Record<string, any>;
  configOptions?: Record<string, any>[];
}

export interface SessionEndedEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'sessionEnded';
  data: SessionEndedData;
}

export interface AgentErrorEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'agentError';
  data: AgentErrorData;
}

export interface AuthStatusEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'authStatus';
  data: AuthStatus;
}

export interface RunStartedEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'runStarted';
  data: RunStartedData;
}

export interface RunStartedData {
  runId: string;
  prompt: string;
}

export interface RunEndedEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'runEnded';
  data: RunEndedData;
}

export interface UserMessageChunkEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'userMessageChunk';
  data: MessageChunkData;
}

export interface MessageChunkData {
  messageId?: string;
  contentType: string;
  text?: string;
}

export interface AgentMessageChunkEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'agentMessageChunk';
  data: MessageChunkData;
}

export interface AgentThoughtChunkEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'agentThoughtChunk';
  data: MessageChunkData;
}

export interface ToolCallEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'toolCall';
  data: ToolCallData;
}

export interface ToolCallData {
  toolCall: ToolCallState;
}

export interface ToolCallUpdateEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'toolCallUpdate';
  data: ToolCallData;
}

export interface PlanEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'plan';
  data: PlanData;
}

export interface PlanData {
  entries: PlanEntry[];
}

export interface AvailableCommandsEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'availableCommands';
  data: AvailableCommandsData;
}

export interface AvailableCommandsData {
  commands: AvailableCommand[];
}

export interface CurrentModeEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'currentMode';
  data: CurrentModeData;
}

export interface CurrentModeData {
  currentModeId: string;
}

export interface ConfigOptionsEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'configOptions';
  data: ConfigOptionsData;
}

export interface ConfigOptionsData {
  configOptions: Record<string, any>[];
}

export interface SessionInfoEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'sessionInfo';
  data: SessionInfoData;
}

export interface UsageEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'usage';
  data: UsageData;
}

export interface PermissionRequestedEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'permissionRequested';
  data: PermissionRequestedData;
}

export interface PermissionResolvedEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'permissionResolved';
  data: PermissionResolvedData;
}

export interface PermissionResolvedData {
  permissionId: string;
  outcome: PermissionOutcome;
  optionId?: string;
}

export type PermissionOutcome = 'selected' | 'cancelled';

export interface UnknownUpdateEvent {
  projectId: string;
  agentId: string;
  sessionId: string | null;
  runId: string | null;
  seq: number;
  receivedAt: number;
  kind: 'unknownUpdate';
  data: UnknownUpdateData;
}

export interface UnknownUpdateData {
  type: string;
}

export interface ProjectOpened {
  type: 'projectOpened';
  project: ProjectInfo;
}

export interface ProjectOpenFailed {
  type: 'projectOpenFailed';
  path: string;
  code: ProjectErrorCode;
  message: string;
}

export type ProjectErrorCode =
  'PATH_NOT_ABSOLUTE' | 'PATH_NOT_FOUND' | 'PATH_NOT_DIRECTORY' | 'PATH_NOT_READABLE';

export interface AgentsUpdated {
  type: 'agentsUpdated';
  agents: AgentSummary[];
}

export interface ConnectionChecked {
  type: 'connectionChecked';
  agentId: string;
  readiness: Readiness;
  authMethods: AuthMethodInfo[];
  authStatus?: AuthStatus;
  error?: AgentErrorData;
}

export interface AuthMethodInfo {
  id: string;
  name: string;
  description?: string;
}

export interface CommandRejected {
  type: 'commandRejected';
  command: string;
  reason: RejectReason;
  message: string;
  agentId?: string;
  projectId?: string;
  permissionId?: string;
}

export type RejectReason =
  | 'UNKNOWN_PROJECT'
  | 'UNKNOWN_AGENT'
  | 'UNKNOWN_PERMISSION'
  | 'INVALID_STATE'
  | 'INVALID_MESSAGE'
  | 'NOT_IMPLEMENTED';

export interface AgentEventMessage {
  type: 'agentEvent';
  event: AgentEvent;
}

/** One ADR 0005 AgentEvent, discriminated by `kind`. */
export type AgentEvent =
  | AgentStatusChangedEvent
  | AgentStartingEvent
  | SessionStartedEvent
  | SessionEndedEvent
  | AgentErrorEvent
  | AuthStatusEvent
  | RunStartedEvent
  | RunEndedEvent
  | UserMessageChunkEvent
  | AgentMessageChunkEvent
  | AgentThoughtChunkEvent
  | ToolCallEvent
  | ToolCallUpdateEvent
  | PlanEvent
  | AvailableCommandsEvent
  | CurrentModeEvent
  | ConfigOptionsEvent
  | SessionInfoEvent
  | UsageEvent
  | PermissionRequestedEvent
  | PermissionResolvedEvent
  | UnknownUpdateEvent;

export interface WebviewReady {
  type: 'webviewReady';
}

export interface LaunchAgent {
  type: 'launchAgent';
  folderPath?: string;
  bypassPermissions?: boolean;
}

export interface FocusAgent {
  type: 'focusAgent';
  id: number;
}

export interface CloseAgent {
  type: 'closeAgent';
  id: number;
}

export interface SaveAgentSeats {
  type: 'saveAgentSeats';
  seats: Record<string, SeatAssignment>;
}

export interface SeatAssignment {
  palette: number;
  hueShift: number;
  seatId: string | null;
}

export interface SaveLayout {
  type: 'saveLayout';
  layout: Record<string, any>;
}

export interface SetSoundEnabled {
  type: 'setSoundEnabled';
  enabled: boolean;
}

export interface SetLastSeenVersion {
  type: 'setLastSeenVersion';
  version: string;
}

export interface SetAlwaysShowLabels {
  type: 'setAlwaysShowLabels';
  enabled: boolean;
}

export interface SetGhostHeadlessAgents {
  type: 'setGhostHeadlessAgents';
  enabled: boolean;
}

export interface SetHooksEnabled {
  type: 'setHooksEnabled';
  providerId: string;
  enabled: boolean;
}

export interface HooksConsentResponse {
  type: 'hooksConsentResponse';
  providerId: string;
  choice: HooksConsentChoice;
}

export type HooksConsentChoice = 'install' | 'notNow' | 'never';

export interface SetHooksInfoShown {
  type: 'setHooksInfoShown';
}

export interface SetWatchAllSessions {
  type: 'setWatchAllSessions';
  enabled: boolean;
}

export interface ExportLayout {
  type: 'exportLayout';
}

export interface ImportLayout {
  type: 'importLayout';
}

export interface OpenSessionsFolder {
  type: 'openSessionsFolder';
}

export interface AddExternalAssetDirectory {
  type: 'addExternalAssetDirectory';
  path?: string;
}

export interface RemoveExternalAssetDirectory {
  type: 'removeExternalAssetDirectory';
  path: string;
}

export interface SaveAreaMappings {
  type: 'saveAreaMappings';
  mappings: Record<string, string[]>;
}

export interface SetShowAreas {
  type: 'setShowAreas';
  enabled: boolean;
}

export interface RequestDiagnostics {
  type: 'requestDiagnostics';
}

export interface OpenProject {
  type: 'openProject';
  path: string;
}

export interface RelocateProject {
  type: 'relocateProject';
  projectId: string;
  path: string;
}

export interface SaveAgentConfig {
  type: 'saveAgentConfig';
  config: AgentConfigInput;
}

export interface AgentConfigInput {
  agentId?: string;
  projectId: string;
  name: string;
  harnessId: string;
  providerId: string;
  modelId: string | null;
  authMethodId: string;
}

export interface CheckConnection {
  type: 'checkConnection';
  agentId: string;
}

export interface StartAgent {
  type: 'startAgent';
  agentId: string;
}

export interface StopAgent {
  type: 'stopAgent';
  agentId: string;
}

export interface SendPrompt {
  type: 'sendPrompt';
  agentId: string;
  text: string;
}

export interface CancelRun {
  type: 'cancelRun';
  agentId: string;
  runId: string;
}

export interface RespondPermission {
  type: 'respondPermission';
  permissionId: string;
  optionId: string;
}
