---
2026-08-03 (월) — Day 1 프로젝트 세팅
---

### 한 것
- spring.io에서 기본 설정 및 의존성 추가해서 프젝 틀 생성
- ScheduleApiApplication 및 컨트롤러 생성해서 톰캣 서버 올라오는지 확인

### 배운 것
- spring-boot-starter-web이 내장 톰캣을 가져오고 Boot가 자동 설정 → 컨트롤러 없어도 서버는 뜸
- Spring Boot는 별개 프레임워크가 아니라 Spring 설정을 자동화한 도구

### 애로사항
- Spring Boot 4.1로 시작 — 3.5가 6월 EOL이라 Initializr에 3.x 선택지 없음. SNAPSHOT 제외하고 정식 최신으로
- 디스크 풀로 Gradle 빌드 실패 (No space left on device). 37GB 정리 후 해결


---
2026-08-12 (수) — 엔티티 및 연관관계 매핑
---

### 한 것
- 엔티티 3개(Member/Category/Schedule) + BaseTimeEntity 작성
- Schedule → Member, Category 단방향 @ManyToOne(LAZY) 매핑
- Repository 3개 + 더미 데이터 삽입

### 배운 것
- @MappedSuperclass는 상속관계 매핑이 아니라 공통 필드만 물려주는 것. 테이블 안 생김
- @EnableJpaAuditing 없으면 createdAt이 null로 들어감
- FK는 N쪽이 가지고, 그 쪽이 연관관계의 주인
- IDENTITY 전략은 PK를 알아야 영속성 컨텍스트에 넣을 수 있어 persist 시점에 즉시 INSERT (쓰기 지연 X)

### 애로사항
- CategoryRepository 제네릭에 Member를 넣어놔서 컴파일 에러. "should extend Member" 메시지로 원인 특정


---
2026-08-19 (수) — N+1 재현
---

### 케이스 1 — 회원 5명 / 일정 10건
GET /schedules 호출 시 SELECT 8회
- schedule 전체 조회: 1회
- member 조회: 5회 (중복 member_id는 1차 캐시에서 반환)
- category 조회: 2회

1차 캐시가 없었다면 1 + (10 × 2) = 21회였을 것

### 케이스 2 — 회원 1,000명 / 일정 2,000건
GET /schedules 호출 시 SELECT 1,003회 / 소요 시간 2,431ms
- schedule 전체 조회: 1회
- member 조회: 1,000회 (FK가 모두 달라 캐시 효과 거의 없음)
- category 조회: 2회

1차 캐시가 없었다면 1 + (2,000 × 2) = 4,001회였을 것
→ 캐시가 약 3,000회를 걸러냈지만 여전히 1,003회 / 2,431ms

### 배운 것
- N+1의 N은 조회된 행 수가 아니라 연관관계별 중복 제거된 FK 개수
- 같은 트랜잭션 내 동일 ID는 1차 캐시에서 반환되어 쿼리가 생략됨
- 단, 1차 캐시는 트랜잭션 범위이고 FK가 모두 다르면 효과가 없음
- 1차 캐시는 id 조회(find, 프록시 초기화)에만 적용되고 JPQL은 항상 DB에 쿼리를 날림
- 캐시는 배수를 줄일 뿐, 데이터 건수에 비례해 늘어나는 구조는 그대로 → Fetch Join 필요

### 애로사항
- 패키지명 오타(contoller) 수정 후 404 — build 캐시에 옛 클래스가 남아 ./gradlew clean으로 해결
- IntelliJ Ultimate 트라이얼 종료 → Community Edition으로 전환


---
2026-08-20 (목) — Fetch Join 적용
---

### 측정 조건
회원 1,000명 / 일정 2,000건

| | 쿼리 수 | 소요 시간 |
| --- | --- | --- |
| 지연 로딩 | 1,003회 | 2,431ms |
| 지연 로딩 + readOnly | 1,003회 | 676ms |
| Fetch Join + readOnly | 1회 | 82ms |

**최종: 쿼리 1,003회 → 1회, 응답 2,431ms → 82ms (약 30배 단축)**

※ 측정 과정은 아래 "남은 900ms에 대하여" → "readOnly 적용 후 재측정" 순서로 기록

### 실행된 SQL
```sql
select s1_0.*, m1_0.*, c1_0.*
from schedule s1_0
join member m1_0   on m1_0.member_id   = s1_0.member_id
join category c1_0 on c1_0.category_id = s1_0.category_id
```
member와 category는 서로 관계가 없고, 둘 다 schedule의 FK로 각각 조인됨

---

