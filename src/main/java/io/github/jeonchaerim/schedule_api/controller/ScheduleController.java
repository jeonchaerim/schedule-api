package io.github.jeonchaerim.schedule_api.controller;

import io.github.jeonchaerim.schedule_api.dto.ScheduleResponse;
import io.github.jeonchaerim.schedule_api.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleRepository scheduleRepository;

    @GetMapping("/schedules")
    public List<ScheduleResponse> getSchedules() {
        long start = System.currentTimeMillis();          // ← 메서드 안, 맨 위

        List<ScheduleResponse> result = scheduleRepository.findAll().stream()
                .map(ScheduleResponse::from)
                .toList();

        System.out.println(">>> 소요시간: "
                + (System.currentTimeMillis() - start) + "ms");   // ← return 전

        return result;
    }
}