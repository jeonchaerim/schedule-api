# 트러블슈팅

`schedule-api` 개발 과정에서 마주친 문제와 해결 과정을 정리한 문서입니다.
성능 측정 결과 요약은 [README](../README.md#성능-개선-결과)를 참고하세요.

---

### 1. N+1 문제 — 조회 1건에 쿼리 1,003회

**문제**

일정 목록 조회 시 SQL이 1,003회 실행되었습니다.

```
select ... from schedule s1_0                      ← 목록 조회 1회
select ... from member m1_0 where m1_0.member_id=?  ← binding [1]
select ... from category c1_0 where c1_0.category_id=? ← binding [1]
select ... from category c1_0 where c1_0.category_id=? ← binding [2]
select ... from member m1_0 where m1_0.member_id=?  ← binding [2]
...
```

**원인**

`@ManyToOne(fetch = LAZY)`로 매핑되어 있어 목록 조회 시점에는 연관 엔티티가
프록시로만 채워지고, DTO 변환 과정에서 `getMember().getName()`을 호출하는
순간마다 프록시가 초기화되며 개별 SELECT가 발생했습니다.

**N의 정체**

일정이 2,000건인데 쿼리는 4,001회가 아닌 1,003회였습니다.
같은 트랜잭션 안에서 동일한 ID는 영속성 컨텍스트 1차 캐시에서 반환되기 때문에,
실제 쿼리 수는 **행 수가 아니라 연관관계별 중복 제거된 FK 개수**로 결정됩니다.

```
1 (목록 조회)
+ 1,000 (member — FK가 모두 달라 캐시 효과 없음)
+ 2     (category — 2개뿐이라 대부분 캐시에서 반환)
= 1,003회
```

1차 캐시가 없었다면 `1 + (2,000 × 2) = 4,001회`였을 것입니다.
즉 캐시는 약 3,000회를 걸러냈지만, **데이터 건수에 비례해 쿼리가 늘어나는
구조 자체는 그대로**입니다. FK가 모두 다른 데이터라면 캐시 효과는 사라집니다.

**해결 — Fetch Join**

```java
@Query("select s from Schedule s " +
       "join fetch s.member " +
       "left join fetch s.category")
List<Schedule> findAllWithMemberAndCategory();
```

실행되는 SQL:

```sql
select s1_0.schedule_id, c1_0.category_id, c1_0.color, ...,
       m1_0.member_id, m1_0.email, m1_0.name, ...
from schedule s1_0
join member m1_0 on m1_0.member_id = s1_0.member_id
left join category c1_0 on c1_0.category_id = s1_0.category_id
```

`fetch`의 실체는 **select 절에 연관 테이블 컬럼을 포함시키는 것**입니다.
컬럼이 함께 조회되므로 하이버네이트가 프록시 대신 실제 객체를 채워 넣고,
이후 어떤 필드에 접근해도 추가 쿼리가 발생하지 않습니다.
`join`만 쓰면 조인은 수행되지만 연관 엔티티는 프록시로 남아 N+1이 그대로 발생합니다.

**결과: 쿼리 1,003회 → 1회**

---

### 2. EAGER는 해결책이 아니다

N+1을 해결하며 `FetchType.EAGER`도 검토했으나 적용하지 않았습니다.

- `findById`에서는 조인으로 한 번에 조회되지만
- **JPQL에서는 작성한 쿼리를 그대로 SQL로 번역한 뒤, 연관 엔티티를 채우기 위해
  추가 쿼리를 발생시켜 오히려 N+1을 유발**합니다
- 연관 엔티티를 사용하지 않는 조회에서도 불필요한 조인이 따라옵니다

따라서 **기본은 LAZY로 두고, 필요한 조회에서만 Fetch Join을 명시**하는 방식을 택했습니다.

---

### 3. 조회 전용 트랜잭션 — 스냅샷 생성 비용

**문제**

Fetch Join으로 쿼리를 1회로 줄였는데도 응답 시간이 기대만큼 줄지 않았습니다.
쿼리가 99.9% 감소했는데 시간은 60% 정도만 단축되었습니다.

**원인**

영속성 컨텍스트는 Dirty Checking을 위해 조회한 엔티티의 **스냅샷을 별도로 보관**합니다.
2,000건 × 전체 필드를 복사하는 비용이 응답 시간을 점유하고 있었습니다.

**해결**

```java
@Service
@Transactional(readOnly = true)   // 클래스 기본값
public class ScheduleService {

    @Transactional                 // 쓰기 메서드만 재정의
    public Long create(...) { ... }
}
```

`readOnly = true`이면 flush 모드가 MANUAL이 되어 스냅샷을 생성하지 않습니다.

**결과**

`readOnly` 적용 전후로 동일 조회에서 응답 시간이 약 1/4 수준으로 감소했습니다.
(측정 시점에 따라 절대값은 달랐으나 감소 비율은 일관되게 유지되었습니다)

스냅샷 비용을 제거하고 나서야 Fetch Join의 효과가 온전히 드러났습니다.
처음에는 "H2 인메모리라 쿼리당 비용이 낮아 개선 폭이 작다"고 해석했으나,
실제로는 스냅샷 생성 비용이 시간을 점유해 쿼리 개선 효과가 가려져 있었습니다.

---

### 4. Redis 캐시와 무효화 전략

**적용**

목록 조회에 `@Cacheable`을 적용해 캐시 HIT 시 DB 접근을 완전히 제거했습니다.

```java
@Cacheable(value = "schedules", key = "'all'")   // Redis key: schedules::all
public List<ScheduleResponse> findAllWithFetch() { ... }
```

캐시 HIT일 때는 **메서드 자체가 실행되지 않아 SQL이 한 건도 발생하지 않습니다.**

**무효화가 필요한 이유**

캐시는 DB와 별개의 저장소이므로 데이터 변경이 자동 반영되지 않습니다.
TTL(10분)만으로는 만료 전까지 낡은 목록이 응답됩니다.

```
목록 조회 → 캐시 생성
일정 수정 → DB만 변경
목록 조회 → 캐시 HIT → 수정 전 데이터 반환 ❌
```

**해결**

등록·수정·삭제 세 지점 모두에 `@CacheEvict`를 적용했습니다.

```java
@CacheEvict(value = "schedules", key = "'all'")
```

단건 캐시라면 `key = "#id"`로 해당 건만 제거하겠지만,
이 프로젝트는 **목록 전체를 하나의 키로 캐싱**하므로 어떤 건이 변경되든
목록 캐시를 통째로 무효화해야 합니다.

| 동작 | Redis 키 | SQL |
| --- | --- | --- |
| 1차 조회 | `schedules::all` 생성 | 발생 |
| 2차 조회 | 유지 | **미발생 (HIT)** |
| 수정(PUT) | **삭제됨** | UPDATE 발생 |
| 3차 조회 | 재생성 | 다시 발생 (MISS) |

---

### 5. Spring Boot 4.1 — 캐시 자동설정 부재

**문제**

`@EnableCaching`과 `spring-boot-starter-data-redis`가 모두 있는데도
`CacheManager` 빈을 찾을 수 없다는 오류로 애플리케이션이 기동되지 않았습니다.

```
A component required a bean of type 'org.springframework.cache.CacheManager'
that could not be found.
```

**원인 추적**

`debug: true`로 조건 평가 리포트(CONDITIONS EVALUATION REPORT)를 확인했습니다.

```
DataRedisAutoConfiguration       → matched
LettuceConnectionConfiguration   → matched
redisConnectionFactory           → matched
CacheAutoConfiguration           → Positive/Negative 어디에도 없음
```

**조건이 맞지 않아 제외된 것이 아니라, 자동설정 클래스 자체가 존재하지 않는 상태**였습니다.
Spring Boot 4.1에서 캐시 자동설정이 분리된 것으로 판단했습니다.

**해결**

`RedisCacheManager`를 직접 빈으로 등록했습니다.

```java
@Bean
public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues();

    return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
}
```

최신 버전이라 참고할 자료가 거의 없는 상황에서,
**로그를 통해 "조건 실패"와 "대상 부재"를 구분해 원인을 특정**한 사례입니다.

---

### 6. 캐시 직렬화 — 타입 정보 손실

**문제**

JSON 직렬화를 적용하자 캐시 HIT 시 예외가 발생했습니다.

```
Cannot cast java.util.LinkedHashMap to ScheduleResponse
```

**원인**

Redis에는 값이 JSON 문자열로 저장되는데, JSON 자체에는 타입 정보가 없습니다.

```json
[{"id":1,"title":"일정 1-1","memberName":"회원1"}]
```

역직렬화 시 Jackson이 이를 `LinkedHashMap`으로 복원하면서 캐스팅에 실패했습니다.
저장은 성공했지만 복원이 불가능한 상태였습니다.

**시도**

`activateDefaultTyping`으로 클래스명을 함께 저장하는 방식을 검토했습니다.
다만 Spring Boot 4가 Jackson 3(`tools.jackson`)로 전환되며 클래스와 enum 값이
모두 변경되어 설정에 시간이 소요되었고, `record`가 암묵적으로 final이라
`NON_FINAL` 옵션에서 제외되는 문제도 있었습니다.

**판단**

이 프로젝트의 목표는 **캐시 HIT/MISS 동작과 성능 차이를 검증하는 것**이었고,
직렬화 형식은 그 목표와 무관했습니다.
`Serializable` 기반 JDK 기본 직렬화로 전환해 TTL·캐시 동작·성능 측정을 모두 유지했습니다.

> 부수적으로, 직렬화 방식을 변경한 뒤 기존 캐시와 형식이 맞지 않아 예외가 발생했습니다.
> 실무에서 직렬화 방식을 변경할 때는 배포와 함께 캐시 무효화가 필요하다는 점을 확인했습니다.

---

### 7. nullable 연관관계와 inner join

**문제**

`categoryId` 없이 등록한 일정이 목록 조회에서 나타나지 않았습니다.
저장은 정상이었으나 조회 결과에서만 누락되었습니다.

**원인**

`join fetch`는 기본적으로 **inner join**입니다.
`category_id`가 null인 행은 조인 조건을 만족하지 못해 결과에서 제외되었습니다.

스키마는 `category_id`를 nullable로 설계했는데 쿼리가 이를 반영하지 않은,
**설계와 구현이 어긋난 상태**였습니다.

**해결**

```java
"left join fetch s.category"
```

동시에 `ScheduleResponse.from()`에 null 방어를 추가했습니다.
left join으로 바꾸는 순간 `getCategory()`가 null을 반환할 수 있기 때문입니다.

```java
schedule.getCategory() == null ? null : schedule.getCategory().getName()
```

**스키마(null 허용) · 쿼리(left join) · DTO(null 방어)가 한 세트로 맞아야 한다**는 것을
확인한 사례입니다.

---

### 8. 일정 수정(PUT) — categoryId 없으면 카테고리가 제거됨

**문제**

`PUT /schedules/{id}` 요청에서 `categoryId`를 생략하면 기존에 설정되어
있던 카테고리가 사라집니다. `update()` 단위 테스트를 작성하며
"categoryId가 없으면 카테고리가 유지될 것"이라고 가정하고 검증하다가
발견했습니다.

**원인**

```java
Category category = null;
if (request.categoryId() != null) {
    category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new IllegalArgumentException("카테고리를 찾을 수 없습니다."));
}
schedule.update(request.title(), request.content(),
        request.startAt(), request.endAt(), category);
```

`categoryId`가 없으면 `category` 지역변수가 `null`로 유지된 채
`Schedule.update()`에 전달됩니다.

```java
this.category = category;
```

`Schedule.update()`는 이 값을 조건 없이 그대로 덮어쓰기 때문에,
기존 카테고리가 `null`로 지워집니다.

**판단**

수정 API가 `PUT /schedules/{id}`로 설계되어 있어, HTTP PUT의 표준 시맨틱
(요청 표현으로 리소스 전체를 교체)에 부합하는 동작으로 판단했습니다.
프론트가 수정 폼 전체를 다시 제출하는 구조라면, `categoryId`가 없다는 것은
사용자가 카테고리를 실제로 제거했다는 의미로 해석하는 것이 맞습니다.

부분 수정(PATCH)이 필요한 API라면 `Optional`/`JsonNullable` 같은 래퍼로
"필드가 요청에 있었는지"와 "필드를 명시적으로 null로 보냈는지"를 구분해야
합니다.

**PUT은 필드 생략을 "값 없음"으로 해석해 리소스를 통째로 교체하는 것이
표준**이라는 것을 테스트 작성 과정에서 확인한 사례입니다. 버그가 아니라
설계 의도임을 확인하고 `ScheduleService.update()`에 이를 명시하는 주석을
추가했습니다.

---

### 9. @WebMvcTest / @DataJpaTest 슬라이스 실패 — 메인 클래스에 붙은 @EnableCaching·@EnableJpaAuditing

**문제**

Repository/Controller 슬라이스 테스트를 추가하자 둘 다 컨텍스트 로딩 단계에서
실패했습니다. `@WebMvcTest`는 `JPA metamodel must not be empty`,
`@DataJpaTest`는 `CacheManager` 타입의 빈을 찾지 못하는 `NoSuchBeanDefinitionException`
이었습니다.

**원인**

`ScheduleApiApplication`에 `@EnableCaching`, `@EnableJpaAuditing`이 직접
붙어 있었습니다. 슬라이스 테스트는 이 메인 클래스를 부트스트랩 설정으로
그대로 재사용하는데, 컴포넌트 스캔은 걷어내지만 **메인 클래스에 직접 붙은
어노테이션은 걷어내지 않습니다.**

```java
@EnableCaching
@SpringBootApplication
@EnableJpaAuditing
public class ScheduleApiApplication { ... }
```

그 결과 `@WebMvcTest`(엔티티 없음)에서는 JPA Auditing이 빈 메타모델을
만나 실패했고, `@DataJpaTest`(캐시 설정 없음)에서는 캐싱 AOP가
`CacheManager`를 찾지 못해 실패했습니다.

**해결**

두 어노테이션을 메인 클래스에서 떼어 각자의 설정 클래스로 옮겼습니다.

```java
// CacheConfig.java
@Configuration
@EnableCaching
public class CacheConfig { ... }

// JpaAuditingConfig.java (신규)
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig { }
```

일반 `@Configuration` 클래스는 슬라이스 테스트의 `TypeExcludeFilter`에
걸려 제외되므로, 이제는 슬라이스 테스트에 영향을 주지 않습니다.

**`@SpringBootApplication` 클래스에 기능 활성화 어노테이션을 직접 붙이면
슬라이스 테스트로 새어 들어간다**는 것을 확인한 사례입니다. 전체
애플리케이션 기동(컴포넌트 스캔)에는 영향이 없어 동작은 그대로 유지됩니다.

---

### 10. Spring Boot 4.1 — @MockBean이 제거되고 @MockitoBean으로 대체됨

**문제**

`@WebMvcTest`에서 Service를 목킹하려고 `@MockBean`을 쓰려 했으나 해당
클래스가 클래스패스에 존재하지 않았습니다.

**원인**

Spring Boot 4.1의 `spring-boot-test` 4.1.0 jar에는 `MockBean` 클래스가
없습니다. Spring Framework의 `@MockitoBean`
(`org.springframework.test.context.bean.override.mockito.MockitoBean`)으로
대체되었습니다.

**해결**

```java
@MockitoBean
private ScheduleService scheduleService;
```

동작은 기존 `@MockBean`과 동일하게, 해당 타입의 빈을 Mockito mock으로
교체해 컨텍스트에 등록합니다.

**최신 Spring Boot 버전을 쓸 때는 자료보다 실제 jar 안의 클래스를
직접 확인하는 것이 더 정확하다**는 것을 확인한 사례입니다.

---

### 11. docker compose 프로젝트 이름 — 한글 폴더명이 이미지 태그를 깨뜸

**문제**

`docker compose up --build`는 성공(exit code 0)했는데, `app` 컨테이너가
뜨지 않고 `Error response from daemon: no such image: 2026_08___app:
invalid reference format`가 발생했습니다.

**원인**

프로젝트 폴더명이 `2026_08_일정_토이프로젝트`로 한글을 포함하고 있어,
docker compose가 폴더명으로부터 자동 생성하는 프로젝트 이름이
`2026_08__`처럼 깨졌습니다. 이 깨진 이름이 그대로 이미지 태그
(`<프로젝트명>_app`)에 들어가 유효하지 않은 레퍼런스가 되었습니다.

**해결**

`docker-compose.yml` 최상단에 프로젝트 이름을 명시해 폴더명에 의존하지
않도록 했습니다.

```yaml
name: schedule-api

services:
  ...
```

**docker compose는 기본적으로 폴더명을 프로젝트 이름(=이미지 태그의 일부)으로
사용하므로, 폴더명에 비ASCII 문자가 들어가면 `name`을 명시해야 안전하다**는
것을 확인한 사례입니다.

---

### 12. Apple Silicon(arm64)에서 eclipse-temurin:17-jre-alpine 빌드 실패

**문제**

11번을 해결한 뒤 다시 빌드하니 이번에는 `no match for platform in
manifest ...: not found`로 실행 스테이지 이미지를 받아오지 못했습니다.

**원인**

빌드 호스트가 Apple Silicon(arm64)인데, `eclipse-temurin:17-jre-alpine`이
가리키는 특정 패치 버전에는 arm64용 매니페스트가 빠져 있었습니다.
JDK 빌드 스테이지(`17-jdk-jammy`)는 문제가 없었고, 실행 스테이지에서만
발생했습니다.

**해결**

실행 스테이지 베이스 이미지를 멀티 아키텍처 지원이 더 안정적인
`eclipse-temurin:17-jre-jammy`로 교체했습니다. jammy 계열에는 `curl`이
기본 포함되어 있지 않아, 헬스체크용으로 별도 설치했습니다.

```dockerfile
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
```

**alpine 계열 공식 이미지는 patch 버전에 따라 특정 아키텍처의 매니페스트가
누락될 수 있어, 멀티 아키텍처 환경(Apple Silicon 등)을 고려한다면 jammy
계열이 더 안전하다**는 것을 확인한 사례입니다.

---

### 13. CD(deploy job) 코드리뷰 — needs의 성공 게이트가 커스텀 if로 사라짐

**문제**

`deploy` job을 추가한 뒤 `/code-review`로 자체 점검했더니, `needs: build`로
연결되어 있음에도 build가 실패했을 때 deploy가 실행되지 않는다는 보장이
없었습니다.

**원인**

```yaml
deploy:
  needs: build
  if: github.event_name == 'push'
```

GitHub Actions는 `if`를 따로 적지 않으면 "needs job이 성공했을 때만
실행"을 기본값으로 깔아줍니다. 하지만 커스텀 `if`를 직접 적으면 그
기본값이 완전히 대체되어, `success()`를 명시하지 않는 한 "성공 여부"는
더 이상 조건에 포함되지 않습니다. 즉 `github.event_name == 'push'`만
있으면, build가 실패해도 push 이벤트라는 조건만 맞으면 deploy가 그대로
실행될 수 있는 상태였습니다.

게다가 `Dockerfile`은 이미지 빌드 시 `-x test`로 테스트를 스킵하기 때문에
(Gradle 테스트는 이미 `build` job에서 검증했다는 전제), deploy가 이 게이트
없이 실행되면 **테스트가 깨진 코드도 이미지로 그대로 빌드·푸시**될 수
있었습니다.

**해결**

```yaml
if: success() && github.event_name == 'push'
```

`success()`를 명시적으로 추가해 원래의 안전장치를 되살렸습니다.

**`needs`는 실행 순서(대기)만 보장하고, 성공 여부 확인은 별도로 챙겨야
한다**는 것을 코드리뷰로 미리 잡은 사례입니다. "끝났다"와 "성공했다"는
다른 개념입니다.

---

### 14. CD — Docker 레이어 캐시 없이 매번 전체 재빌드

**문제**

`deploy` job이 실행될 때마다 Gradle 의존성 다운로드부터 Docker 이미지
빌드까지 매번 처음부터 다시 하고 있었습니다.

**원인**

GitHub Actions 러너는 매번 완전히 새로운 가상머신이라, 로컬 컴퓨터와
달리 이전 실행의 Docker 레이어 캐시가 전혀 남아있지 않습니다.
`docker/build-push-action`에 캐시 설정이 없어서, 문서나 주석만 고친
커밋이라도 이미지 빌드가 매번 몇 분씩 걸렸습니다.

**해결**

```yaml
- uses: docker/build-push-action@v7
  with:
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

GitHub Actions가 제공하는 캐시 저장소(`type=gha`)를 레이어 캐시로
사용하도록 지정했습니다.

**CI 러너는 매번 깨끗한 컴퓨터이므로, 로컬에서 당연했던 캐시 재사용도
명시적으로 설정해줘야 한다**는 것을 확인한 사례입니다.

---

### 15. CD — 이미지 태그의 대소문자 문제

**문제**

이미지 태그를 `github.repository`(예: `jeonchaerim/schedule-api`) 값을
그대로 써서 만들고 있었는데, 이 값에 대문자가 들어가면 push가 실패할
수 있는 상태였습니다.

**원인**

Docker 레지스트리는 이미지 이름이 반드시 소문자여야 합니다. 하지만
`github.repository`는 GitHub 계정/조직명을 가입 시 설정한 대소문자
그대로 보존해서 반환합니다(GitHub 아이디는 대소문자를 구분하지 않지만
표기는 보존함). 지금 계정(`jeonchaerim`)은 이미 소문자라 우연히 문제가
없었지만, 대문자가 포함된 계정으로 이전되거나 이 워크플로우를 다른
저장소에 그대로 복사하면 `invalid reference format` 에러로 push
단계에서 실패하게 됩니다.

**해결**

```yaml
- name: Set lowercase image name
  id: image
  run: echo "name=$(echo '${{ github.repository }}' | tr '[:upper:]' '[:lower:]')" >> "$GITHUB_OUTPUT"
```

강제로 소문자 변환한 값을 별도 step 출력으로 만들어, 이후 태그 지정에
그 값(`steps.image.outputs.name`)을 사용하도록 바꿨습니다.

**지금 당장 문제가 없어 보여도, 외부 값(계정명 등)에 의존하는 문자열은
값의 범위(대소문자 포함 여부)까지 따져봐야 한다**는 것을 확인한 사례입니다.

---

### 16. GHCR에 올라간 이미지 자체가 arm64 매니페스트 없음

**문제**

CD로 ghcr.io에 올라간 이미지를 로컬(Apple Silicon 맥북)에서
`docker pull` + `docker run`으로 직접 실행해보니
`no matching manifest for linux/arm64/v8 in the manifest list entries`
에러가 발생했습니다.

**원인**

GitHub Actions의 `ubuntu-latest` 러너는 amd64(인텔/AMD 계열) 아키텍처
컴퓨터입니다. `docker/build-push-action`을 별다른 설정 없이 쓰면
**러너 자신의 아키텍처로만** 이미지를 빌드하기 때문에, ghcr.io에 올라간
이미지에는 amd64용 레이어만 있고 arm64용 레이어가 없었습니다. 12번에서
겪은 `eclipse-temurin:17-jre-alpine` 문제(남이 만든 이미지에 arm64가
없음)와 같은 카테고리지만, 이번엔 **우리가 직접 만든 이미지**에서
발생했다는 점이 다릅니다.

**해결**

```yaml
- name: Set up QEMU
  uses: docker/setup-qemu-action@v4

- name: Set up Docker Buildx
  uses: docker/setup-buildx-action@v4

- uses: docker/build-push-action@v7
  with:
    platforms: linux/amd64,linux/arm64
```

QEMU 에뮬레이션을 등록해 amd64 러너에서도 arm64용 레이어를 함께 빌드하고,
`platforms`로 두 아키텍처를 모두 명시했습니다. 수정 후 로컬에서 다시
`docker pull`했을 때 `arm64/linux`로 정상 pull되는 것을 확인했습니다.

**CI 러너의 아키텍처와 최종 사용자의 아키텍처는 다를 수 있으므로, 여러
환경에 배포할 이미지는 처음부터 멀티 아키텍처로 빌드해야 한다**는 것을
실제 로컬 검증으로 확인한 사례입니다.

---

### 17. 동시성 제어 — Redisson 분산락으로 일정 중복 등록 방지

**문제 상황**

`ScheduleService.create()`는 겹치는 시간대인지 검증하지 않고, 동시성
제어도 없이 그냥 저장만 했습니다. 같은 회원이 같은 시간대에 일정 등록을
동시에 여러 번 요청하면(예: 더블클릭, 재시도 로직, 여러 탭) 서버 인스턴스가
여러 요청을 동시에 처리하면서 겹치는 일정이 중복으로 저장될 수 있는
경합 조건(race condition)이었습니다.

**재현 방법**

`ScheduleConcurrencyWithoutLockTest`에서, 겹침 검사·락 없이
`ScheduleRepository.save()`만 스레드 10개로 동시에 호출해 같은 회원의
같은 시간대 일정을 등록했습니다.

```
결과: 10개 요청이 전부 성공 → 겹치는 일정 10건이 그대로 중복 저장됨
```

**락 적용**

Redisson으로 회원 단위 분산락을 걸고, 락을 잡은 상태에서만 겹침을
검증·저장하도록 `create()`를 수정했습니다.

```java
RLock lock = redissonClient.getLock("schedule-lock:member:" + memberId);
if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
    throw new LockAcquisitionException(...);   // 409
}
try {
    if (scheduleRepository.existsOverlapping(memberId, startAt, endAt)) {
        throw new ScheduleConflictException(...);   // 409
    }
    // 검증 통과 후에만 저장
} finally {
    unlockAfterCommit(lock);   // 커밋 이후에만 unlock (아래 "발견 2" 참고)
}
```

**락 키를 "memberId + 겹치는 시간대"로 정확히 담지 못하는 이유** — Redis
락은 정확히 같은 문자열 키끼리만 서로를 막아줍니다. "겹치는 시간대"는
09:00~10:00과 09:30~10:30처럼 시작/끝 값이 달라도 겹칠 수 있는 구간
조건이라, 그 값 자체를 락 키에 넣으면 두 요청이 서로 다른 키를 갖게
되어 락이 무의미해집니다. 그래서 락의 범위는 "회원 단위"로 단순화하고,
실제 "겹치는지" 판단은 락을 잡은 상태에서 DB 쿼리(`existsOverlapping`)로
정확히 검증하도록 역할을 나눴습니다.

**전/후 비교 결과**

`ScheduleConcurrencyWithLockTest`로 동일한 조건(스레드 10개, 같은 회원,
같은 시간대)을 재현했습니다.

| | 성공 | 막힘(충돌/락대기초과) | 실제 저장된 행 | 소요 시간 (테스트 메서드 실행 시간) |
| --- | --- | --- | --- | --- |
| 락 적용 전 | 10 | 0 | **10건 (중복)** | 0.376초 |
| 락 적용 후 | 1 | 9 | **1건** | 5.488초 |

(`./gradlew concurrencyTest` 실행 후 `build/test-results/concurrencyTest/TEST-*.xml`의
`testcase` `time` 속성으로 측정한 실제 값입니다. Spring 컨텍스트 부팅 시간은
제외한, 테스트 메서드 본문만의 실행 시간입니다.)

락을 걸면서 응답 시간이 늘어난 게(0.376초 → 5.488초) 눈에 띄는데, 이건
**정확성과 처리량(throughput)을 맞바꾼 것**입니다 — 같은 회원에게
동시에 몰린 요청 10개가 순서대로 하나씩만 처리되도록 강제로 직렬화했기
때문에 늘어난 시간이고, 겹치는 일정이 중복 저장되는 것보다는 낫다고
판단했습니다. 서로 다른 회원의 요청은 락 키가 달라서 이 직렬화의 영향을
받지 않습니다.

**개발 중 발견 1 — Redisson은 Lettuce와 달리 빈 생성 시점에 즉시 연결을 시도함**

Redisson 도입 후 `./gradlew test`를 Redis 없이 돌려봤더니(CI와 동일한
조건), `ScheduleApiApplicationTests`가 `RedisConnectionException`으로
실패했습니다. 캐시에 쓰는 Lettuce(spring-boot-starter-data-redis)는
연결을 지연시켜서 지금까지 Redis 없이도 컨텍스트가 잘 떴는데, Redisson은
`RedissonClient` 빈을 만드는 순간 바로 연결을 시도해서 실패하면 그
자리에서 예외를 던졌습니다.

`@Lazy`를 `RedissonConfig`의 빈 정의와 `ScheduleService`의 주입부
양쪽에 붙여서, 실제로 락을 처음 쓰는 시점(`create()` 호출 시)까지
연결을 미뤘습니다. 다만 `@RequiredArgsConstructor`는 필드의 `@Lazy`를
생성자 파라미터까지 기본적으로 복사해주지 않아서, `lombok.config`에
`lombok.copyableAnnotations += org.springframework.context.annotation.Lazy`를
추가해야 했습니다.

**개발 중 발견 2 — 락 해제를 finally에서 바로 하면 안 됨**

처음엔 `finally { lock.unlock(); }`으로 짰는데, `create()`가
`@Transactional`이라는 걸 놓쳤습니다. 실제 커밋은 메서드가 끝난 뒤
(스프링이 씌운 프록시 바깥에서) 일어나는데, `finally`에서 곧장
`unlock()`하면 **커밋되기도 전에 락이 풀려서**, 그 틈에 다음 스레드가
락을 잡고 겹침을 조회하면 방금 저장한(아직 커밋 전이라 안 보이는) 행을
못 보고 "안 겹친다"고 잘못 판단해 또 저장해버릴 수 있었습니다 — 락을
걸어놓고도 원래 버그가 그대로 재현될 뻔한 지점입니다.

`TransactionSynchronizationManager.registerSynchronization()`으로
`afterCompletion` 콜백을 등록해서, 트랜잭션이 실제로 끝난 뒤(커밋이든
롤백이든)에만 락을 풀도록 고쳤습니다.

**"분산락으로 동시 접근을 막았다"는 것과 "그 락이 트랜잭션 커밋과
올바른 순서로 해제된다"는 것은 별개의 문제이고, 둘 다 맞아야 실제로
안전하다**는 것을 이번 기능에서 직접 확인했습니다.