### fetch의 의미
- fetch = "조인만 하지 말고 연관 엔티티도 같이 가져와라"
- SQL 레벨에서의 실체는 **select 절에 연관 테이블 컬럼을 포함시키는 것**

| | 조인 | 연관 엔티티 |
| --- | --- | --- |
| `join s.member` | O | 프록시로 남음 → N+1 그대로 |
| `join fetch s.member` | O | 실제 객체로 채워짐 |

- JPQL은 테이블이 아니라 객체 경로(`s.member`)로 작성하고, 조인 조건(on)은 JPA가 매핑 정보로 생성
- `on` 절의 좌우 순서는 결과·실행계획에 영향 없음 (가독성 문제)

---

### 프록시 객체의 실체
LAZY 조회 시 연관 필드에 들어가는 것:

Member$HibernateProxy extends Member
├── id = 1 ← FK 컬럼에서 받은 값. 이것만 있음
├── target = null ← 진짜 Member 자리. 아직 비어있음
└── getName() 호출 시 target이 null이면 그때 SELECT 후 위임


- 프록시는 **엔티티를 상속한 자식 클래스** → 그래서 기본 생성자가 protected 이상이어야 함
- `getId()`는 프록시가 이미 갖고 있어 쿼리 없이 반환됨. `getName()`부터 초기화 발생
- Fetch Join이면 select 절에 연관 컬럼이 다 들어와 하이버네이트가 진짜 객체를 만들어 꽂음

확인 방법:
- LAZY → `class Member$HibernateProxy$...`
- Fetch Join → `class ...domain.Member`

---

### EAGER는 fetch join이 아니다
- `findById` → 조인해서 한 번에 가져옴 (EAGER가 잘 동작)
- **JPQL/findAll → 쿼리를 작성한 그대로 번역한 뒤, 연관 엔티티를 채우려 추가 쿼리를 날림 → N+1 발생**
- 즉 EAGER는 해결책이 아니라 오히려 예측 불가능한 쿼리를 만듦
- **정답: 전부 LAZY로 두고 필요한 조회에서만 fetch join**

---

### 주의할 점
- 기본은 inner join → **nullable 연관은 `left join fetch` 필요** (아니면 해당 행이 통째로 누락)
- 컬렉션(@OneToMany) fetch join은 행 뻥튀기·페이징 불가 문제가 있음 (본 프로젝트는 단방향이라 해당 없음)
- 프록시 때문에 `equals`를 `getClass()` 비교로 구현하면 깨짐 → `instanceof`로 구현할 것

---

### 중간 측정 — Fetch Join만 적용했을 때 (900ms)
- 쿼리는 99.9% 줄었는데 시간은 63%만 단축된 이유:
  **H2 인메모리는 네트워크·디스크 I/O가 없어 쿼리 1건당 비용이 매우 낮음**
  → 실제 DB에서는 쿼리당 네트워크 왕복이 붙어 격차가 훨씬 클 것으로 예상
- 남은 900ms는 쿼리가 아니라 **2,000건의 엔티티 생성 · 영속성 컨텍스트 등록(스냅샷 포함) · DTO 변환 · JSON 직렬화** 비용
- 이 구간은 Fetch Join으로 줄지 않음 → 페이징, DTO 직접 조회, `@Transactional(readOnly = true)`가 필요한 영역
- 조회 전용이면 Dirty Checking을 위한 스냅샷이 불필요하므로 `readOnly = true`로 그 비용을 제거할 수 있음

### @Transactional(readOnly = true) 적용 후 재측정

| | 쿼리 수 | 소요 시간 |
| --- | --- | --- |
| 지연 로딩 (readOnly 미적용) | 1,003회 | 2,431ms |
| 지연 로딩 + readOnly | 1,003회 | 676ms |
| Fetch Join + readOnly | 1회 | 82ms |

- readOnly만으로 72% 단축 → Dirty Checking용 스냅샷 저장 비용이 2,000건 기준 상당했음
- 스냅샷 비용을 제거하자 Fetch Join의 효과가 더 선명해짐 (676ms → 82ms, 88% 단축)
- 어제는 "H2 인메모리라 쿼리당 비용이 낮아 63%만 단축됐다"고 해석했으나,
  실제로는 스냅샷 비용이 시간을 점유해 쿼리 개선 효과가 가려져 있었음
- 최종: 2,431ms → 82ms (약 30배), 쿼리 1,003회 → 1회

---
2026-08-21 (금) — Dirty Checking / CRUD
---

