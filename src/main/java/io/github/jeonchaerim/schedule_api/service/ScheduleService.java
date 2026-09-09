package io.github.jeonchaerim.schedule_api.service;

import io.github.jeonchaerim.schedule_api.domain.Category;
import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import io.github.jeonchaerim.schedule_api.dto.ScheduleCreateRequest;
import io.github.jeonchaerim.schedule_api.dto.ScheduleResponse;
import io.github.jeonchaerim.schedule_api.dto.ScheduleUpdateRequest;
import io.github.jeonchaerim.schedule_api.repository.CategoryRepository;
import io.github.jeonchaerim.schedule_api.repository.MemberRepository;
import io.github.jeonchaerim.schedule_api.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
// ← 클래스 기본값: 조회 전용
// Snapshot 비용 측정해보기
@Transactional(readOnly = true)
public class ScheduleService {

    private final MemberRepository memberRepository;
    private final CategoryRepository categoryRepository;
    private final ScheduleRepository scheduleRepository;

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
    @CacheEvict(value = "schedules", key = "'all'")
    @Transactional
    public Long create(ScheduleCreateRequest request) {
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다. id=" + request.memberId()));

        Category category = findCategoryOrNull(request.categoryId());

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
}