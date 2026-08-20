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
| 지연 로딩 (GET /schedules) | 1,003회 | 2,431ms |
| Fetch Join (GET /schedules/fetch) | 1회 | 900ms |

쿼리 99.9% 감소, 응답 시간 약 63% 단축

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

### 남은 900ms에 대하여
- 쿼리는 99.9% 줄었는데 시간은 63%만 단축된 이유:
  **H2 인메모리는 네트워크·디스크 I/O가 없어 쿼리 1건당 비용이 매우 낮음**
  → 실제 DB에서는 쿼리당 네트워크 왕복이 붙어 격차가 훨씬 클 것으로 예상
- 남은 900ms는 쿼리가 아니라 **2,000건의 엔티티 생성 · 영속성 컨텍스트 등록(스냅샷 포함) · DTO 변환 · JSON 직렬화** 비용
- 이 구간은 Fetch Join으로 줄지 않음 → 페이징, DTO 직접 조회, `@Transactional(readOnly = true)`가 필요한 영역
- 조회 전용이면 Dirty Checking을 위한 스냅샷이 불필요하므로 `readOnly = true`로 그 비용을 제거할 수 있음