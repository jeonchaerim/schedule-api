package io.github.jeonchaerim.schedule_api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public record ScheduleUpdateRequest(
        @Schema(example = "수정된 일정") String title,
        @Schema(example = "내용") String content,
        @Schema(example = "2026-08-26T10:00:00") LocalDateTime startAt,
        @Schema(example = "2026-08-26T11:00:00") LocalDateTime endAt,
        @Schema(example = "1") Long categoryId
) {
}