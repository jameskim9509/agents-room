# ADR 0005: AgentEvent 모델

## 상태

Accepted (2026-10-23 팀 결정).
Jira가 정한 것은 "공통 AgentEvent"(SCRUM-11, 66), 정규화(SCRUM-23), 지어내지 않는다는 원칙(SCRUM-19, 21, 27)이다. 모델의 형태는 정하지 않았으므로, 아래의 이름, 단위, 전이는 이 ADR이 정한다.

## 날짜

2026-10-21

## 맥락

- 업스트림 `AgentEvent`(`core/src/provider.ts`)의 kind는 10개다: `toolStart`, `toolEnd`, `turnEnd`, `subagentStart`, `subagentEnd`, `subagentTurnEnd`, `progress`, `permissionRequest`, `sessionStart`, `sessionEnd`.
- 이 모델은 toolId를 `hook-${Date.now()}`로 지어내고 `progress`를 버린다. `AgentActivityStatus`(`core/asyncapi.yaml`)는 `active`와 `waiting`뿐이라 실패와 취소를 표현하지 못한다. `permissionRequest`에는 payload가 없고, 권한 요청(Permission request)에 응답하는 ClientMessage도 없다. `runInBackground` 같은 Claude 전용 필드가 있다.
- ACP v1에는 `sessionUpdate` 판별자 14종, `StopReason` 5종, `ToolKind` 10종, `ToolCallStatus` 4종(`cancelled` 없음), 권한 옵션 kind 4종이 있다 ([schema.json](https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v1/schema.json), [Tool Calls](https://agentclientprotocol.com/protocol/tool-calls), [Prompt Turn](https://agentclientprotocol.com/protocol/prompt-turn)).

## 결정

1. 공통 필드(envelope)는 `{ projectId, agentId, sessionId | null, runId | null, seq, receivedAt, kind, data }`이고, kind는 아래 kind 표와 같다. 내용 이벤트는 ACP v1 판별자와 1:1로 대응하고, agents-room은 상태, 생명주기, 실행(Run), 권한, 진단 이벤트만 더한다. 클라이언트가 광고해야 오는 `notice`, `compaction_update`, `compaction_summary_chunk`는 MVP에서 받지 않는다.
2. 렌더러가 런타임에 보내는 명령은 `openProject`, `relocateProject`, `saveAgentConfig`, `checkConnection`, `startAgent`, `stopAgent`, `sendPrompt`, `cancelRun`, `respondPermission`이다. `cancelRun`은 실행만 멈추고(SCRUM-24의 "중지"), `stopAgent`는 어댑터 프로세스를 끝낸다. 렌더러가 연결되면 런타임은 상태 스냅샷을 1회 보내고 그다음부터 이벤트를 보낸다 (업스트림 `agentActivityResend.ts`의 개념).
3. 실행은 `session/prompt` 1회다. `runId`는 런타임이 발급한다. 실행 밖에서 온 update(세션 단위 update, 응답 뒤에 늦게 온 update)는 `runId = null`이다. 프롬프트 단위로 잡아야 늦게 온 이벤트(SCRUM-24)와 비정상 종료(SCRUM-47)를 해당 실행에 귀속할 수 있다. Jira의 "실행"이 가리키는 단위는 [용어집](../architecture.md#용어집), 식별자 정의는 [식별자](../architecture.md#식별자)에 있다.
4. 에이전트 상태는 런타임이 계산하고 `agentStatusChanged`로 보낸다. 렌더러는 상태를 다시 계산하지 않는다. 전이는 아래 전이표와 같다. 값, 표시, Jira 상태어(SCRUM-6)와의 대응은 [ux.md#상태-어휘](../ux.md#상태-어휘)에 있다. `stopAgent`는 프로세스가 있는 모든 상태에서 받는다. 실행 중이면 먼저 [ADR 0003](0003-acp-integration.md) 결정 7대로 취소하고, 정해진 시간만 기다린 뒤 프로세스 트리를 끝낸다. 취소 응답이 시간 안에 오지 않아 강제로 끝낼 때도 같은 경로를 쓴다 (SCRUM-24). `disconnected`의 전이는 post-MVP(SCRUM-48)에서 정한다.
5. 실행 결과는 `stopReason`으로 정한다: `end_turn` → `completed`, `cancelled` → `cancelled`, `refusal` → `refused`, `max_tokens`·`max_turn_requests` → `limit_reached`, `session/prompt` 오류 응답이나 실행 중 프로세스 종료 → `failed`. 사용자가 중지를 요청했어도 실제 `stopReason`이 `end_turn`이면 `completed`다 (SCRUM-24 "실제 결과에 맞게"). 실행 종료를 타이머로 추정하지 않는다. 업스트림의 휴리스틱 타이머(권한 7초, text-idle 5초)는 ACP 에이전트에 쓰지 않는다.
6. Tool 호출(Tool call): `toolCall`과 `toolCallUpdate`는 `toolCallId` 기준으로 부분 병합한다. 같은 `toolCallId`의 update는 새 Tool 호출을 만들지 않는다 (SCRUM-27). `content`와 `locations`는 이전 값을 대체한다. `kind`나 `status`가 없으면 `null`로 둔다 (ACP 기본값을 사실로 저장하지 않는다). 실행이 끝났을 때 끝나지 않은 Tool 호출은 결과가 `cancelled`면 `derivedStatus: cancelled`, 그 밖이면 `derivedStatus: incomplete`로 표시하고, 둘 다 클라이언트가 판단한 값이라고 밝힌다. 행동 표시는 [ux.md#행동-매핑](../ux.md#행동-매핑)에 있다.
7. 권한 요청은 상태를 가진 엔티티로 다룬다: `{permissionId, agentId, sessionId, runId, toolCall, options[{optionId, name, kind}], state: pending | answered | cancelled}`. 명령 `respondPermission {permissionId, optionId}`를 새로 만든다. 렌더러는 사용자가 고른 선택지만 보낸다. `cancelled` 응답은 런타임이 실행을 취소하거나 에이전트를 종료할 때만 보낸다 ([ADR 0003](0003-acp-integration.md) 결정 7). MVP 기본 정책: 어댑터가 보낸 권한 요청은 모두 사용자에게 보여주고, 자동으로 허용하지 않으며, 대화상자를 닫으면 요청은 대기 상태로 남는다 (SCRUM-15, 33). 에이전트별 세부 정책은 post-MVP다 (SCRUM-62).
8. 오류 코드 enum은 아래 오류 코드 표다 (이 ADR이 홈이다). 감지원은 [ADR 0003](0003-acp-integration.md)과 [ADR 0004](0004-model-authentication.md), 문구는 [ux.md#문구-목록](../ux.md#문구-목록)에 있다.
9. 지어내지 않기: Plan은 받은 목록 전체로 바꾼다. 우선순위는 어댑터가 만든 값일 수 있다 (codex-acp는 `medium`으로 고정한다). 컨텍스트 게이지는 `usage.used`/`usage.size`로만 그리고, 비용은 `cost`가 있을 때만 보여준다. 제목은 `sessionInfo.title`에서 온다. 생각 청크는 live로만 보여주고 저장하지 않는다 (SCRUM-16 범위 제외, SCRUM-52). vendor `_meta`(예: `claudeCode.parentToolUseId`, `terminal_output`)는 선택적인 보강 정보로만 쓰고 "어댑터 제공 정보"로 표시한다.
10. 서브에이전트(Sub-agent) 캐릭터는 post-MVP다 (SCRUM-56, 2026-10-22 팀 결정). 그때도 ACP가 검증된 부모·자식 세션 ID를 보고할 때만 만든다 (SCRUM-21). MVP에서는 Agent나 Task Tool 호출을 일반 Tool 호출로 보여주고, claude-agent-acp의 `_meta.claudeCode.parentToolUseId`가 있으면 Detail Panel 안에서 묶어 보여줄 수 있다. 자식 세션을 알리는 방법은 [Subagents RFD](https://agentclientprotocol.com/rfds/subagents)(Draft. 스키마는 1.24.0부터 unstable)에 있다: `clientCapabilities.subagents`를 광고하면 부모 세션의 `subagent_update`가 자식 `sessionId`를 알린다 (SCRUM-56, post-MVP). 이를 쓰려면 SDK를 고치거나 얇은 클라이언트가 필요하다 (ADR 0003).
11. 계약은 `core/asyncapi.yaml` 하나다. AsyncAPI 3.0.0으로 고정한다 (Modelina 5.10.1은 3.x 중 3.0.0만 지원한다). Modelina로 TS(렌더러)와 Java(런타임) 바인딩을 생성하고, 업스트림의 CI drift check를 유지한다. 손으로 맞춘 DTO는 drift가 생긴다 (업스트림 `AgentCreated`, [upstream.md#알려진-불일치](../upstream.md#알려진-불일치)). broadcast는 타입 검사를 한다. 업스트림의 `subagent*`, `team*`, `isTeammateSpawn`, `runInBackground`, `isExternal`, `STATUS_TO_TOOL` 영어 접두어 해석, hooks와 JSONL 이벤트는 버린다.

| 분류     | kind                                                                                                                                                                                           | 출처                                                                                     |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| 상태     | `agentStatusChanged {status, previous}`                                                                                                                                                        | 런타임이 계산한다 (단일 출처)                                                            |
| 생명주기 | `agentStarting {stage}`, `sessionStarted {sessionId, agentInfo?, modes?, configOptions?}`, `sessionEnded {reason: stopped \| exited, exitCode?, signal?}`, `agentError {code, stage, message}` | 프로세스와 ACP 요청/응답                                                                 |
| 실행     | `runStarted {runId, prompt}`, `runEnded {runId, outcome, stopReason?, errorCode?}`                                                                                                             | `session/prompt`와 그 응답                                                               |
| 내용     | `userMessageChunk`, `agentMessageChunk`, `agentThoughtChunk`, `toolCall`, `toolCallUpdate`, `plan`, `availableCommands`, `currentMode`, `configOptions`, `sessionInfo`, `usage`                | `session/update`의 `sessionUpdate` 판별자 (예: `config_option_update` → `configOptions`) |
| 권한     | `permissionRequested {permissionId, toolCall, options[]}`, `permissionResolved {permissionId, outcome: selected \| cancelled, optionId?}`                                                      | `session/request_permission`                                                             |
| 진단     | `unknownUpdate {type}`. 내용은 버리고 유형 이름과 시각만 남긴다 (SCRUM-50)                                                                                                                     | 알 수 없는 update                                                                        |

| 현재 상태                        | 이벤트                                 | 다음 상태                                                                                                                                |
| -------------------------------- | -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `offline`, `error`               | `startAgent`                           | `starting`. 새 프로세스, 새 세션이다                                                                                                     |
| `starting`                       | `session/new` 성공 / 단계 실패         | `ready` / `error`                                                                                                                        |
| `ready`                          | `sendPrompt`                           | `running` (`runStarted`)                                                                                                                 |
| `running`                        | 권한 요청                              | `awaiting_permission`                                                                                                                    |
| `awaiting_permission`            | 대기 중인 요청에 모두 응답함           | `running`                                                                                                                                |
| `running`, `awaiting_permission` | `cancelRun`                            | `cancelling`                                                                                                                             |
| `running`, `cancelling`          | `session/prompt` 응답 (오류 응답 포함) | `ready` (`runEnded`)                                                                                                                     |
| 프로세스가 있는 모든 상태        | `stopAgent` (강제 종료 포함)           | `offline`, `sessionEnded {reason: stopped}`. 실행 중이었으면 `runEnded`(결과는 `stopReason`, 응답 전에 끝냈으면 errorCode 없는 `failed`) |
| 프로세스가 있는 모든 상태        | 요청하지 않은 프로세스 종료            | `error`. 실행 중이었으면 `runEnded {outcome: failed, errorCode: PROCESS_EXITED}`                                                         |

| code                              | stage                                   | 뜻                                                         | MVP      |
| --------------------------------- | --------------------------------------- | ---------------------------------------------------------- | -------- |
| `EXECUTABLE_MISSING`              | `spawn`                                 | 어댑터나 실행기 실행 파일이 없다                           | 예       |
| `ADAPTER_START_FAILED`            | `spawn`                                 | `initialize` 응답 전에 종료했거나, stdout이 ACP가 아니다   | 예       |
| `PROTOCOL_UNSUPPORTED`            | `initialize`                            | 어댑터가 `protocolVersion` 1이 아닌 값을 응답했다          | 예       |
| `ACP_INIT_FAILED`                 | `initialize`                            | `initialize` 오류 또는 시간 초과                           | 예       |
| `AUTH_REQUIRED`                   | `authenticate`, `session_new`, `prompt` | 인증이 필요하다                                            | 예       |
| `AUTH_FAILED`                     | `authenticate`                          | `authenticate` 요청이 실패했다 (로그인 미완료, 취소, 만료) | 예       |
| `SESSION_CREATE_FAILED`           | `session_new`                           | `session/new` 오류                                         | 예       |
| `PROMPT_FAILED`                   | `prompt`                                | `session/prompt` 오류 응답                                 | 예       |
| `PROCESS_EXITED`                  | `running`                               | 요청하지 않은 프로세스 종료                                | 예       |
| `PROTOCOL_ERROR`                  | `running`                               | 형식이 잘못된 메시지. 그 연결 안에서만 격리한다 (SCRUM-50) | 예       |
| `MODEL_INCOMPATIBLE`              | `config`                                | 실행기와 모델의 정적 호환성 규칙 위반                      | 예       |
| `AWS_CREDENTIALS_UNAVAILABLE`     | `check`                                 | AWS 자격증명을 불러오지 못했다                             | post-MVP |
| `AWS_CREDENTIALS_INVALID`         | `check`                                 | 만료되었거나 잘못된 자격증명                               | post-MVP |
| `BEDROCK_ACCESS_DENIED`           | `check`                                 | IAM 권한 부족                                              | post-MVP |
| `BEDROCK_MODEL_UNAVAILABLE`       | `check`                                 | 리전, 계약, 승인 상태 때문에 모델을 쓸 수 없다             | post-MVP |
| `NETWORK`, `THROTTLED`, `TIMEOUT` | 모든 단계                               | 네트워크 오류, 속도 제한, 시간 초과                        | 예       |
| `CHECK_UNAVAILABLE`               | `check`                                 | 검사를 할 수 없다. 정상으로 간주하지 않는다 (SCRUM-49)     | 예       |

## 검토한 대안

### 업스트림 AgentEvent 유지

- 장점: 렌더러의 기존 처리 코드(`useExtensionMessages.ts`)를 그대로 쓸 수 있다.
- 단점: 권한 id와 옵션이 없고, 실패와 취소를 표현할 수 없으며, 지어낸 id와 Claude 전용 필드가 남는다.
- 판단: 채택하지 않는다.

### 원시 ACP JSON 전달

- 장점: 변환 계층이 없다.
- 단점: 렌더러가 프로토콜 버전과 vendor `_meta`에 묶이고, 한 곳에서 마스킹할 수 없으며, 생명주기 정보가 없다.
- 판단: 채택하지 않는다.

### 프로세스나 세션 수명 단위의 Run

- 장점: 식별자가 하나 줄어든다.
- 단점: 요청마다 결과(완료, 실패)를 줄 수 없고, 취소 뒤에 늦게 온 이벤트를 구분할 수 없으며, SCRUM-53처럼 완료·실패 상태로 실행을 찾을 수 없다.
- 판단: 채택하지 않는다.

## 결과

- 좋은 점: 매핑이 1:1이라 어댑터가 보고한 것 이상을 만들지 않는다. 실행기를 추가해도 렌더러를 바꾸지 않는다. 턴 종료 감지가 한 곳에 모여 있어, v2(idle `state_update`로 턴 종료를 알린다)로 옮길 때 실행 경계 규칙은 그 한 곳만 바뀐다.
- 비용과 위험: 렌더러의 `useExtensionMessages.ts`를 다시 써야 한다. 범용 작업 표시(SCRUM-27)와 새 아이콘 스프라이트가 필요하다.
- 후속 작업: `core/asyncapi.yaml`에 이벤트와 명령을 정의하고, Modelina Java 생성을 추가한다.

### 열린 질문

- notice와 compaction을 언제 광고할지.
- codex-acp가 표준 `subagent_update`를 보내는지. 문서에는 초기 AIR 초안의 이벤트 이름이 있다 (미검증, 실제 테스트 필요).
- 이력 저장(SCRUM-52)의 마스킹과 보존 정책.
- 메시지 청크를 묶는 단위 (SCRUM-28: 묶어도 주요 상태 전환은 빠뜨리지 않는다).

## 관련 이슈

SCRUM-6, 11, 15, 16, 19, 21, 23, 24, 27, 28, 33, 47, 48, 49, 50, 52, 53, 56, 62, 66 / [ADR 0003](0003-acp-integration.md), [ADR 0004](0004-model-authentication.md) / [architecture.md#식별자](../architecture.md#식별자), [ux.md#상태-어휘](../ux.md#상태-어휘), [ux.md#행동-매핑](../ux.md#행동-매핑)
