# 아키텍처

이 문서는 agents-room의 목표 구조를 설명한다. 코드는 아직 pixel-agents 기준선([`d1e007a`](upstream.md#포크-기준)) 그대로이고, 아래 구성요소는 모두 계획이다.
화면과 문구는 [ux.md](ux.md), 결정과 대안은 [ADR](adr/README.md), 업스트림 코드 상세는 [upstream.md](upstream.md)에 있다.

## 용어집

| 본문 표기       | 영어 / 식별자                      | 정의                                                                                                                                                                                                                                                                                          | Jira                     |
| --------------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------ |
| 에이전트        | Agent, `agentId`                   | 프로젝트에 등록된 실행 단위다. 실행기·모델 제공자·모델·인증 방식의 조합(AgentConfig)을 가진다. 시작하면 ACP 어댑터 프로세스 1개, ACP 세션 1개와 연결되고, 캐릭터 1개로 표시된다. MVP에서는 에이전트마다 프로세스·세션·실행이 동시에 최대 1개다.                                               | SCRUM-10, 23, 28         |
| 서브에이전트    | Sub-agent                          | 에이전트가 실행 중에 스스로 만든 하위 에이전트다. ACP가 자식 세션 ID와 부모 관계를 실제로 보고할 때만 별도 캐릭터로 만들며, 캐릭터 표시는 post-MVP다(SCRUM-56). 사용자가 연결한 워크플로우의 다음 에이전트와는 다른 개념이다.                                                                 | SCRUM-6, 21, 29, 56      |
| 실행기          | Harness, `harnessId`               | 코딩 에이전트 제품이다. MVP는 Codex와 Claude Code이고(SCRUM-23, 66), Gemini CLI·OpenCode·Custom 같은 나머지는 그 뒤에 붙인다([ADR 0003](adr/0003-acp-integration.md)). Jira의 "Harness", "실행기", "Agent 종류"는 같은 뜻이다.                                                                | SCRUM-9, 10, 26          |
| ACP 어댑터      | ACP adapter                        | 실행기를 ACP의 Agent 역할로 노출하는 실행 파일이다. 로컬 런타임이 자식 프로세스로 띄우고 stdio로 JSON-RPC를 주고받는다. 예: `codex-acp`, `claude-agent-acp`. ACP가 없는 실행기를 감싸는 것은 "브리지"라고 부른다(post-MVP).                                                                   | SCRUM-23, 57, 66         |
| Java ACP Client | Java ACP Client                    | 로컬 런타임 안에서 ACP의 Client 역할을 하는 구성요소다. 어댑터 프로세스마다 연결이 1개다.                                                                                                                                                                                                     | SCRUM-23, 66             |
| 모델 제공자     | Model Provider, `providerId`       | 모델을 서비스하는 곳이다: Amazon Bedrock(MVP는 mock), OpenAI(ChatGPT 로그인, MVP 확인용). 배포 서비스의 모델은 Bedrock으로 제공한다. 실행기와는 별개다.                                                                                                                                       | SCRUM-10, 25, 65         |
| 모델            | Model, `modelId`                   | 제공자의 모델 ID 또는 inference profile ID다. 지정하지 않으면 실행기 기본값을 쓴다. 실제로 쓰인 모델은 어댑터가 보고한 값만 표시한다.                                                                                                                                                         | SCRUM-25, 26             |
| 인증 방식       | Auth method, `authMethodId`        | 실행기가 모델 제공자에 인증하는 경로다: ChatGPT 로그인(MVP 확인용), AWS profile. Claude 계정 로그인은 쓰지 않는다. 요금제(plan)와는 별개이며, 요금제는 앱이 설정하지 않는다([ADR 0004](adr/0004-model-authentication.md)).                                                                    | SCRUM-65, 25             |
| 자격증명 참조   | Credential reference               | 앱이 저장하는 값 중 비밀이 아닌 참조값이다(AWS profile 이름, region). 비밀 원문은 저장하지 않는다.                                                                                                                                                                                            | SCRUM-34, 58, 65         |
| 프로젝트        | Project, `projectId`               | 사용자가 등록한 로컬 폴더다. 검증된 절대 경로가 ACP `session/new`의 `cwd`가 된다.                                                                                                                                                                                                             | SCRUM-13, 31             |
| 워크스페이스    | Workspace                          | 프로젝트 하나와 그 프로젝트의 에이전트 설정, 레이아웃, 실행 상태를 묶은 단위다. MVP에서는 프로젝트와 1:1이다.                                                                                                                                                                                 | SCRUM-13                 |
| 세션            | Session (ACP session), `sessionId` | 어댑터가 `session/new`로 발급하는 대화 단위다. 어댑터 프로세스 1개 안에서 열리고, 그 안에서 실행이 차례로 일어난다.                                                                                                                                                                           | SCRUM-9, 23              |
| 실행            | Run, `runId`                       | 사용자 요청 1건, 곧 `session/prompt` 1회의 처리다([ADR 0005](adr/0005-agent-event-model.md)). Jira는 "실행"을 프로세스·세션·프롬프트 단위로 섞어 쓴다(SCRUM-23, 28, 47, 60). 이 문서 세트는 프롬프트 단위로 정하고, SCRUM-28의 "Agent 실행"은 에이전트로 읽는다.                              | SCRUM-23, 24, 28, 47, 60 |
| Tool 호출       | Tool call, `toolCallId`            | 에이전트가 보고한 개별 동작이다(파일 읽기, 편집, 명령 실행 등). ACP `tool_call`과 `tool_call_update`로 오며 `kind`와 `status`를 가진다.                                                                                                                                                       | SCRUM-19, 27             |
| AgentEvent      | AgentEvent                         | 로컬 런타임이 ACP 메시지와 프로세스 생명주기를 정규화한 공통 이벤트다. 렌더러는 AgentEvent와 상태 스냅샷만 받는다. 정의는 [ADR 0005](adr/0005-agent-event-model.md)에 있다.                                                                                                                   | SCRUM-11, 23, 66         |
| 에이전트 상태   | Agent status                       | 캐릭터와 Detail Panel이 보여주는 상태값이다. 값과 표시는 [ux.md](ux.md#상태-어휘), 전이 규칙은 [ADR 0005](adr/0005-agent-event-model.md)에 있다.                                                                                                                                              | SCRUM-6, 11              |
| 캐릭터          | Character                          | 픽셀 월드에서 에이전트 1개를 나타내는 스프라이트다. 상태와 행동은 AgentEvent로만 바뀐다.                                                                                                                                                                                                      | SCRUM-11, 27, 28         |
| Detail Panel    | Detail Panel                       | 캐릭터를 선택하면 화면 오른쪽에 열리는 패널이다. 선택한 에이전트의 요청, 세션 상태, 현재 Tool, 이벤트 순서, 결과를 보여준다.                                                                                                                                                                  | SCRUM-6, 19              |
| 권한 요청       | Permission request, `permissionId` | 에이전트가 위험할 수 있는 동작 전에 보내는 ACP `session/request_permission` 요청이다. 사용자가 고른 선택지로 원래 요청에 응답해야 한다. 워크플로우의 승인 단계와는 다르다.                                                                                                                    | SCRUM-33, 45             |
| 워크플로우      | Workflow (post-MVP)                | 사용자가 GUI에서 여러 에이전트를 연결해 만든 실행 흐름이다. 자체 실행 ID를 가지며, 서브에이전트 관계와 구분해서 표시한다.                                                                                                                                                                     | SCRUM-12, 20, 29, 30     |
| Capability      | Capability                         | ACP `initialize`에서 클라이언트와 어댑터가 서로 알린 기능이다. 알리지 않은 기능은 지원하지 않는 것으로 본다.                                                                                                                                                                                  | SCRUM-9, 10, 50          |
| 데스크톱 셸     | Desktop shell                      | 창, OS 대화상자, 앱 생명주기, 설치와 업데이트를 맡는 껍데기다([ADR 0001](adr/0001-desktop-framework.md)). 에이전트 프로세스를 직접 띄우지 않는다.                                                                                                                                             | SCRUM-8, 17              |
| 로컬 런타임     | Local runtime                      | 셸이 띄우는 Java/Spring 프로세스다([ADR 0002](adr/0002-java-spring-runtime.md), 띄우는 방식은 [ADR 0001](adr/0001-desktop-framework.md)). 프로젝트, 에이전트 설정, 프로세스, ACP 연결, 이벤트 정규화, 권한 중개, 저장을 맡는다. Jira의 "Spring 런타임", "로컬 런타임", "데스크톱 런타임"이다. | SCRUM-8, 22, 23          |
| 렌더러          | Renderer                           | 픽셀 월드와 패널을 그리는 웹 UI다(현재 `webview-ui/`). 표시와 입력만 맡으며 프로세스, 파일, 비밀에 직접 접근하지 않는다.                                                                                                                                                                      | SCRUM-8, 22              |

표기 규칙:

- 처음 나올 때는 "실행(Run)", "실행기(Harness)"처럼 괄호로 영어를 병기하고, 그다음부터는 본문 표기만 쓴다.
- "자식 에이전트", "하위 Agent"는 Jira 문구를 인용할 때만 쓰고, 본문에서는 "서브에이전트"로 쓴다.
- "브리지"는 ACP가 없는 실행기를 감싸는 것(SCRUM-57)에만 쓰고, ACP 어댑터를 브리지라고 부르지 않는다. "서버"는 로컬 런타임의 동의어로 쓰지 않는다. agents-room은 사용자가 URL을 입력하는 웹 서버가 아니다(SCRUM-36).
- 업스트림 pixel-agents의 Sub-agent·Teammate·Headless·Hooks 모드 정의는 쓰지 않는다. 뜻이 다르다([upstream.md](upstream.md#레거시-참조)).

## 구성도

```text
Desktop shell (ADR 0001): 창, 메뉴, OS 대화상자(폴더 선택), 설치와 업데이트
 ├─ Renderer (webview-ui: React + Canvas)
 │      ▲ AgentEvent, 상태 스냅샷      ▼ 명령 (목록은 ADR 0005)
 │      계약: core/asyncapi.yaml (ADR 0005), 전송 방식: ADR 0001
 └─▶ Local runtime (Java/Spring, ADR 0002): 띄우는 방식은 ADR 0001
        ├─ Renderer gateway: 렌더러와 통하는 유일한 통로
        ├─ Project/Workspace service
        ├─ Agent config registry
        ├─ Process supervisor ──spawn──▶ ACP adapter 자식 프로세스 (에이전트마다 1개)
        ├─ ACP client + session manager ◀── stdio JSON-RPC (ADR 0003) ──▶ codex-acp, claude-agent-acp
        ├─ Event normalizer (+ 상태 저장소)
        ├─ Permission broker
        └─ Persistence (앱 데이터 디렉터리)
ACP adapter ── 어댑터 자체 인증과 모델 호출 (ADR 0004) ──▶ Amazon Bedrock (MVP는 mock 서버), OpenAI(ChatGPT, MVP 확인용)
```

- 렌더러는 에이전트 프로세스를 띄우지 않는다. 프로세스의 시작과 종료는 로컬 런타임이 맡는다 (SCRUM-22).
- 에이전트마다 자식 프로세스와 ACP 연결이 따로 있다. 한 에이전트의 오류나 잘못된 메시지는 다른 에이전트로 번지지 않는다 (SCRUM-24, 50, 66).
- ACP 메시지는 런타임 밖으로 나가지 않는다. 렌더러는 정규화된 AgentEvent만 받으므로, 실행기가 달라도 같은 런타임 API를 쓴다 (SCRUM-9).
- ACP 어댑터는 실행기 실행, Tool 실행, 모델 호출, 자체 자격증명 저장을 맡고 agents-room의 상태는 모른다 (SCRUM-23, 66).

## 런타임 구성요소

### Project/Workspace service

- 셸이 넘긴 폴더 경로를 정규화하고, 존재하는지·읽을 수 있는지·절대 경로인지 검증한다. 실패하면 다시 고르게 하고, 렌더러가 넘긴 경로도 검증 없이 믿지 않는다 (SCRUM-18, 31).
- `projectId`를 발급하고 최근 프로젝트를 관리한다. 폴더가 옮겨지거나 지워졌으면 "위치 다시 지정"을 요구하고, 다른 경로에서 대신 실행하지 않는다 (SCRUM-32).
- 최소 파일 트리를 읽기 전용, 깊이 제한으로 제공한다 (SCRUM-31). 프로젝트마다 에이전트 설정과 상태를 분리한다 (SCRUM-13).

### Agent config registry

- 프로젝트별 AgentConfig를 관리한다: `{agentId, projectId, name, harnessId, providerId, modelId | null, authMethodId, providerSettings {awsProfile?, awsRegion?, endpointUrl?}}`. 비밀은 담지 않는다 ([ADR 0004](adr/0004-model-authentication.md), SCRUM-10, 65).
- `providerId`는 `amazon-bedrock`과 `openai`다. MVP의 `amazon-bedrock`은 `endpointUrl`로 Bedrock mock 서버를 가리킨다 ([ADR 0004](adr/0004-model-authentication.md) 결정 10, SCRUM-67). 고를 수 있는 제공자, 모델, 인증 방식은 실행기 카탈로그([ADR 0003](adr/0003-acp-integration.md))와 정적 호환성 규칙으로 정한다: `claude-code`는 `amazon-bedrock`만 쓴다 (ADR 0004 결정 4). 모델은 `claude-code`가 Anthropic 계열, `codex`가 OpenAI GPT 계열만 쓴다 (제안). 설치되지 않은 실행기나 지원하지 않는 조합은 실행할 수 있다고 표시하지 않는다 (SCRUM-26, 58).
- 설정 상태(`configured`, `auth_required`, `unsupported`, `executable_missing`)를 계산한다 (SCRUM-25, 36).

### Process supervisor

- 어댑터를 자식 프로세스로 띄운다. `cwd`는 프로젝트 경로이고, env는 명시적으로 구성한다 (env 위생은 ADR 0004, SCRUM-9). MVP에서는 OS 수준 격리(sandbox)를 하지 않으므로 `cwd` 지정은 접근 제한이 아니다 (SCRUM-35, 표시 규칙은 [ux.md](ux.md#관찰한-것만-보여준다)).
- 종료 코드와 시그널을 감지한다. 에이전트를 끌 때(`stopAgent`)는 진행 중인 실행을 먼저 [취소](#실행-취소)하고 정해진 시간만 기다린다. 그다음 자손 목록 스냅샷([`ProcessHandle.descendants()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.html)) → stdin 닫기 → 대기 → 어댑터 종료 → 스냅샷에 남은 자손 종료 순서로 끝낸다. 상태 전이는 [ADR 0005](adr/0005-agent-event-model.md) 결정 4에 있다.
- 셸이 죽으면 런타임이 스스로 끝내고 자식 프로세스를 정리한다 (방식은 ADR 0001).

### ACP client + session manager

- 어댑터 프로세스마다 ACP 연결이 1개이고, 실행기가 달라도 같은 인터페이스를 쓴다. `initialize`(버전, capability) → 인증 → `session/new` 순서이며, 세부는 ADR 0003과 0004에 있다 (SCRUM-9, 23, 66).
- (`agentId`, `sessionId`)를 매핑한다. 세션마다 진행 중인 실행은 1개이고, 새 `session/prompt`는 앞 실행의 응답을 받은 뒤에만 보낸다 (SCRUM-24).
- 어댑터가 클라이언트에 보내는 요청에 응답한다. `session/request_permission`은 Permission broker로 넘기고, fs와 terminal 메서드는 MVP에서 광고하지 않는다.

### Event normalizer

- `session/update`, 요청과 응답, 프로세스 생명주기를 AgentEvent로 바꾸고 `seq`와 `runId`를 붙인다 ([ADR 0005](adr/0005-agent-event-model.md), SCRUM-23, 66).
- 에이전트별 현재 상태를 계산해서 상태 저장소에 반영한다. 상태 저장소가 단일 출처다. 업스트림 `AgentStateStore`의 개념을 이어받되, broadcast 메시지는 타입 검사를 한다 (SCRUM-19).
- 알 수 없는 update는 `unknownUpdate`로, Tool 호출은 ADR 0005의 병합 규칙대로 바꾼다. 내용을 추측해서 채우지 않는다 (SCRUM-27, 50).

### Permission broker

- 대기 중인 요청을 등록한다: `permissionId` ↔ (`agentId`, JSON-RPC 요청 id) (SCRUM-33).
- MVP 기본 정책은 [ADR 0005](adr/0005-agent-event-model.md) 결정 7에 있다. 에이전트별 세부 정책은 post-MVP다 (SCRUM-62).
- 실행을 취소하거나 에이전트를 끄면 대기 중인 요청 전부에 `cancelled`로 응답한다 (SCRUM-33).

### Persistence

- 앱 데이터 디렉터리에 저장한다 (경로는 구현 때 정한다). 업스트림이 쓰는 `~/.pixel-agents/`와는 공유하지 않는다. 프로젝트 설정도 사용자의 프로젝트 폴더 안에 쓰지 않는다 (제안).
- MVP 저장 대상은 최근 프로젝트, 프로젝트별 AgentConfig, 사무실 레이아웃, UI 설정이다 (SCRUM-32, 37). 레이아웃 편집(SCRUM-59)은 post-MVP다. 비밀과 "실행 중" 상태는 저장하지 않으므로, 앱을 다시 시작하면 모든 에이전트는 `offline`이다. 이벤트 이력은 post-MVP다 (SCRUM-52).
- tmp 파일에 쓴 뒤 rename하는 원자적 저장을 쓴다 (업스트림에서 이어받은 교훈). 설정 파일이 손상되면 그 파일은 덮어쓰지 않고 남겨 둔 채 안전한 초기값으로 시작한다. 사용자에게 알리고 "초기화 유지"와 "복구 시도" 중에서 고르게 한다 (제안, SCRUM-37).

### Renderer gateway

- 렌더러와 통하는 유일한 통로다. 계약은 `core/asyncapi.yaml`이고 TS와 Java 바인딩을 생성한다 (ADR 0005). 전송 방식은 ADR 0001에서 정한다 (SCRUM-8, 22).
- 렌더러가 연결되면 상태 스냅샷을 먼저 보내고, 그다음부터 이벤트를 보낸다. 메시지 청크는 묶어서 보낼 수 있지만, 상태 전이는 하나도 빠뜨리지 않는다 (SCRUM-28).
- 명령(목록은 ADR 0005)을 검증한다: 존재하는 `agentId`인지, 대기 중인 `permissionId`인지.

## 식별자

| 식별자                     | 발급 주체                       | 범위와 수명                                                              | 용도                                                                                |
| -------------------------- | ------------------------------- | ------------------------------------------------------------------------ | ----------------------------------------------------------------------------------- |
| `projectId`                | 로컬 런타임 (UUID)              | 등록된 폴더 1개. 경로를 다시 지정해도 유지된다                           | 프로젝트별 설정 분리 (SCRUM-13, 32)                                                 |
| `agentId`                  | 로컬 런타임 (UUID)              | AgentConfig 1개. 재시작해도 유지되며 캐릭터와 1:1이다                    | 이벤트와 권한 요청의 귀속 (SCRUM-28, 66)                                            |
| `sessionId`                | ACP 어댑터 (`session/new` 응답) | 어댑터 프로세스 1개의 수명 안. 재시작하면 새 값이다                      | ACP 메시지 라우팅 (SCRUM-23)                                                        |
| `runId`                    | 로컬 런타임 (UUID)              | `session/prompt` 1회                                                     | 늦게 온 이벤트 구분(SCRUM-24), 비정상 종료 귀속(SCRUM-47), 이력(SCRUM-52, post-MVP) |
| `toolCallId`               | ACP 어댑터                      | 세션 안에서만 유일하다. 전역 키는 (`agentId`, `sessionId`, `toolCallId`) | Tool 상태 병합, 중복 캐릭터·애니메이션 방지 (SCRUM-27)                              |
| `permissionId`             | 로컬 런타임 (UUID)              | 권한 요청 1건. 연결마다 따로 매겨지는 JSON-RPC 요청 id와 1:1로 대응한다  | 원래 요청에 응답 (SCRUM-33)                                                         |
| `seq`                      | 로컬 런타임                     | 에이전트마다 단조 증가하는 수신 순번                                     | 수신 순서 보존 (SCRUM-19, 52)                                                       |
| `workflowRunId` (post-MVP) | 로컬 런타임                     | 워크플로우 실행 1회                                                      | SCRUM-30, 44                                                                        |

- 렌더러는 JSON-RPC 요청 id를 보지 않는다. ACP가 발급한 ID(`sessionId`, `toolCallId`)는 불투명한 문자열로 다룬다.
- 실행 밖에서 온 세션 단위 update(예: `available_commands_update`)는 `runId = null`이다.
- 위 식별자와 Run의 단위, 아래 흐름의 상태·이벤트 이름은 ADR 0005에서 정했다. Jira는 실행 식별자가 필요하다는 것만 정했다 (SCRUM-23, 24, 47). Run을 `session/prompt` 단위로 정한 이유와 대안, 상태 전이표는 [ADR 0005](adr/0005-agent-event-model.md)에 있다.

## 주요 흐름

단계별 오류 코드와 상태 전이는 [ADR 0005](adr/0005-agent-event-model.md)에 있다. 아래는 정상 경로와 갈림길만 적는다.

### 프로젝트 열기

1. 사용자가 프로젝트 열기를 요청하면 셸이 OS 폴더 선택기를 연다. 취소하면 아무것도 바뀌지 않는다 (SCRUM-18, 31).
2. 고른 경로가 `openProject`로 런타임에 전달된다 (전달 경로는 ADR 0001).
3. Project/Workspace service가 경로를 검증하고, `projectId`를 발급하고, 최근 목록을 갱신한다. 실패하면 오류를 보내고 다시 고르게 한다.
4. 렌더러에 프로젝트 이름, 경로, 최소 파일 트리를 보낸다.

### 에이전트 시작

1. `startAgent {agentId}`를 받으면 AgentConfig를 확인하고 명령과 env를 구성한다. 상태는 `starting`이고, 아래 단계 중 하나가 실패하면 `error`가 된다 (SCRUM-23, 66).
2. 어댑터 프로세스를 실행한다 (`cwd` = 프로젝트 경로).
3. `initialize`(`protocolVersion` 1, client capability)를 보내 버전을 확인하고, 인증을 확인한다 ([ADR 0004](adr/0004-model-authentication.md)).
4. `session/new {cwd: 절대 경로, mcpServers: []}`로 받은 `sessionId`를 `agentId`에 묶고 `ready`가 된다.

### 프롬프트에서 캐릭터까지

1. `sendPrompt {agentId, text}`를 받으면 `runId`를 발급하고 `runStarted`를 낸 뒤 `session/prompt`를 보낸다. 상태는 `running`이다 (SCRUM-23).
2. Event normalizer가 `session/update` 스트림을 AgentEvent로 바꾸고 `seq`를 붙여 상태 저장소에 반영한다.
3. Renderer gateway가 이벤트를 보내고, 렌더러가 캐릭터 애니메이션과 Detail Panel을 갱신한다 (SCRUM-19, 27).
4. `session/prompt` 응답이 오면 `runEnded {outcome}`를 내고 `ready`로 돌아간다. 실행이 끝났는지는 이 응답으로만 판단한다 (결과 규칙은 ADR 0005 결정 5).

### 권한 요청

1. 어댑터가 `session/request_permission`을 보낸다 (JSON-RPC 요청 id N, SCRUM-33).
2. Permission broker가 `permissionId`를 발급하고 `permissionRequested`를 낸다. 상태는 `awaiting_permission`이다.
3. 렌더러가 대화상자를 보여주고, 사용자가 고른 `optionId`를 `respondPermission`으로 보낸다. 대화상자를 닫으면 요청은 대기 상태로 남는다 (정책은 ADR 0005 결정 7).
4. broker가 N에 `selected`로 응답하고 `permissionResolved`를 낸다. 남은 요청이 없으면 `running`으로 돌아간다.

### 실행 취소

1. `cancelRun {agentId, runId}`를 받으면 `session/cancel` 통지를 보내고, 대기 중인 권한 요청 전부에 `cancelled`로 응답한다. 상태는 `cancelling`이다 (SCRUM-24).
2. `session/prompt` 응답이 오면 `runEnded`를 낸다 (결과와 끝나지 않은 Tool 호출의 처리는 ADR 0005 결정 5, 6). 응답이 제한 시간 안에 오지 않으면 경고하고 사용자에게 강제 종료를 권한다 (제안). 강제 종료는 `stopAgent`와 같은 경로다.
3. 응답 뒤에 늦게 온 update는 끝난 `runId`에 붙이지 않는다. 다른 에이전트는 영향을 받지 않는다.

### 비정상 종료

1. Process supervisor가 요청하지 않은 프로세스 종료를 감지한다 (종료 코드, 시그널, SCRUM-47).
2. 진행 중인 실행이 있었으면 `runEnded {outcome: failed}`를 낸다. 완료로 표시하지 않는다.
3. `sessionEnded {reason: exited}`를 내고 상태는 `error`가 된다. GUI는 계속 돈다 (SCRUM-22).
4. 다시 시작하면 새 프로세스, 새 세션이다. 이전 세션을 이어간다고 표시하지 않고, 자동 재시작을 무한히 반복하지 않는다.

### 앱 종료

1. 셸이 닫힌다. 실행 중인 에이전트가 있을 때의 경고는 [ux.md](ux.md#화면-구성)에 있다.
2. 런타임이 stdin EOF나 부모 프로세스 종료를 감지한다 (방식은 ADR 0001). 셸이 비정상으로 끝나도 같다.
3. 런타임이 모든 어댑터 프로세스 트리를 정리하고 끝난다.

## 현재 코드와 목표 구조

판정은 계획이다. 파일 단위 판정과 이유는 [upstream.md](upstream.md#상속-범위)에만 있다.

- 그대로 쓴다: 픽셀 월드(`webview-ui/src/office/`의 엔진·레이아웃·에디터·스프라이트, `OfficeCanvas.tsx`), `WebSocketTransport`, `core/src/transport.ts` 같은 렌더러 공용 코드.
- 고쳐 쓴다: 렌더러의 메시지 처리(`useExtensionMessages.ts`, `toolUtils.ts`)와 전송 분기(`transport/index.ts`), 계약(`core/asyncapi.yaml`, ADR 0005), e2e.
- 개념만 옮긴다: `server/`(Node/Fastify)는 Java로 다시 구현하고 상태 저장소, 연결 시 스냅샷, 원자적 저장 같은 구조만 가져온다 ([ADR 0002](adr/0002-java-spring-runtime.md)).
- 버린다: Claude hooks와 JSONL 처리(`server/src/providers/hook/**`, `fileWatcher.ts`, `transcriptParser.ts`), `adapters/vscode/**`.
- 새로 만든다: Detail Panel, 권한 대화상자, 에이전트 설정 화면([ux.md](ux.md#화면-구성)), 데스크톱 셸, Java 런타임.

## 관련 문서

- [ux.md](ux.md): [화면 구성](ux.md#화면-구성), [상태 어휘](ux.md#상태-어휘), [관찰한 것만 보여준다](ux.md#관찰한-것만-보여준다), [문구 목록](ux.md#문구-목록)
- ADR: [0001 데스크톱 셸 프레임워크](adr/0001-desktop-framework.md), [0002 Java/Spring 로컬 런타임](adr/0002-java-spring-runtime.md), [0003 ACP 연동 방식](adr/0003-acp-integration.md), [0004 모델 제공자 인증과 자격증명](adr/0004-model-authentication.md), [0005 AgentEvent 모델](adr/0005-agent-event-model.md), [목록과 작성 규칙](adr/README.md)
- [upstream.md](upstream.md): [포크 기준](upstream.md#포크-기준), [상속 범위](upstream.md#상속-범위), [레거시 참조](upstream.md#레거시-참조)
