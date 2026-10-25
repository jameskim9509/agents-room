# agents-room

agents-room은 코딩 에이전트를 픽셀 캐릭터로 보여주는 게임형 데스크톱 IDE를 목표로 한다. 로컬 런타임이 Codex, Claude Code 같은 실행기(Harness)의 ACP 어댑터를 자식 프로세스로 띄우고, 런타임 안의 Java ACP Client가 [ACP(Agent Client Protocol)](https://github.com/agentclientprotocol/agent-client-protocol/blob/main/README.md)로 연결하는 구조다. 에이전트마다 픽셀 사무실에 캐릭터가 하나씩 있고, 캐릭터를 선택하면 오른쪽 Detail Panel에서 요청, 현재 Tool, 이벤트 순서, 결과를 본다. 원칙은 하나다: 에이전트가 ACP로 실제로 보고한 것만 보여주고, 보고되지 않은 계획, 서브에이전트, 진행 상태는 지어내지 않는다. 오픈소스 [pixel-agents](https://github.com/pixel-agents-hq/pixel-agents)를 포크해서 시작했다.

## 현재 상태

2026-10-23 기준이며, agents-room 기능은 아직 동작하지 않는다.

- SCRUM Sprint 0(2026-10-18 – 2026-10-25)을 진행하고 있다. 목표는 "데스크톱 IDE에서 Codex 에이전트 하나를 실행하고 ACP 이벤트를 수신하여 픽셀 캐릭터와 상세 패널에 표시할 수 있다"이다. 핵심 스토리는 SCRUM-23, 19, 27이고, 전체 목록은 Jira에서 본다.
- 다음은 SCRUM Sprint 1(계획)이다.
- 배포 서비스의 모델은 Amazon Bedrock으로 제공한다. MVP에서는 Codex를 ChatGPT 로그인으로, Claude Code를 Bedrock mock 서버(SCRUM-67)로 확인한다. Claude 계정 로그인은 쓰지 않는다 → [ADR 0004](docs/adr/0004-model-authentication.md).
- 코드는 대부분 pixel-agents 기준선(`d1e007a`) 그대로다: VS Code 확장과 `npx pixel-agents` standalone CLI이며, Claude Code hooks와 JSONL로 동작한다. 데스크톱 셸 뼈대(`desktop/`)와 Java 런타임 골격(`runtime/`)이 들어갔고, ACP 연동은 아직 없다.
- `package.json`의 이름은 아직 업스트림 그대로다. 상속한 배포 워크플로는 지웠고, 이 포크에서는 npm·Marketplace로 배포(publish)하지 않는다 → [남은 업스트림 흔적](docs/upstream.md#남은-업스트림-흔적).
- 처음이면 [구성도](docs/architecture.md#구성도) → [ADR 0003](docs/adr/0003-acp-integration.md) → [상태 어휘](docs/ux.md#상태-어휘) 순서로 읽는다. ADR 0001–0005는 모두 합의되었다. 남은 결정은 각 ADR의 "열린 질문"에 있다.

## 문서 지도

| 문서                                         | 이럴 때 본다                                                                                                                                                                                                                                                                                       |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [docs/architecture.md](docs/architecture.md) | 용어 뜻, 구성요소 책임, 주요 흐름을 확인할 때                                                                                                                                                                                                                                                      |
| [docs/ux.md](docs/ux.md)                     | 화면 배치, 상태 표시, UI 문구를 정할 때                                                                                                                                                                                                                                                            |
| [docs/upstream.md](docs/upstream.md)         | 상속 코드를 고칠지 버릴지, 업스트림 커밋을 가져올지 판단할 때                                                                                                                                                                                                                                      |
| [docs/adr/](docs/adr/README.md)              | 왜 이렇게 정했는지, 무엇이 아직 미정인지 볼 때                                                                                                                                                                                                                                                     |
| [CHANGELOG.md](CHANGELOG.md)                 | 스프린트마다 무엇이 들어갔는지 볼 때                                                                                                                                                                                                                                                               |
| [CLAUDE.md](CLAUDE.md)                       | 상속 코드를 고칠 때 (Claude Code가 자동으로 읽는다). 업스트림 코드를 설명하며 목표 구조가 아니다. 맨 앞 안내문이 이를 밝힌다. 안의 `CONTEXT.md`, `docs/adr/0001`은 지운 업스트림 문서다 → [레거시 참조](docs/upstream.md#레거시-참조). 수치 차이 → [알려진 불일치](docs/upstream.md#알려진-불일치) |
| [e2e/README.md](e2e/README.md)               | e2e 테스트를 쓰거나 고칠 때. 작성 규칙과 자동 생성되는 테스트 목록이 있다                                                                                                                                                                                                                          |

## 스프린트 운영

Jira는 백로그, 스프린트, 인수 조건의 단일 출처다. 문서는 스프린트가 끝나도 남는 설계 지식을 담고, 스토리를 복사하지 않고 `SCRUM-nn` 키로 참조한다.
MVP 범위는 Jira 라벨로 판단한다. `post-mvp` 라벨이 붙은 이슈는 스프린트에 배정되어 있어도 post-MVP다.

| 시점          | Jira                            | 문서                                                                                                                                                                                                                                                   |
| ------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 스프린트 계획 | 스토리와 인수 조건을 확정한다   | 필요한 결정이 있으면 ADR을 `Proposed`로 추가한다                                                                                                                                                                                                       |
| 구현 중       | 상태를 갱신한다                 | 설계나 화면이 바뀌면 같은 PR에서 `architecture.md`나 `ux.md`를 고친다. 제3자 코드나 에셋을 넣으면 `THIRD_PARTY_NOTICES.md`를 고친다. e2e 테스트를 바꾸면 `npm run e2e:inventory`로 목록을 다시 만든다. 머지된 변경은 CHANGELOG `[Unreleased]`에 적는다 |
| 결정 합의     | 관련 스토리에 ADR 경로를 남긴다 | ADR 상태를 `Accepted`로 바꾸고 `docs/adr/README.md` 목록을 갱신한다                                                                                                                                                                                    |
| 스프린트 리뷰 | 완료 처리한다                   | CHANGELOG `[Unreleased]`를 `## [Sprint N] - 날짜` 섹션으로 옮기고 완료한 키를 적는다                                                                                                                                                                   |

PR 제목은 Conventional Commits 형식이다. 상속한 `.github/workflows/pr-title.yml`이 강제하며, type은 feat, fix, refactor, chore, docs, style, perf, test, ci, build 중 하나이고 subject는 소문자로 시작한다.
Jira 키는 PR 본문에 적는다.

## 개발 빠른 시작

아래 명령은 상속한 pixel-agents 코드를 빌드하고 실행한다.

```bash
# Node 22 (.nvmrc). package.json engines: node >= 22.12
npm install        # 루트와 server, webview-ui, desktop 워크스페이스
npm run build      # AsyncAPI 생성 → 타입 검사 → lint → esbuild → Vite (dist/)
npm test           # webview·server·desktop Vitest와 패키지 계약 테스트
```

- VS Code 확장 디버그: `.env.example`을 `.env`로 복사한 뒤 F5 ("Run Extension"). `launch.json`이 `.env`를 읽는데 저장소에는 `.env`가 없다.
- standalone: `node dist/cli.js`. 포트를 주지 않으면 OS가 임시 포트를 고른다. `--port 3100`, `--host 127.0.0.1`로 지정할 수 있다. 출력된 URL에는 토큰이 들어 있으므로 공유하지 않는다.
- 렌더러만 개발: `cd webview-ui && npm run dev`. Vite dev 서버와 browser mock으로 백엔드 없이 UI를 띄운다.
- 데스크톱 셸: `npm run desktop:start`(렌더러와 셸을 빌드해 `app://bundle`로 띄운다), `npm run desktop:dev`(Vite dev 서버와 Electron). 로컬 런타임은 `runtime/build/dev-runtime.json`이나 환경변수 `AGENTS_ROOM_RUNTIME_COMMAND`(JSON argv)로 찾는다. 처음에는 `npx install-electron --no`로 Electron 실행 파일을 받는다. 셸 e2e는 `npm run desktop:build` 뒤 `npm run e2e:desktop`이다 → [architecture.md](docs/architecture.md#데스크톱-셸).
- e2e: 빌드한 뒤 `npm run e2e`. 첫 실행 때 VS Code를 내려받는다. standalone 테스트에는 Playwright Chromium이 필요하다 (`npx playwright install chromium`).
- 로컬 런타임: `npm install` 뒤 `npm run runtime:build`(빌드와 테스트)나 `npm run runtime:test`. JDK 17 이상이 필요하고, 빌드가 node로 계약 바인딩을 만든다 → [로컬 런타임 코드 구성](docs/architecture.md#로컬-런타임-코드-구성).
- `npm run import-tileset`은 쓰지 않는다 (스크립트 파일이 없다).
- 색, 그림자, 폰트 규칙은 ESLint(`eslint-rules/pixel-agents-rules.mjs`)가 강제한다 → [docs/ux.md](docs/ux.md#상속한-시각-요소).

## 업스트림과 라이선스

- [pixel-agents](https://github.com/pixel-agents-hq/pixel-agents)(MIT, Pablo De Lucca)의 포크다. 라이선스는 MIT([LICENSE](LICENSE))이고, 제3자 고지는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 있다.
- 보안 취약점은 공개 이슈로 올리지 말고 저장소 관리자에게 비공개로 알린다.
