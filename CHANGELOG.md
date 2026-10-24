# Changelog

agents-room의 변경 기록이다. 형식은 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)를 따른다.
릴리스 버전이 아직 없으므로 섹션은 `[Unreleased]`와 `## [Sprint N] - YYYY-MM-DD`를 쓴다. 언제 옮기는지는 [README의 스프린트 운영](README.md#스프린트-운영)에 있다.
항목에는 관련 Jira 키(`SCRUM-nn`)를 붙이고, 반영한 업스트림 커밋은 해시와 PR 번호를 적는다.

포크 이전 기록(pixel-agents v1.4.1까지)은 업스트림 [CHANGELOG.md (d1e007a)](https://github.com/pixel-agents-hq/pixel-agents/blob/d1e007a9fdf3003c252d2973abe1999ae59aec33/CHANGELOG.md)에 있다. 포크 기준 커밋 `d1e007a`(#428, e2e 의존성 갱신)는 v1.4.1 바로 다음 커밋이며 CHANGELOG 항목이 없다. 포크 기준은 [docs/upstream.md](docs/upstream.md#포크-기준)를 본다.

## [Unreleased]

### Added

- 프로젝트 문서: `docs/architecture.md`, `docs/ux.md`, `docs/upstream.md`, `docs/adr/` (README와 ADR 0001–0005), `THIRD_PARTY_NOTICES.md`.

### Changed

- `README.md`를 agents-room 기준으로 다시 썼다.
- `CHANGELOG.md`를 agents-room 기록으로 새로 시작했다.
- `CLAUDE.md` 맨 앞에 이 포크가 agents-room이며 목표 구조는 `docs/`에 있다는 안내를 추가했다.
- `LICENSE`에 `Copyright (c) 2026 agents-room contributors`를 추가했다. 업스트림 저작권 줄은 그대로 두었다.
- 팀 결정 반영 (2026-10-22): ADR 0001을 Accepted로 바꿨다 (데스크톱 셸 = Electron). Bedrock(SCRUM-25·34)과 서브에이전트 캐릭터(SCRUM-56)를 post-MVP로 정했다. `post-mvp` 라벨이 붙은 이슈는 모두 post-MVP라는 원칙을 README에 적었다.
- 팀 결정 반영 (2026-10-23): ADR 0004와 0005를 Accepted로 바꿨다. 남은 자격증명 환경변수는 자식 프로세스에서 지운다. MVP의 Bedrock은 mock 컨테이너로 확인한다 (ADR 0004 결정 10). ADR 0003은 공식 Java SDK 사용만 합의했고, 어댑터를 설치 파일에 동봉하는 안(결정 12)을 추가했다.
- 어댑터 실행 방식 확인 (2026-10-23): 어댑터는 Electron 실행 파일을 Node로 써서 실행하고 `runAsNode` fuse를 켜 둔다(합의). Electron 43~45의 내장 Node 24가 claude-agent-acp 요구(Node ≥ 22)를 만족함을 확인했다. codex-acp는 Apache-2.0이라 동봉하고, Claude Code 어댑터는 Claude Agent SDK가 Anthropic 독점 라이선스라 조건을 확인하기 전까지 동봉하지 않는다.
- 팀 결정 반영 (2026-10-23, 추가): ADR 0001(결정 2~8, SCRUM-17 MVP 범위 포함)과 ADR 0003을 Accepted로 바꿨다. ADR 0002에 JDK 25, Spring Boot 4.x, WebFlux gateway, Gradle `runtime/`을 결정으로 더했다. 런타임 동봉 방식(jlink 또는 native-image)만 남았다. 배포 서비스의 모델은 Bedrock으로 제공하고, Claude Code는 Claude 계정 로그인 없이 Bedrock(MVP는 mock, SCRUM-67)으로만 연결한다. Codex의 ChatGPT 로그인(SCRUM-65)은 MVP 확인용이다 (ADR 0004). UI: "프로젝트 열기", "항상 허용"·"항상 거부" 포함, 펫·카펫·인트로 투어 유지, `offline` 캐릭터 표현 (ux.md).
- 팀 결정 반영 (2026-10-23, 셋째): 결정을 따르는 코드가 없으면 Accepted ADR도 Proposed처럼 바로 고친다는 규칙을 `docs/adr/README.md`에 적었다. 런타임은 jlink JRE와 함께 싣는다 (ADR 0002 결정 9, native-image는 대안으로 옮겼다). SCRUM-17의 MVP 범위를 SCRUM-68(Sprint 1)로 만들고 ADR 0001에 연결했다. 업데이트 파일을 올릴 위치를 ADR 0001의 열린 질문에 더했다. SCRUM-18의 "폴더 열기"를 "프로젝트 열기"로 바꿨다. ADR 0004 결정 10에 mock 연결에서도 Claude Code가 실제로 실행된다는 점을 적었다.
- Claude Code 연동 확인 (2026-10-23): claude-agent-acp 0.94.0을 Claude 로그인 없는 격리 환경에서 Bedrock mock에 붙여 `session/new`부터 권한 요청, Write 도구 실행까지 통과함을 확인했다 (ADR 0003 맥락, ADR 0004 결정 10). Anthropic 약관 원문을 다시 읽고 ADR 0003의 열린 질문을 두 가지(인증 방식 제한, Bedrock 비용 주체)로 좁혔다. 실행기 이름은 어댑터의 `agentInfo.title`("Claude Agent")을 쓴다 (ux.md, 제안). 업데이트 파일은 GitHub Releases에 올린다 (ADR 0001 결정 8, SCRUM-68).
- 팀 결정 반영 (2026-10-24): Bedrock mock은 컨테이너가 아니라 `node`로 실행하는 Node.js 스크립트이며 Docker는 쓰지 않는다 (ADR 0004 결정 10, SCRUM-66·67, README, architecture.md). MVP의 e2e CI는 Windows에서만 돌린다 (ADR 0001 결정 7). SCRUM-67 인수 조건에 모델 목록 요청과 임의의 모델 ID 처리를 더했다. 이 포크에서 GitHub Actions를 켰다. 상속한 배포 워크플로를 지운 삭제가 main에 올라간 뒤에만 Release를 발행한다는 규칙을 upstream.md에 적었다 (SCRUM-68).

### Removed

- 업스트림 문서 8개: `CONTEXT.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `docs/adr/0001-consent-choices-send-immediately-and-revise-as-absolute-state.md`, `docs/external-assets.md`, `docs/research/claude-harness-spawn-formats.md`, `webview-ui/README.md`. 요약과 원문 링크는 [docs/upstream.md](docs/upstream.md#은퇴한-문서)에 있다.
- 업스트림 배포 워크플로 2개: `.github/workflows/publish-extension.yml`(Release 발행 때 VS Code 확장과 npm 게시), `.github/workflows/update-badges.yml`(배지 갱신). 이 포크에서 Release를 발행하면 업스트림 패키지 게시를 시도하기 때문이다 (SCRUM-68, [upstream.md 규칙](docs/upstream.md#남은-업스트림-흔적)).
