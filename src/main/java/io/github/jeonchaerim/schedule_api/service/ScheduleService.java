package io.github.jeonchaerim.schedule_api.service;

import io.github.jeonchaerim.schedule_api.domain.Category;
import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import io.github.jeonchaerim.schedule_api.dto.ScheduleCreateRequest;
import io.github.jeonchaerim.schedule_api.dto.ScheduleResponse;
import io.github.jeonchaerim.schedule_api.dto.ScheduleUpdateRequest;
import io.github.jeonchaerim.schedule_api.exception.LockAcquisitionException;
import io.github.jeonchaerim.schedule_api.exception.ScheduleConflictException;
import io.github.jeonchaerim.schedule_api.repository.CategoryRepository;
import io.github.jeonchaerim.schedule_api.repository.MemberRepository;
import io.github.jeonchaerim.schedule_api.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
// ← 클래스 기본값: 조회 전용
// Snapshot 비용 측정해보기
@Transactional(readOnly = true)
public class ScheduleService {

    // 락 이름 접두사 — Redis 안에서 캐시 키 등 다른 용도와 안 섞이게 네임스페이스 구분
    private static final String LOCK_KEY_PREFIX = "schedule-lock:member:";
    // 락을 못 잡았을 때 최대 몇 초까지 기다려볼지. 사용자를 오래 기다리게 하지 않으면서
    // 순간적인 경합 정도는 흡수할 수 있는 값으로 3초를 선택함
    private static final long LOCK_WAIT_SECONDS = 3L;
    // 락을 잡은 뒤 최대 몇 초간 점유할지(고정값 — Redisson의 watchdog 자동연장은 안 씀).
    // create()의 실제 작업(겹침 조회 1번 + insert 1번)은 보통 수십 ms면 끝나므로
    // 5초는 네트워크 지연까지 감안해도 충분히 여유 있으면서, 혹시 인스턴스가 죽어도
    // 락이 5초 뒤엔 자동 해제되게 하는 안전장치이기도 함
    private static final long LOCK_LEASE_SECONDS = 5L;

    private final MemberRepository memberRepository;
    private final CategoryRepository categoryRepository;
    private final ScheduleRepository scheduleRepository;
    // @Lazy — Redisson은 Lettuce(캐시)와 달리 빈 생성 시점에 즉시 연결을 시도해서,
    // 테스트 환경처럼 Redis가 없는 곳에서 컨텍스트 전체가 못 뜰 수 있음. 실제로
    // create()에서 락을 처음 쓸 때까지 연결을 미루도록 지연 프록시로 주입받음
    // (lombok.config의 copyableAnnotations 설정으로 생성자까지 전달됨)
    @Lazy
    private final RedissonClient redissonClient;

    /** 지연 로딩 — N+1 발생 (비교용) */
    public List<ScheduleResponse> findAll() {
        return scheduleRepository.findAll().stream()
                .map(ScheduleResponse::from)
                .toList();
    }

    /** Fetch Join 적용 */
    // @Cacheable
    // 1. 요청이 들어오면 proxy객체로 감싸서, schedules::all (키이름::값)
    // 이 있는지 확인하여 메서드 실행 유무를 판단해서 Reids 캐시에서 꺼내쓸수 있는지 결정 (HIT or MISS)
    // 2. 있으면 꺼내 쓰고 없으면 채워라
    @Cacheable(value = "schedules", key = "'all'")
    public List<ScheduleResponse> findAllWithFetch() {
        return scheduleRepository.findAllWithMemberAndCategory().stream()
                .map(ScheduleResponse::from)
                .collect(Collectors.toList());   // ← .toList() 대신
    }

