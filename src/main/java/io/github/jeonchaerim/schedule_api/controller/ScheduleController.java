package io.github.jeonchaerim.schedule_api.controller;

import io.github.jeonchaerim.schedule_api.dto.ScheduleCreateRequest;
import io.github.jeonchaerim.schedule_api.dto.ScheduleResponse;
import io.github.jeonchaerim.schedule_api.dto.ScheduleUpdateRequest;
import io.github.jeonchaerim.schedule_api.repository.ScheduleRepository;
import io.github.jeonchaerim.schedule_api.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "일정", description = "일정 조회 및 관리 API")
@RestController
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;   // ← Repository 대신

    @Operation(summary = "일정 목록 조회 (지연 로딩)",
            description = "N+1이 발생하는 케이스. 성능 비교용 엔드포인트")
    @GetMapping("/schedules")
    public List<ScheduleResponse> getSchedules() {
        long start = System.currentTimeMillis();
        List<ScheduleResponse> result = scheduleService.findAll();
        System.out.println(">>> [LAZY] 소요시간: " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }

    @Operation(summary = "일정 목록 조회 (Fetch Join + 캐시)",
            description = "Fetch Join으로 N+1 해결, Redis 캐시 적용")
    @GetMapping("/schedules/fetch")
    public List<ScheduleResponse> getSchedulesWithFetch() {
        long start = System.currentTimeMillis();
        List<ScheduleResponse> result = scheduleService.findAllWithFetch();
        System.out.println(">>> [FETCH JOIN] 소요시간: " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }

    @Operation(summary = "일정 수정", description = "Dirty Checking으로 처리")
    @PutMapping("/schedules/{id}")
    public void update(@PathVariable Long id, @RequestBody ScheduleUpdateRequest request) {
        scheduleService.update(id, request);
    }

    @Operation(summary = "일정 등록")
    @PostMapping("/schedules")
    public Long create(@RequestBody ScheduleCreateRequest request) {
        return scheduleService.create(request);
    }

    @Operation(summary = "일정 삭제")
    @DeleteMapping("/schedules/{id}")
    public void delete(@PathVariable Long id) {
        scheduleService.delete(id);
    }
}