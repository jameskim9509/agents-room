# ADR 0001: 데스크톱 셸 프레임워크

## 상태

Accepted — 결정 1(Electron)은 2026-10-22에, 결정 2~8은 2026-10-23에 팀이 합의했다. Jira SCRUM-8은 셸을 "Electron 또는 Tauri"로 열어 두었다.

## 날짜

2026-10-21

## 맥락

용어는 [용어집](../architecture.md#용어집)을, 구성요소의 위치는 [구성도](../architecture.md#구성도)를 따른다.

Jira 제약:

- SCRUM-8: 셸 후보는 Electron과 Tauri다. 렌더러(Renderer)와 로컬 런타임(Local runtime)을 분리하고, IPC/Event로 양방향 통신하며, 자식 프로세스 실행 기반을 갖춘다.
- SCRUM-22: 에이전트가 오래 실행돼도 UI가 막히지 않는다. 프로세스 시작과 종료는 렌더러가 아니라 로컬 런타임이 맡는다.
- SCRUM-18: OS 폴더 선택 창의 결과가 IPC로 GUI에 전달된다.
- SCRUM-36: Windows 설치 파일로 설치한다. 대상은 Node.js나 Electron 개발 환경이 없는 사용자다. 웹 서버 URL 입력을 요구하지 않는다.
- SCRUM-17(배포와 업데이트, MVP 에픽): 완료 기준은 설치 패키지 외에 앱 버전 식별과 업데이트 확인, 업데이트 실패에 대비한 기본 복구 전략, 릴리스 파이프라인 기반을 요구한다. 설치는 SCRUM-36이, 나머지는 SCRUM-68(MVP)이 다룬다.
- post-MVP: SCRUM-63은 서명된 업데이트와 실패 복구를, SCRUM-64는 macOS·Linux 패키지와 핵심 GUI·상태 표시의 플랫폼 간 일관성을, SCRUM-61은 프로젝트마다 별도 창을 요구한다.

현재 코드 사실:

- 렌더러(`webview-ui/`)는 React 19와 Vite 8이다. Canvas를 pixel-perfect로 그리고(`imageSmoothingEnabled=false`, DPR 기반 정수 확대), 알림음에 Web Audio를 쓴다.
- 렌더러는 지금까지 Chromium에서만 테스트했다. VS Code webview는 Electron이고, standalone e2e는 Playwright 기본 Chromium이다 (추정, 설정에 browser project가 없다).
- `MessageTransport`(`core/src/transport.ts`)는 wire에 묶여 있지 않다. `WebSocketTransport`는 그대로 다시 쓸 수 있고, 바뀌는 것은 `createTransport()`의 URL 유도뿐이다.
- 업스트림 서버(`server/src/httpServer.ts`)는 기본으로 127.0.0.1에 바인딩한다. standalone의 `/ws`는 연결 때 Origin만 검사하고(Origin이 없으면 허용), 토큰은 권한 있는 메시지에만 요구한다. Origin/Host 검사만으로는 DNS rebinding을 막지 못한다는 점은 코드 주석에 적혀 있다.
- 로컬 런타임은 Java라서 앱에 함께 실어야 한다 ([ADR 0002](0002-java-spring-runtime.md)).

## 결정

1. 데스크톱 셸(Desktop shell)은 Electron으로 만든다.
2. 패키징과 업데이트는 electron-builder NSIS(사용자별 설치)와 electron-updater로 한다. electron-updater는 Squirrel.Windows를 지원하지 않으므로 Squirrel 계열은 쓰지 않는다 ([electron-updater](https://www.electron.build/docs/features/auto-update/)).
3. main 프로세스가 앱에 동봉한 로컬 런타임을 `child_process.spawn`으로 띄운다. 런타임은 jlink로 만든 JRE와 함께 싣는다 (ADR 0002 결정 9). 에이전트 프로세스는 런타임이 띄운다.
4. 데이터 경로: 런타임은 127.0.0.1의 임시 포트에 WebSocket(`ws://127.0.0.1:<port>/ws`)을 연다. 렌더러는 기존 `WebSocketTransport`로 직접 접속하므로 이벤트가 main 프로세스를 거치지 않는다 (SCRUM-22, [Electron 성능 지침](https://www.electronjs.org/docs/latest/tutorial/performance)). Jira의 "IPC/Event"(SCRUM-8, 18, 22)는 프로세스 사이 통신 전반으로 읽으며, 이 WebSocket도 여기에 든다. 셸은 preload로 URL과 토큰만 넘긴다. 런타임은 실행할 때마다 새로 만든 랜덤 토큰을 연결 자체에 요구하고 Origin도 검사한다. 업스트림보다 강화한 것이다.
   - 토큰은 셸이 실행마다 만드는 32바이트 난수(base64url)다. 환경변수 `AGENTS_ROOM_TOKEN`으로 넘기고, argv에는 넣지 않는다.
   - 렌더러는 토큰을 `?token=` 쿼리로 보낸다. 토큰이 없거나 틀리면 런타임은 업그레이드 전에 HTTP 401로 답한다(상수 시간 비교). Origin 헤더가 있고 허용 목록 밖이면 403이다.
   - 허용 목록은 셸이 `AGENTS_ROOM_ALLOWED_ORIGINS`(쉼표 구분)로 넘긴다. 배포판은 `app://bundle`이고, `desktop:dev`에서만 `http://localhost:5173`이 붙는다.
   - 토큰은 로그에 남기지 않는다. 런타임은 접근 로그를 끄고 `token=`을 가린다. 셸도 `runtime.log`에 쓸 때 토큰을 `***`로 가린다.
5. 제어 경로: 셸과 런타임은 stdio로 통한다. 런타임은 준비되면 포트를 담은 준비 줄을 stdout에 한 줄 쓴다. stdin EOF나 부모 종료(`ProcessHandle.parent().onExit()`)를 감지하면 어댑터 프로세스 트리를 정리하고 스스로 끝낸다. 셸은 종료할 때 런타임 프로세스 트리를 정리한다.
   - 실행: `<java> -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -jar <jar>`. stdin, stdout, stderr는 모두 pipe이고, 셸은 stdin을 런타임이 사는 동안 열어 둔다. 셸은 런타임을 모든 OS에서 detached로 띄운다. Windows에서는 Node(libuv)가 detached가 아닌 자식을 kill-on-close job object에 넣는다. 그러면 셸이 죽을 때 OS가 런타임을 바로 끝내서, 런타임이 어댑터를 정리하지 못한다(Windows에서는 런타임 자신의 job object가 어댑터를 강제로 끝낼 뿐이다, [architecture.md](../architecture.md#셸과-런타임의-연결)). detached면 런타임은 stdin EOF를 받고 어댑터를 정리할 시간을 갖는다. POSIX에서는 런타임이 자기 프로세스 그룹을 이끈다. 환경변수 `AGENTS_ROOM_DATA_DIR`은 Electron `userData`의 절대 경로이며, 런타임은 `<dir>/runtime/` 아래에만 파일을 둔다.
   - 개발 중에는 런타임 Gradle 빌드가 쓰는 `runtime/build/dev-runtime.json`(`{"java": "<JDK 25 java>", "jar": "<boot jar>"}`)을 읽는다. 환경변수 `AGENTS_ROOM_RUNTIME_COMMAND`(JSON argv)가 있으면 그것이 이긴다 (테스트용). 설치판의 경로는 패키징 때 정한다.
   - 셸의 테스트·개발용 환경변수(`AGENTS_ROOM_RUNTIME_COMMAND`, `AGENTS_ROOM_READY_TIMEOUT_MS`, `AGENTS_ROOM_USER_DATA_DIR`, `AGENTS_ROOM_RENDERER_URL`)는 패키징하지 않은 실행(`app.isPackaged`가 false)에서만 읽는다. 설치판은 모두 무시한다. 그렇지 않으면 사용자 환경변수를 바꿀 수 있는 프로그램이 설치판에 아무 명령이나 띄우게 하고 토큰까지 넘겨받는다. 설치판 e2e(SCRUM-36)에 이 값이 필요하면 그때 다시 정한다.
   - 준비 줄: `AGENTS_ROOM_READY {"port":<int>}` + `\n` (UTF-8). stdout에는 이 한 줄만 쓰고, 배너와 로그는 모두 stderr로 보낸다. 읽는 쪽은 끝의 `\r`을 지운다.
   - 셸은 준비 줄을 30초 기다린다. 준비 줄이 온 뒤에만 접속 정보를 렌더러에 준다. 그 밖의 stdout과 stderr는 `<userData>/logs/runtime.log`에 쓴다. 그 파일을 열 수 없으면 `runtime-<pid>.log`에 쓴다.
   - 시작에 실패하거나(실행 명령 없음, 실행 실패, 시간 초과, 준비 전 종료) 런타임이 예상하지 않게 끝나면 네이티브 대화상자를 띄운다. 로그 경로를 보여주고 "다시 시작"과 "종료"를 고르게 한다. 자동 재시작은 하지 않는다.
   - 앱을 끌 때는 stdin을 닫고 3초 기다린 뒤, 런타임이 살아 있으면 프로세스 트리를 강제로 끝낸다 (Windows `taskkill /pid N /T /F`, 그 밖은 프로세스 그룹).
6. 보안 기준선은 [Electron 보안 체크리스트](https://www.electronjs.org/docs/latest/tutorial/security)를 따른다.
   - `contextIsolation`과 `sandbox`를 켜고 `nodeIntegration`을 끈다. 단일 인스턴스로 실행한다 (`app.requestSingleInstanceLock`).
   - preload의 `contextBridge`로 좁은 API만 노출한다: `window.agentsRoom.getRuntimeEndpoint(): Promise<{url, token}>`(런타임 접속 정보)와 `window.agentsRoom.pickProjectFolder(): Promise<string | null>`(폴더 선택 대화상자, SCRUM-18, 취소하면 `null`). 창 생명주기 API는 필요해질 때 더한다. 원시 `ipcRenderer`는 노출하지 않는다. IPC sender는 메인 창의 최상위 frame이고 origin이 렌더러 출처일 때만 받는다.
   - `file://` 대신 custom protocol을 쓴다. 렌더러 출처는 `app://bundle`이고, 스킴은 `standard`, `secure`, `supportFetchAPI` 권한으로 등록한다. 응답 헤더 CSP는 오류 응답(400, 404)을 포함한 모든 응답에 붙고, 값은 `default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data: blob:; connect-src 'self' ws://127.0.0.1:*; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`이다. `connect-src 'self'`는 렌더러가 번들한 에셋을 `fetch`하는 데 쓴다.
   - 새 창, webview, 렌더러 출처 밖으로의 내비게이션과 리디렉션을 막는다. 권한 요청은 모두 거절하고, 렌더러 출처의 loopback 접근만 예외로 둔다. fuses를 점검한다. 단, 어댑터를 Electron 실행 파일로 돌리므로 `runAsNode` fuse는 켜 둔다 ([ADR 0003](0003-acp-integration.md) 결정 12).
7. e2e는 Playwright `_electron.launch`로 한다. 기존 `e2e/helpers/launch.ts`의 e2e 실행 코드를 옮겨 쓴다 ([Playwright Electron](https://playwright.dev/docs/api/class-electron)). MVP의 CI는 e2e를 `windows-latest`에서만 돌린다 (SCRUM-36). 상속한 e2e 행렬(VS Code와 standalone)은 `ci.yml`에서 지웠다. Windows 데스크톱 e2e는 `ci.yml`의 `desktop-windows` job이 돌린다. 가짜 런타임으로 셸을, 실제 런타임 jar로 셸과 런타임의 연결을 확인한다. macOS·Linux는 SCRUM-64 때 더한다.
8. SCRUM-17의 MVP 범위 (SCRUM-68): 앱 버전을 표시하고 electron-updater로 새 버전이 있는지 확인한다. 사용자가 업데이트를 고르면 내려받은 파일을 sha512로 검증한 뒤 설치하고, 다운로드나 검증에 실패하면 설치하지 않고 기존 버전을 그대로 쓴다. CI는 Windows NSIS 설치 파일과 업데이트 정보 파일(`latest.yml`)을 만들어 이 저장소의 GitHub Releases에 올리고, 앱은 electron-updater의 GitHub provider로 읽는다. 저장소는 public이어야 한다. private 저장소는 앱에 GitHub 토큰을 넣어야 하므로 쓰지 않는다 ([auto-update](https://www.electron.build/docs/features/auto-update/)). 코드 서명, 서명 검증, 설치 도중 실패의 복구는 post-MVP다 (SCRUM-63). 서명하지 않은 설치 파일에는 Windows SmartScreen 경고가 뜰 수 있다.

## 검토한 대안

### Tauri

- 장점:
  - 작다. Tauri는 앱이 최소 600KB까지 작아질 수 있다고 한다. 실제 앱(Authme)의 설치 파일이 Tauri 약 2.5MB, Electron 약 85MB였다는 [비교](https://www.levminer.com/blog/tauri-vs-electron)가 있다 (제3자 측정).
  - 플러그인 권한은 capability로 허용해야 쓸 수 있다. 다만 앱이 등록한 command는 기본으로 모든 창에서 호출된다 ([capabilities](https://v2.tauri.app/security/capabilities/)).
  - updater 서명이 강제된다 ([updater](https://v2.tauri.app/plugin/updater/)). Windows에서는 WebView2(Chromium)를 쓴다.
- 단점:
  - macOS는 WKWebView, Linux는 WebKitGTK라 엔진이 다르고 OS 업데이트에 묶인다 ([webview 버전](https://v2.tauri.app/reference/webview-versions/)). Tauri 문서 스스로 WebKitGTK에서 canvas가 조용히 느린 경로로 떨어질 수 있고 NVIDIA DMABUF 문제가 있다고 경고한다 ([Linux 그래픽](https://v2.tauri.app/develop/debug/linux-graphics/)). SCRUM-64의 일관성 요구와 충돌한다.
  - JVM은 단일 바이너리가 아니라서 [sidecar](https://v2.tauri.app/develop/sidecar/)(externalBin) 모델과 맞지 않는다 (추정). 공식 문서는 sidecar 감독(재시작, 트리 종료)을 다루지 않으므로 앱이 Rust로 직접 짜야 한다. Rust라는 세 번째 언어가 생긴다.
  - tauri-driver를 직접 쓰면 macOS를 지원하지 않고, macOS는 WebdriverIO 서비스의 embedded server로만 지원된다 ([WebDriver](https://v2.tauri.app/develop/tests/webdriver/)). 기존 Playwright e2e 코드는 그대로 쓸 수 없다.
- 판단: Windows만 보면 위험이 작지만, SCRUM-64까지 생각하면 렌더링 일관성 위험이 크다. Java 런타임을 함께 실으면 크기 이점도 줄어든다 (추정).

### 그 밖의 대안

- 브라우저와 로컬 서버 (현재 standalone 방식): 코드는 이미 있지만 SCRUM-36(웹 서버 URL을 요구하지 않는다)과 SCRUM-8(데스크톱 앱)을 충족하지 못하고 OS 대화상자도 없다. 채택하지 않는다.
- 셸 IPC로 이벤트 중계: 열린 포트와 토큰은 없지만 모든 이벤트가 main 프로세스를 지난다. Electron 성능 지침은 main을 막으면 앱 전체가 멈춘다고 경고한다. 이벤트에는 쓰지 않고 결정 6의 preload API에만 쓴다.

## 결과

- 좋은 점: 모든 OS에서 같은 Chromium으로 그린다. Playwright `_electron` 기반 e2e 구성과 CI 구성을 다시 쓴다 (MVP는 Windows만, 결정 7). 셸을 TypeScript로 짜므로 언어가 TS와 Java 둘로 끝난다.
- 비용과 위험:
  - 설치 파일이 Tauri보다 크다. 위 비교에서는 약 80MB 차이였다 (제3자 측정).
  - Electron은 8주마다 메이저 버전이 나오고 최신 3개 메이저만 지원한다 ([Electron 일정](https://www.electronjs.org/docs/latest/tutorial/electron-timelines)). 이를 따라가는 업그레이드 비용이 든다.
  - Tauri의 플러그인 권한 모델 같은 장치가 없으므로 보안 체크리스트(결정 6)를 직접 챙겨야 한다.
  - Electron과 Tauri 모두 자동 rollback이 없다. SCRUM-17의 기본 복구 전략과 SCRUM-63의 실패 복구는 직접 설계해야 한다.
- 후속 작업: 업데이트 확인과 CI 릴리스 (SCRUM-68), 설치판에서 런타임을 찾는 경로와 fuses 설정 (SCRUM-36), 첫 Release 전에 `publish-extension.yml` 삭제를 GitHub의 main에 반영 ([upstream.md 규칙](../upstream.md#남은-업스트림-흔적)), 서명 방식 결정 (post-MVP). `createTransport()`의 데스크톱 분기와 preload API는 `desktop/`에 들어갔다 ([architecture.md](../architecture.md#데스크톱-셸)).
- 재검토 조건: Tauri의 CEF runtime이 stable이 될 때, 또는 Windows 전용으로 확정할 때. CEF runtime은 [v3 alpha](https://github.com/tauri-apps/tauri/releases)로 진행 중이고 (2026-10-20 기준), 성숙도는 확인하지 못했다 (미검증).

### 열린 질문

- SCRUM-61에서 런타임을 앱마다 하나 둘지, 창마다 하나 둘지.
- 설치 크기 예산을 얼마로 할지. Jira에 정해져 있지 않다.

## 관련 이슈

SCRUM-8, 17, 18, 22, 36, 61, 63, 64, 68 / [ADR 0002](0002-java-spring-runtime.md), [ADR 0003](0003-acp-integration.md) / [architecture.md 구성도](../architecture.md#구성도)