### 한 것
- Schedule 엔티티에 update() 메서드 추가 (setter 대신)
- ScheduleService에 create / update / delete 추가
- ScheduleCreateRequest, ScheduleUpdateRequest DTO 분리
- POST / PUT / DELETE 엔드포인트 추가

### Dirty Checking 확인
- save() 호출 없이 UPDATE 쿼리 발생 확인
- @LastModifiedDate로 updated_at만 갱신되고 created_at은 유지되는 것을 H2에서 확인
- 등록 시에는 created_at과 updated_at이 동일하게 들어감

### 기본 UPDATE는 전체 필드
변경하지 않은 컬럼(member_id, created_at)까지 SET 절에 포함됨
→ 쿼리 문자열을 재사용해 파싱 비용·실행계획 캐시 이점을 얻기 위한 하이버네이트 기본 동작

@DynamicUpdate 적용 후:
```sql
update schedule
set content=?, end_at=?, start_at=?, title=?, updated_at=?
where schedule_id=?
```
변경된 필드만 SET 절에 포함되는 것 확인

### CRUD 동작 확인
| 메서드 | 엔드포인트 | 확인 내용 |
| --- | --- | --- |
| POST | /schedules | 생성된 id(2001) 반환, save() 시점에 즉시 INSERT |
| PUT | /schedules/{id} | save() 없이 UPDATE, updated_at 갱신 |
| DELETE | /schedules/{id} | findById 후 delete, H2에서 삭제 확인 |

- IDENTITY 전략이라 save() 순간 바로 INSERT (쓰기 지연 미적용)
- delete도 SELECT가 먼저 나감 — 영속 상태로 만들어야 삭제 대상으로 관리되기 때문

### 배운 것
- @Transactional이 없으면 commit이 일어나지 않아 flush·스냅샷 비교 시점 자체가 없음 → UPDATE 미발생
- 조회 후 메서드를 벗어나면 준영속 상태가 되어 변경 감지 대상에서 제외됨
- setter 대신 의미 단위 메서드를 여는 이유: 변경 지점 추적 가능, 검증 로직 삽입 가능, 도메인 의도가 드러남
- JPA는 FK 값(Long)이 아니라 연관 객체를 요구하므로, categoryId를 받아 조회 후 객체로 변환해 전달
  (getReferenceById를 쓰면 프록시만 만들어 SELECT를 생략할 수 있으나 존재 검증이 불가)

### 남은 것
- 예외 처리 미적용 — 없는 id 조회 시 IllegalArgumentException이 500으로 나감
  (@RestControllerAdvice로 400 변환은 여유 시 진행)


---
2026-08-24 (월) — Redis 캐시 적용
---

### 한 것
- Redis 설치 및 Spring Cache 연동
- 조회 API에 @Cacheable 적용, 캐시 HIT/MISS 확인

### 측정 결과
| | 쿼리 수 | 소요 시간 |
| --- | --- | --- |
| 1번째 호출 (캐시 MISS) | 1회 | 1,676ms |
| 2번째 호출 (캐시 HIT) | **0회** | 175ms |

캐시 HIT 시 메서드 자체가 실행되지 않아 SQL이 발생하지 않음

### 캐시 동작 원리
- @Cacheable이 붙으면 스프링이 프록시로 메서드를 감쌈 (@Transactional과 동일한 패턴)
- 호출 시 Redis에 `schedules::all` 키 존재 여부 확인
  - 있으면(HIT) 메서드 실행 없이 캐시 값 반환
  - 없으면(MISS) 메서드 실행 → 결과를 Redis에 저장 → 반환
- 캐시 이름(value)과 키(key)를 `::`로 연결해 실제 Redis 키가 생성됨
- 1차 캐시와의 차이: 1차 캐시는 트랜잭션 범위·JVM 내부, Redis는 애플리케이션 전체·서버 간 공유

### 애로사항
- **brew install redis 실패** — macOS 12는 Homebrew 지원 대상에서 제외되어 bottle이 없고,
  의존성 20개(llvm, rust, python)를 전부 소스 컴파일하려 함
  → Redis 본체는 C로 외부 의존성이 거의 없다는 점을 확인하고 소스 직접 빌드로 우회
  → 부가 모듈(redisbloom/search/json/timeseries)은 빌드 실패했으나 캐시 용도에 불필요하여 무시

- **CacheManager 빈 미생성** — @EnableCaching과 spring-boot-starter-data-redis가 모두 있는데도
  "No qualifying bean of type CacheManager" 발생
  → debug: true로 CONDITIONS EVALUATION REPORT 확인 결과 CacheAutoConfiguration이
  Positive/Negative 어느 쪽에도 없음 = 자동설정 클래스 자체가 존재하지 않음
  → Spring Boot 4.1에서 캐시 자동설정이 분리된 것으로 판단, RedisCacheManager를 직접 빈 등록

