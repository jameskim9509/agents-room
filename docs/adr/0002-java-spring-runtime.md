# ADR 0002: Java/Spring 로컬 런타임

## 상태

Accepted — 결정 1–4는 Jira가 전제한 구조다. SCRUM-23 "Spring 런타임이 codex-acp 프로세스를 시작하고 Java ACP Client가 stdio로 연결한다", SCRUM-66 "동일한 Java ACP Client 인터페이스로 제어된다". 결정 5–9는 2026-10-23에 팀이 합의했다.

셸이 런타임을 띄우고 끝내는 방식(프로세스 수 포함), 셸이 사라질 때의 정리, 127.0.0.1 바인딩과 전송 방식은 [ADR 0001](0001-desktop-framework.md)이 다룬다.

## 날짜

2026-10-21

## 맥락

- SCRUM-8: 렌더러(Renderer)와 로컬 런타임(Local runtime)을 분리한다.
- SCRUM-9: 런타임이 에이전트 프로세스의 실행·중지·재시작과 ACP 연결을 관리하고, 여러 세션을 동시에 다룬다.
- SCRUM-22: 프로세스 시작과 종료는 렌더러가 아니라 로컬 런타임이 맡는다.
- SCRUM-36: 대상은 Node.js나 Electron 개발 환경이 없는 Windows 사용자다.
- 현재 코드의 런타임 역할은 Node/Fastify `server/`가 맡는다.
- Java와 Spring을 고른 이유는 Jira에 기록되어 있지 않다. 이 ADR은 이유를 추측하지 않고, Jira가 전제한 구조와 그 결과만 기록한다.
- Spring Boot 4.1.2(2026-10-23 기준 최신)는 Java 17~27과 호환하고, Gradle 8.14 이상과 9.x, Maven 3.6.3 이상을 지원한다 ([시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)).

## 결정

1. 로컬 런타임은 GUI와 분리된 Java/Spring 프로세스다 (SCRUM-8, 23).
2. 에이전트 프로세스를 시작하고 중지하는 것은 렌더러가 아니라 런타임이다 (SCRUM-22, 23).
3. 런타임 안의 Java ACP Client가 stdio로 ACP를 처리한다 (SCRUM-23, 66. 세부는 [ADR 0003](0003-acp-integration.md)).
4. 사용자는 개발 환경을 설치하지 않으므로 런타임을 앱에 함께 싣는다. Jira에 직접 적힌 것이 아니라 SCRUM-36에서 끌어낸 결론이다.
5. JDK는 25 LTS다. 시작 시간을 줄이는 [AOT cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)(Java 25+)를 쓸 수 있고, 21보다 지원 기간이 길다.
6. Spring Boot 4.x를 쓴다. JSON은 Jackson 3이고, SDK의 `acp-json-jackson3`와 맞춘다 ([ADR 0003](0003-acp-integration.md) 결정 2). `acp-spring-boot-starter`도 Spring Boot 4.1과 Jackson 3를 대상으로 한다 (2026-10-20 기준).
7. Renderer gateway는 Spring WebFlux로 만든다. ADR 0003이 고른 SDK의 `AcpAsyncClient`가 Reactor 기반이므로, ACP에서 받은 이벤트 스트림을 같은 방식으로 WebSocket에 보낸다.
8. 빌드 도구는 Gradle(Kotlin DSL, wrapper 포함)이다. 런타임 코드는 저장소 루트의 `runtime/`에 두고, `core/asyncapi.yaml`에서 생성한 Java 바인딩([ADR 0005](0005-agent-event-model.md))을 쓴다.
9. 런타임은 jlink로 만든 JRE와 함께 싣는다. jlink는 런타임이 쓰는 Java 모듈만 골라 작은 JRE 폴더를 만들고, 셸이 이 JRE로 런타임 jar를 실행한다. 일반 JVM이므로 Spring, ACP SDK, Jackson이 그대로 동작한다.

## 검토한 대안

- Node/TypeScript 런타임: 현재 `server/`를 다시 쓰므로 코드를 재사용하고 언어가 하나이며, TS ACP SDK `@agentclientprotocol/sdk`도 있다. 그러나 Jira가 Java ACP Client를 전제한다. 채택하지 않는다.
- Tauri core 안의 Rust: 별도 런타임 프로세스와 JRE 동봉이 필요 없지만, Jira가 전제한 Java ACP Client와 맞지 않는다. ADR 0001의 Tauri 선택에 묶이고 세 번째 언어가 생긴다. 채택하지 않는다.
- JDK 21: SDK와 Spring Boot 4는 쓸 수 있지만 AOT cache를 쓸 수 없다. 채택하지 않는다.
- Spring Boot 3.x: SDK의 `acp-json-jackson2`로 쓸 수 있지만, starter와 Jackson 3가 대상으로 하는 버전이 아니다. 채택하지 않는다.
- Spring MVC: 동기 코드라 읽기 쉽지만, Reactor 기반 SDK와 섞으면 스레드 모델이 둘이 된다. 채택하지 않는다.
- Maven: 선언형이라 단순하지만, jlink와 OS별 패키징 같은 빌드 단계를 붙이기가 Gradle보다 번거롭다. 채택하지 않는다.
- GraalVM native-image: Java 코드를 미리 기계어로 컴파일해 실행 파일 하나로 만든다. 시작이 빠르고 메모리를 적게 쓰지만, closed-world라 Spring, ACP SDK, Jackson의 reflection hint가 빠지면 실행 중에 깨지고, 빌드가 오래 걸린다 ([Spring native image](https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html)). MVP에는 채택하지 않고, 시작 시간이 문제가 되면 다시 검토한다.

## 결과

- 좋은 점:
  - 공식 ACP Java SDK가 있다 (Java 17+, [java-sdk](https://github.com/agentclientprotocol/java-sdk)).
  - Spring의 설정, DI, 로깅 생태계를 쓸 수 있다.
  - 계약은 `core/asyncapi.yaml`에서 Modelina의 Java 생성기로 공유할 수 있다. 저장소에 설치된 Modelina 5.10.1에 java generator가 있다 (세부는 [ADR 0005](0005-agent-event-model.md)).
- 비용과 위험:
  - 런타임을 함께 실으므로 크기와 시작 시간이 늘어난다. jlink 런타임은 50MB 미만으로 줄일 수 있다는 [추정](https://snyk.io/blog/jlink-create-docker-images-spring-boot-java/)이 있다 (제3자 추정). 시작 시간은 AOT cache(결정 5)나 CDS로 줄인다.
  - 언어가 TS와 Java 둘이다. WebFlux(결정 7)는 reactive 코드라 디버깅과 학습 비용이 든다.
  - 업스트림 `server/` 코드는 재사용하지 않고 개념만 옮긴다 ([upstream.md의 상속 범위](../upstream.md#상속-범위)).
  - stdout을 제어 경로로 쓴다면 Spring 배너와 로그를 stdout에서 분리해야 한다.
  - jlink JRE는 OS마다 따로 만들어야 하므로 OS별 CI 빌드가 필요하다 (MVP는 Windows, macOS·Linux는 SCRUM-64).
- 후속 작업: `runtime/` 골격(빌드, jlink JRE 생성, 셸과의 시작·종료 연동)을 만든다.

## 관련 이슈

SCRUM-8, 9, 22, 23, 36, 64, 66 / [ADR 0001](0001-desktop-framework.md), [ADR 0003](0003-acp-integration.md), [ADR 0005](0005-agent-event-model.md) / [architecture.md 런타임 구성요소](../architecture.md#런타임-구성요소), [upstream.md 상속 범위](../upstream.md#상속-범위)
