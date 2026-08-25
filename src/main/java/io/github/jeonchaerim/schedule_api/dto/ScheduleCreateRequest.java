package io.github.jeonchaerim.schedule_api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public record ScheduleCreateRequest(
        @Schema(description = "일정 제목", example = "팀 회의") String title,
        @Schema(description = "일정 내용", example = "주간 회의") String content,
        @Schema(description = "시작 일시", example = "2026-08-26T10:00:00") LocalDateTime startAt,
        @Schema(description = "종료 일시", example = "2026-08-26T11:00:00") LocalDateTime endAt,
        @Schema(description = "회원 ID", example = "1") Long memberId,
        @Schema(description = "카테고리 ID (선택)", example = "1") Long categoryId
) {
}