- **역직렬화 실패** — JSON 직렬화 시도 중 Cannot cast LinkedHashMap to ScheduleResponse
  → JSON에는 원래 타입 정보가 없어 복원이 불가. activateDefaultTyping으로 타입을 함께 저장해야 함
  → Jackson 3(tools.jackson)로 전환되며 클래스·enum 값이 달라져 설정에 시간 소요
  → 캐시 동작 검증이 목적이므로 JDK 기본 직렬화(Serializable)로 전환하여 해결

- 직렬화 방식 변경 후 기존 캐시와 형식이 불일치해 예외 발생
  → flushall로 제거. 실무에서 직렬화 방식 변경 시 배포와 함께 캐시 무효화가 필요함을 확인


---
2026-08-25 (화) — 캐시 무효화 / 코드 보완 / Swagger
---

### 한 것
- create / update / delete에 @CacheEvict 적용
- join fetch → left join fetch 수정 및 null 방어 추가
- Swagger(springdoc-openapi) 적용
- application.yml 진단용 설정 정리

### 캐시 무효화 확인 결과
| 동작 | Redis 키 | SQL |
| --- | --- | --- |
| 1차 조회 | schedules::all 생성 | 발생 |
| 2차 조회 | 유지 | 미발생 (HIT) |
| 수정(PUT) | **삭제됨** | UPDATE 발생 |
| 3차 조회 | 재생성 | 다시 발생 (MISS) |

- TTL 확인: `redis-cli ttl "schedules::all"` → 600초에서 카운트다운

### 배운 것
- 캐시는 DB와 별개 저장소라 데이터 변경 시 자동 반영되지 않음
  → TTL만으로는 만료 전까지 낡은 데이터가 응답됨
- @CacheEvict는 메서드 정상 종료 후 실행됨 (예외 시 캐시 유지 — 롤백된 DB와 일관)
- TTL은 보조 안전장치, @CacheEvict가 주 방어선
- Dirty Checking은 수정에만 해당. 등록은 save(), 삭제는 delete()를 명시적으로 호출해야 하고
  수정만 유일하게 메서드 호출 없이 커밋 시점에 UPDATE가 발생

### 코드 보완 — left join fetch
- category는 nullable로 설계했는데 join fetch(inner join)를 사용해
  카테고리가 없는 일정이 조회 결과에서 누락되는 문제 확인
  → categoryId 없이 등록한 일정이 목록에 나타나지 않음 (저장은 되었으나 조회 불가)
- left join fetch로 변경하여 해결
- 이에 따라 ScheduleResponse.from()에서 category null 방어 추가 (NullPointerException 방지)
- 스키마(null 허용) · 쿼리(left join) · DTO(null 방어)가 한 세트로 맞아야 함

### Swagger 적용
- springdoc-openapi로 API 문서 자동 생성 (/swagger-ui/index.html)
- @Tag / @Operation으로 엔드포인트 설명, @Schema로 요청 DTO 예시값 지정
- /schedules(지연 로딩)와 /schedules/fetch(Fetch Join + 캐시)를 나란히 노출해
  N+1 비교 목적이 문서에서 드러나도록 구성
- REST 규약: 자원은 URL(명사), 동작은 HTTP 메서드로 표현
  POST /schedules(생성) · PUT /schedules/{id}(수정) · DELETE /schedules/{id}(삭제)
  ※ /schedules/fetch는 성능 비교를 위해 의도적으로 분리한 엔드포인트

### 애로사항
- 로컬 Redis를 &로 백그라운드 실행해 터미널 종료 시 함께 종료됨
  → Connection refused 발생. --daemonize yes로 전환
- 캐시 서버 장애 시 현재는 500 응답 — 실무에서는 DB 폴백 처리가 필요한 지점
- @Schema 미지정 시 Long 필드에 9007199254740991(JS 안전 정수 최댓값)이 예시로 채워져
  그대로 실행하면 "카테고리를 찾을 수 없습니다" 발생 → example 지정으로 해결

### 남은 것
- 예외 처리 미적용 — IllegalArgumentException이 500으로 나감 (8/26 예정)
- 기간 검증 없음 — startAt > endAt도 저장됨 (8/26 예정)

### 예외 처리 및 검증
- @RestControllerAdvice로 전역 예외 처리기 추가
  → IllegalArgumentException을 400 + {"message": "..."} 로 변환
  → 기존 500 + 스택트레이스 노출 문제 해결 (내부 구조·라이브러리 버전 노출 방지)
