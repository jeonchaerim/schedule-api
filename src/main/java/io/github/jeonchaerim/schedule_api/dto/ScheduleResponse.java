package io.github.jeonchaerim.schedule_api.dto;

import io.github.jeonchaerim.schedule_api.domain.Schedule;

public record ScheduleResponse(
        Long id,
        String title,
        String memberName,
        String categoryName
) {
    public static ScheduleResponse from(Schedule schedule) {
        return new ScheduleResponse(
                schedule.getId(),
                schedule.getTitle(),
                schedule.getMember().getName(),      // ← 프록시 초기화 발생
                schedule.getCategory().getName()     // ← 여기도
        );
    }
}