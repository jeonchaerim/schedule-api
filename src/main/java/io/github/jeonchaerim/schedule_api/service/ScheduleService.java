package io.github.jeonchaerim.schedule_api.service;

import io.github.jeonchaerim.schedule_api.dto.ScheduleResponse;
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
}