    // @CacheEvict
    // 1. 데이터가 바뀌면 캐시를 지워라
    // 2. 메서드가 정상 종료된 후 실행돼. 예외가 나면 캐시를 안 지워 — 트랜잭션이 롤백됐으니 DB도 안 바뀌었을 테고, 그럼 캐시도 유지해야함
    // 동시성 제어 — 왜 "memberId + 겹치는 시간대"를 락 키에 그대로 못 담는가
    // Redis 락은 정확히 같은 문자열 키끼리만 서로를 막아준다. 근데 "겹치는 시간대"는
    // 예를 들어 09:00~10:00과 09:30~10:30처럼 시작/끝 값이 서로 달라도 겹칠 수 있는
    // "구간" 조건이라, 그 값 자체를 락 키에 넣으면 두 요청이 서로 다른 키를 갖게 되어
    // 락이 둘을 못 막는다(무의미해짐). 그래서 락의 범위는 "회원 단위"로 단순화해서
    // (LOCK_KEY_PREFIX + memberId), 같은 회원의 일정 생성 요청은 무조건 한 번에
    // 하나씩만 처리되게 하고, 실제 "겹치는지" 여부는 락을 잡은 상태에서 DB로 정확히
    // 검증한다(existsOverlapping). 락은 "동시에 못 들어오게"만 막고 겹침 판단은
    // DB 쿼리가 한다 — 이렇게 역할을 나눈 게 이 설계의 핵심.
    @CacheEvict(value = "schedules", key = "'all'")
    @Transactional
    public Long create(ScheduleCreateRequest request) {
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다. id=" + request.memberId()));

        Category category = findCategoryOrNull(request.categoryId());

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + request.memberId());
        if (!tryLock(lock)) {
            throw new LockAcquisitionException(
                    "일정 등록 요청이 몰려 있습니다. 잠시 후 다시 시도해주세요. memberId=" + request.memberId());
        }

        try {
            // 락을 잡은 뒤에 검증해야 함 — 락 밖에서 검증하면 두 스레드가 동시에
            // "안 겹친다"고 판단한 뒤 둘 다 저장해버릴 수 있음(그게 원래 버그였음)
            if (scheduleRepository.existsOverlapping(request.memberId(), request.startAt(), request.endAt())) {
                throw new ScheduleConflictException(
                        "이미 겹치는 시간대의 일정이 존재합니다. memberId=" + request.memberId());
            }

            Schedule schedule = Schedule.builder()
                    .title(request.title())
                    .content(request.content())
                    .startAt(request.startAt())
                    .endAt(request.endAt())
                    .member(member)
                    .category(category)
                    .build();

            // 저장한 엔티티의 id를  리턴함  (save 후 getID)
            return scheduleRepository.save(schedule).getId();
        } finally {
            unlockAfterCommit(lock);
        }
    }

    @CacheEvict(value = "schedules", key = "'all'")
    // controller에서 proxy 호출할때 commit + rollback
    @Transactional      // ← readOnly 아님! 클래스 기본값을 덮어씀
    public void update(Long id, ScheduleUpdateRequest request) {
        Schedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없습니다. id=" + id));

        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new IllegalArgumentException("카테고리를 찾을 수 없습니다."));
        }

        // PUT 풀 리플레이스 정책: categoryId 없으면 category가 null로 전달되어 기존 카테고리도 제거됨 (부분 수정 X)
        schedule.update(request.title(), request.content(),
                request.startAt(), request.endAt(), category);
        // save() 안 부름 ← 여기가 포인트
    }

    @CacheEvict(value = "schedules", key = "'all'")
    @Transactional
    public void delete(Long id) {
        Schedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없습니다. id=" + id));
        scheduleRepository.delete(schedule);
    }

    private Category findCategoryOrNull(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("카테고리를 찾을 수 없습니다. id=" + categoryId));
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("락을 기다리는 중 인터럽트가 발생했습니다.");
        }
    }

    // 락 해제를 왜 finally에서 바로 안 하고 트랜잭션 커밋 이후로 미루는가
    // create()는 @Transactional이라, 실제 커밋은 이 메서드가 "끝난 뒤"
    // (스프링이 씌운 프록시 바깥에서) 일어난다. finally에서 곧장 unlock()하면
    // 커밋되기도 전에 락이 풀려서, 그 틈에 다른 스레드가 락을 잡고 겹침을 조회하면
    // 방금 저장한(아직 커밋 전이라 안 보이는) 행을 못 보고 "안 겹친다"고 잘못
    // 판단해 또 저장해버릴 수 있다. 그래서 트랜잭션이 실제로 끝난 뒤(커밋이든
    // 롤백이든)에만 락을 풀도록 TransactionSynchronization을 등록한다.
    private void unlockAfterCommit(RLock lock) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
        } else if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}