- Schedule 엔티티 생성자·update()에 기간 검증(validatePeriod) 추가
  → startAt > endAt 인 경우 저장 자체를 차단

- 역할 분리: 엔티티는 "잘못된 값"만 판단하고 던지고(throw),
  HTTP 응답 코드 변환은 GlobalExceptionHandler가 담당
  → 엔티티는 웹 계층을 몰라도 되고, 배치 등 다른 진입점에서도 동일하게 동작
- setter 대신 update() 메서드를 둔 실질적 이유:
  setter는 필드를 하나씩 바꿔 중간에 startAt > endAt 인 상태가 생길 수 있고,
  검증 시점에 비교할 다른 필드가 아직 옛 값이라 순서에 따라 결과가 달라짐
  → 변경할 값을 한 번에 받아야 새 값끼리 검증 가능


---
2026-09-09 (수) — Service 단위 테스트 작성
---

### 한 것
- JUnit5 + Mockito 의존성 확인 (spring-boot-starter-*-test에 이미 포함되어 있어 추가 불필요)
- ScheduleService 단위 테스트 7개 작성 (Repository 3개는 @Mock, @InjectMocks로 Service 조립)
- README에 테스트 섹션 추가, docs/troubleshooting.md에 8번 항목 추가

### 배운 것
- @Mock/@InjectMocks는 상속이 아니라 조립: Mockito가 Repository 인터페이스의 가짜 구현체를
  만들고, @InjectMocks가 그걸 ScheduleService 생성자에 끼워 넣어 진짜 객체를 만듦
- given().willReturn()은 when().thenReturn()과 같은 문법 (BDD 스타일 별칭).
  "이 입력으로 호출되면 이걸 리턴해라"를 미리 등록해두는 것일 뿐, DB는 전혀 안 건드림
- verify(mock, never()).method()로 "호출되지 않았어야 한다"까지 검증 가능
  (반대로 verify(mock).method()는 "1번 호출됐어야 한다")
- 단위 테스트에서 가짜로 바뀌는 건 Repository뿐, Schedule.builder()·검증 로직·
  ScheduleService의 분기문은 전부 실제 코드 그대로 실행됨 — 이게 "단위"의 의미
- id는 @GeneratedValue라 세터가 없어(DB가 INSERT 시점에 채움), 테스트에서는
  ReflectionTestUtils.setField로 강제 주입해야 함

### 애로사항
- update() 테스트에서 "categoryId 없으면 카테고리가 유지될 것"으로 가정하고 짰다가 실패
  → Schedule.update()가 category를 조건 없이 덮어써서, categoryId 없이 수정하면
    실제로는 카테고리가 null로 지워지는 동작이었음
  → 수정 API가 PUT이라 리소스 전체 교체가 표준 시맨틱임을 확인, 버그가 아니라
    의도된 동작으로 판단하고 주석 추가

### 이어서 한 것 — Repository/Controller 테스트, Docker
- ScheduleRepository 테스트 6개 작성 (@DataJpaTest, 내장 H2) — CRUD,
  update() flush 후 반영 확인, findAllWithMemberAndCategory의 fetch join·
  null 카테고리 케이스
- ScheduleController 테스트 10개 작성 (@WebMvcTest + MockMvc, Service는
  @MockitoBean) — 5개 엔드포인트 × 정상/예외(대안) 각 1개
- Dockerfile(멀티스테이지) + docker-compose.yml(app/postgres/redis) +
  .env.example 작성, docker compose up으로 실제 기동·헬스체크까지 확인

### 배운 것 (2)
- Spring Boot 4.1에서 @MockBean이 제거됨 — @MockitoBean으로 대체해야 함
  (자료보다 실제 jar 안의 클래스를 까보는 게 더 정확함)
- @SpringBootApplication 클래스에 직접 붙인 @EnableCaching/@EnableJpaAuditing은
  슬라이스 테스트(@WebMvcTest/@DataJpaTest)에도 그대로 적용됨
  → 컴포넌트 스캔은 걷어내도 메인 클래스 자체의 어노테이션은 안 걷어내서
  → 각자의 @Configuration 클래스로 옮겨서 슬라이스 테스트와 분리
- ddl-auto: create + 영속 볼륨(Postgres)이면 재기동할 때마다 스키마·데이터를
  통째로 새로 만들어서 DataInitializer 중복 email 충돌은 안 나지만, 대신
  "볼륨으로 데이터 유지"라는 원래 목적 자체가 깨짐 — 다음날 코드리뷰에서
  발견 (9/10 기록 참고)
