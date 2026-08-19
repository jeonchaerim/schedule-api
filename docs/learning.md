## 2026-08-03 (Day 1) ##

### 한 것
- spring.io에서 기본 설정 및 의존성 추가해서 프젝 틀 생성
- ScheduleApiApplication 및 컨트롤러 생성해서 톰캣 서버 올라오는지 확인

### 배운 것
- spring-boot-starter-web이 내장 톰캣을 가져오고 Boot가 자동 설정 → 컨트롤러 없어도 서버는 뜸
- Spring Boot는 별개 프레임워크가 아니라 Spring 설정을 자동화한 도구

### 애로사항
- Spring Boot 4.1로 시작 — 3.5가 6월 EOL이라 Initializr에 3.x 선택지 없음. SNAPSHOT 제외하고 정식 최신으로
- 디스크 풀로 Gradle 빌드 실패 (No space left on device). 37GB 정리 후 해결

## 2026-08-19 (N+1 재현) ##

### 케이스 1 — 회원 5명 / 일정 10건
GET /schedules 호출 시 SELECT 8회
- schedule 전체 조회: 1회
- member 조회: 5회 (중복 member_id는 1차 캐시에서 반환)
- category 조회: 2회

1차 캐시가 없다면 1 + (10 × 2) = 21회였을 것

### 케이스 2 — 회원 1,000명 / 일정 2,000건
GET /schedules 호출 시 SELECT 1,003회 / 소요 시간 2,431ms
- schedule 전체 조회: 1회
- member 조회: 1,000회 (FK가 모두 달라 캐시 효과 없음)
- category 조회: 2회

### 배운 것
- N+1의 N은 조회된 행 수가 아니라 연관관계별 중복 제거된 FK 개수
- 같은 트랜잭션 내 동일 ID는 1차 캐시에서 반환되어 쿼리가 생략됨
- 단, 1차 캐시는 트랜잭션 범위이고 FK가 모두 다르면 효과가 없음
  → 케이스 1은 캐시가 13회를 걸러냈지만, 케이스 2는 거의 걸러내지 못함
- 1차 캐시는 id 조회(find, 프록시 초기화)에만 적용되고 JPQL은 항상 DB에 쿼리를 날림
- 캐시는 우연히 도와주는 것이지 해결책이 아님 → Fetch Join 필요