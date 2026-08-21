package io.github.jeonchaerim.schedule_api.controller;

import io.github.jeonchaerim.schedule_api.dto.ScheduleCreateRequest;
import io.github.jeonchaerim.schedule_api.dto.ScheduleResponse;
import io.github.jeonchaerim.schedule_api.dto.ScheduleUpdateRequest;
import io.github.jeonchaerim.schedule_api.repository.ScheduleRepository;
import io.github.jeonchaerim.schedule_api.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;   // ← Repository 대신

    // ** Lazy **
    @GetMapping("/schedules")
    public List<ScheduleResponse> getSchedules() {
        long start = System.currentTimeMillis();
        List<ScheduleResponse> result = scheduleService.findAll();
        System.out.println(">>> [LAZY] 소요시간: " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }

    // ** Fetch Join **
    @GetMapping("/schedules/fetch")
    public List<ScheduleResponse> getSchedulesWithFetch() {
        long start = System.currentTimeMillis();
        List<ScheduleResponse> result = scheduleService.findAllWithFetch();
        System.out.println(">>> [FETCH JOIN] 소요시간: " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }

    // ** Dirty Checking **
    @PutMapping("/schedules/{id}")
    public void update(@PathVariable Long id, @RequestBody ScheduleUpdateRequest request) {
        scheduleService.update(id, request);
    }

    @PostMapping("/schedules")
    public Long create(@RequestBody ScheduleCreateRequest request) {
        return scheduleService.create(request);
    }

    @DeleteMapping("/schedules/{id}")
    public void delete(@PathVariable Long id) {
        scheduleService.delete(id);
    }
}