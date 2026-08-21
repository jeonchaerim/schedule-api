package io.github.jeonchaerim.schedule_api.dto;

import java.time.LocalDateTime;

public record ScheduleUpdateRequest(
        String title,
        String content,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Long categoryId
) {}