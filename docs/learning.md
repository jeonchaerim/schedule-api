## 2026-08-03 (Day 1)

### 한 것
- spring.io에서 기본 설정 및 의존성 추가해서 프젝 틀 생성
- ScheduleApiApplication 및 컨트롤러 생성해서 톰캣 서버 올라오는지 확인

### 배운 것
- spring-boot-starter-web이 내장 톰캣을 가져오고 Boot가 자동 설정 → 컨트롤러 없어도 서버는 뜸
- Spring Boot는 별개 프레임워크가 아니라 Spring 설정을 자동화한 도구

### 애로사항
- Spring Boot 4.1로 시작 — 3.5가 6월 EOL이라 Initializr에 3.x 선택지 없음. SNAPSHOT 제외하고 정식 최신으로
- 디스크 풀로 Gradle 빌드 실패 (No space left on device). 37GB 정리 후 해결