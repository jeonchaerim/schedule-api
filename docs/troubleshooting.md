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