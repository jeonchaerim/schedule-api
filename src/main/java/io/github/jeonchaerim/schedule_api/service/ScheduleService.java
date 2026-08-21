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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    public List<ScheduleResponse> findAllWithFetch() {
        return scheduleRepository.findAllWithMemberAndCategory().stream()
                .map(ScheduleResponse::from)
                .toList();
    }

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

        //
        schedule.update(request.title(), request.content(),
                request.startAt(), request.endAt(), category);
        // save() 안 부름 ← 여기가 포인트
    }

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