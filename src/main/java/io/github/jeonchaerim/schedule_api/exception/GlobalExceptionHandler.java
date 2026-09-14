package io.github.jeonchaerim.schedule_api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage()));
    }

    // 겹치는 일정 존재 / 락 획득 실패 — 둘 다 "지금은 이 요청을 처리할 수 없다"는 상태 충돌이라 409로 통일
    @ExceptionHandler(ScheduleConflictException.class)
    public ResponseEntity<Map<String, String>> handleScheduleConflict(ScheduleConflictException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(LockAcquisitionException.class)
    public ResponseEntity<Map<String, String>> handleLockAcquisition(LockAcquisitionException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage()));
    }
}