- docker compose는 폴더명으로 프로젝트 이름(=이미지 태그 일부)을 자동 생성함
  → 한글 폴더명이면 깨진 이름이 만들어져 이미지를 못 찾음 → compose.yml에
    name을 명시해서 폴더명 의존성을 없앰
- eclipse-temurin:17-jre-alpine은 patch 버전에 따라 arm64(Apple Silicon)
  매니페스트가 없을 수 있음 → jre-jammy로 교체해 해결

### 남은 것
- 없음 (Service/Repository/Controller 3계층 테스트 + Docker 전체 스택 완료)


---
2026-09-10 (목) — 코드리뷰 반영 + GitHub Actions CI
---

### 한 것
- `/code-review`로 어제 변경분(테스트+Docker) diff 리뷰 → 4개 발견, 3개 수정
  1. Postgres 볼륨 마운트해도 ddl-auto: create 때문에 재기동마다 데이터 사라지던 것
     → local은 create, docker는 update로 분리 + DataInitializer에 재시딩 가드 추가
  2. docker 프로필에서도 SQL 바인딩 값(이메일 등)이 TRACE 로그로 찍히던 것
     → local 전용으로만 로깅 레벨 분리
  3. GET /schedules, /schedules/fetch에 예외 응답 테스트가 없던 것
     → 2개 추가 (Controller 테스트 10 → 12, 전체 26개)
  4. (미수정) Controller 테스트 4곳에 JSON 요청 본문 중복 — 리포트만 하고 안 고침
- 실제 docker-compose 재빌드 → 일정 하나 API로 등록 → 컨테이너 재시작 →
  데이터 그대로 남는지, 로그에 이메일 안 찍히는지 직접 검증
- GitHub Actions CI 구성 (.github/workflows/ci.yml)
  - main push/PR 시 JDK 17 + Gradle 빌드·테스트 자동 실행
  - actions/checkout@v7, setup-java@v6, upload-artifact@v7 — GitHub API로
    실제 최신 stable 버전 직접 확인해서 사용
  - setup-java의 cache: gradle로 의존성 캐싱, 테스트 리포트는 항상 아티팩트 업로드
