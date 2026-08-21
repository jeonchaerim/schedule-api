package io.github.jeonchaerim.schedule_api.dto;

import java.time.LocalDateTime;

public record ScheduleCreateRequest(
        String title,
        String content,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Long memberId,
        Long categoryId
) {}