- README에 CI 배지 추가, push해서 Actions 탭에서 실제 그린(성공) 확인함 (CI #1)

### 배운 것
- 워크플로우 > job > step 계층 구조. job은 "가상 컴퓨터 1대한테 던지는 작업
  뭉치"이고, 기본적으로 같은 워크플로우 안 job들은 병렬 실행됨 — 순서를
  강제하려면 `needs: build` 같은 걸 명시해야 함
- `uses:` = 남이 만든 action(함수) 사용, `with:` = 그 함수에 넘기는 인자
  (파라미터). distribution/java-version/cache는 각각 "어느 회사 JDK인지 /
  몇 버전인지 / 어떤 빌드도구 캐시를 쓸지"
- CI(빌드+테스트 자동화)와 CD(배포 자동화)는 다른 개념이지만, 같은 워크플로우
  파일 안에 job만 추가하면 됨(`deploy: needs: build`) — 파일 이름이 ci.yml
  이어도 job 추가에 제약 없음
- 이 "job 순서 의존성(needs)" 개념이 Gradle 자체의 task graph
  (`build`가 `test`에, `test`가 `compileTestJava`에 의존하는 것)와 똑같은
  구조라는 걸 뒤늦게 연결함
- CI 러너는 매번 깨끗한 새 컴퓨터라서, 로컬에 떠 있는 Redis/Postgres에
  의존하면 안 됨 → 실제로 로컬 docker-compose를 잠깐 내려서(포트 막힌 상태)
  `./gradlew build`가 통과하는지 직접 검증하고 나서야 CI 설정에 확신을 가짐

### 애로사항
- 없음 (원인 추적형 문제보다는 코드리뷰로 미리 찾아서 수정한 케이스들)


---
2026-09-11 (금) — CD(GHCR 배포) 추가
---

### 한 것
- ci.yml에 deploy job 추가 — build job 성공 후(push 이벤트에서만) Docker
  이미지를 빌드해 ghcr.io(GitHub Container Registry)에 push
  - latest, 커밋 SHA 두 태그로 업로드
  - docker/login-action, docker/build-push-action 사용, 인증은
    GITHUB_TOKEN(매 실행마다 자동 발급)으로 처리
- `/code-review`로 방금 작성한 deploy job 자체를 리뷰 → 3개 발견, 전부 수정
  1. `if: github.event_name == 'push'`가 `needs: build`의 기본 성공 게이트를
     대체해버려서, 테스트가 실패해도 deploy가 실행될 수 있었음
     → `if: success() && github.event_name == 'push'`로 명시
  2. Docker 레이어 캐시가 없어 매번 전체 재빌드 → `cache-from`/`cache-to:
     type=gha` 추가
  3. 이미지 태그가 `github.repository`를 그대로 써서 대문자 계정이면 깨질 수
     있었음 → 소문자 변환 step 추가
- 실제로 push해서 `Packages`에 이미지가 정상 태그로 올라간 것까지 확인
- 로컬에서 `docker pull` + `docker run`으로 직접 실행 시도 →
  `no matching manifest for linux/arm64/v8` 발견
  → 원인: GitHub Actions 러너(amd64)에서만 빌드해서 이미지에 arm64용
    알맹이가 없었음. 8/20~21에 겪은 `eclipse-temurin:17-jre-alpine` arm64
    문제와 같은 카테고리(멀티 아키텍처)의 버그
  → `docker/setup-qemu-action` 추가 + `build-push-action`에
    `platforms: linux/amd64,linux/arm64` 명시해서 해결

### 배운 것
- workflow > job > step 계층. job은 기본 병렬 실행, `needs`는 "순서(대기)"만
  보장하고 "성공 여부 확인"은 별개 — 커스텀 `if`를 쓰면 그 성공 게이트가
  조용히 사라질 수 있음 (빌드가 "끝난 것"과 "성공한 것"은 다른 개념)
- `uses: 조직/저장소@버전`은 build.gradle의 `그룹:라이브러리:버전`과 같은
  구조 — 재사용 가능한 코드를 정확한 버전으로 가져다 쓰는 것
- `with:`는 그 action(함수)에 넘기는 인자(파라미터)
- `push: true`는 "빌드만 하지 말고 레지스트리에 올려라"는 옵션. `push: false`로
  "빌드만" 하는 것과 Gradle의 `build`(자바 테스트)는 서로 다른 것을 검증함
  — 우리 Dockerfile은 `-x test`로 이미지 빌드 시 테스트를 스킵하기 때문에,
  `deploy`가 `build`의 성공 여부를 안 보면 테스트 깨진 이미지도 그냥
  빌드·푸시될 수 있었던 것 (그래서 success() 누락이 실제로 위험했음)
- ghcr.io(GitHub Container Registry) = GitHub이 운영하는 Docker 이미지
  저장소. 코드는 GitHub, 빌드 결과물(이미지)은 ghcr.io
- GitHub Actions 러너는 기본적으로 자기 아키텍처(amd64)로만 빌드함 —
  여러 아키텍처용 이미지를 만들려면 QEMU 에뮬레이션 + `platforms` 옵션이
  명시적으로 필요함

### 애로사항
- 로컬(Apple Silicon)에서 방금 올린 이미지를 pull해서 돌려보니
  arm64 매니페스트가 없어서 실행 자체가 안 됨 → 원인 파악 후 멀티
  아키텍처 빌드로 수정 (위 "한 것" 참고)
- 로컬에서 GHCR 이미지 pull + run 테스트 중, 8080/8081이 이미 다른
  컨테이너(docker-compose의 schedule-app)가 쓰고 있어서 컨테이너 이름
  충돌·포트 충돌을 번갈아 겪음 → `docker ps -a`로 실제 상태 확인하며 정리

### 배운 것 (3) — 포트 매핑 원리
- 컨테이너는 호스트(내 맥북)와 완전히 격리된 별개의 네트워크 세계임.
  컨테이너 안의 "8080"과 호스트의 "8080"은 번호만 같을 뿐 서로 다른
  세계의 서로 다른 문 — 그래서 `-p 호스트포트:컨테이너포트`처럼 항상
  숫자 두 개(콜론으로 연결)를 지정해야 함. 하나만 쓰면 Docker가
  "어느 세계의 몇 번인지" 알 수 없음
- 오른쪽(컨테이너 포트)은 앱이 내부적으로 정한 고정값(스프링부트 기본
  8080)이라 못 바꾸지만, 왼쪽(호스트 포트)은 그 순간 호스트에서 비어있는
  숫자면 뭐든 써도 됨 — 반드시 같은 숫자여야 하는 규칙은 없음. 실무에서
  다르게 쓰는 이유도 대부분 "보안"이 아니라 "이미 그 번호를 딴 게 쓰고
  있어서(충돌 방지)"
- `docker run -d`는 컨테이너가 실제로 떠 있어도 터미널에 `ERRO context
  canceled`류 메시지가 뜰 수 있음 — 컨테이너 생성 성공 여부는 그 로그가
  아니라 `docker ps -a`로 직접 확인해야 정확함
- 같은 Dockerfile/소스로 만들어도 "어디서 빌드했는지"(로컬 vs GitHub
  Actions)에 따라 이미지 이름이 달라짐(`schedule-api_app` vs
  `ghcr.io/.../schedule-api`) — 하지만 포트만 열어주면 어느 쪽이 응답하든
  완전히 동일하게 동작함 (같은 앱의 서로 다른 복사본일 뿐)
- 컨테이너를 Redis/Postgres 없이 단독으로 띄우면 `/actuator/health`가
  DOWN으로 나오는 게 정상 — Redis 연결 실패 스택트레이스가 이유였고,
  API 자체(`/schedules`)는 별개로 정상 응답함


---
2026-09-12 (토) — Redisson 분산락으로 동시성 제어
---

### 한 것
- ScheduleService.create()에 Redisson 분산락 적용 — 같은 회원이 겹치는
  시간대에 일정을 동시 등록하면 하나만 저장되게 함
  - 락 키는 "회원 단위"(`schedule-lock:member:{memberId}`)로 단순화,
    실제 겹침 판정은 락 안에서 DB 쿼리(existsOverlapping)로 검증
  - tryLock(대기 3초, 점유 5초), 실패 시 LockAcquisitionException(409),
    겹침 발견 시 ScheduleConflictException(409)
- 락 적용 전/후 동시성 재현 테스트 2개 작성 (스레드 10개로 동시 등록)
  - concurrency 태그로 묶어 기본 ./gradlew test에서는 제외, 별도
    concurrencyTest Gradle 태스크로 수동 실행(Redis 필요)
- ScheduleServiceTest의 create 관련 테스트에 RedissonClient/RLock mock
  추가, unlock() 호출 여부까지 검증하는 테스트 보강
- README/troubleshooting.md(17번) 동기화

### 배운 것
- Redis 락은 정확히 같은 문자열 키끼리만 서로 막아줌 — "겹치는 시간대"
  처럼 시작/끝 값이 달라도 겹칠 수 있는 "구간" 조건은 락 키에 그대로
  못 담음(두 요청이 다른 키를 가져서 락이 무의미해짐). 그래서 락은
  "회원 단위"로 걸고, 정확한 겹침 판정은 락 안에서 DB로 따로 검증하는
  방식으로 역할을 나눠야 함
- Redisson(RedissonClient)은 캐시용 Lettuce와 달리 빈을 만드는 시점에
  즉시 Redis에 연결을 시도함 → Redis 없이 테스트 돌리면 컨텍스트 전체가
  못 뜸(RedisConnectionException) → 빈 정의와 주입부 양쪽에 @Lazy를
  붙여서 실제 사용 시점까지 연결을 미룸
- @RequiredArgsConstructor는 필드의 @Lazy를 생성자 파라미터까지 기본
  복사해주지 않음 — lombok.config에 lombok.copyableAnnotations 설정을
  추가해야 실제로 지연 주입이 됨
- @Transactional 메서드 안에서 분산락을 쓸 때는 "언제 unlock하느냐"가
  핵심 — finally에서 바로 unlock하면, 실제 커밋(메서드가 끝난 뒤 프록시
  바깥에서 일어남)보다 락 해제가 먼저 일어나서, 그 틈에 다음 스레드가
  아직 안 보이는(커밋 전) 데이터를 못 보고 똑같이 통과해버릴 수 있음
  → TransactionSynchronizationManager로 커밋 완료 후에만 unlock하도록
  등록해야 안전함. "락을 걸었다"와 "그 락이 트랜잭션과 올바른 순서로
  풀린다"는 별개의 문제
- 유닛 테스트에서 mock RLock의 isHeldByCurrentThread()는 기본값 false라,
  stub 안 하면 unlock() 관련 로직이 하나도 검증되지 않고 그냥 통과함
  (코드리뷰로 발견 — 세 테스트에 isHeldByCurrentThread() stub과
  verify(rLock).unlock() 추가해서 보강)

### 애로사항
- 락 없이 재현 테스트 → 락 적용 후 검증까지는 순조로웠는데, Redis 없이
  전체 테스트를 돌려보니(CI 조건 재현) RedissonClient 빈 생성부터 실패함
  → @Lazy로 해결(위 "배운 것" 참고)
- 실제 락 적용 후 테스트가 락 없을 때보다 훨씬 느려짐(0.3초 → 7초,
  스레드 10개 순차 처리) — 버그가 아니라 "직렬화의 대가"였음, 트러블슈팅
  문서에 트레이드오프로 기록