package io.github.jeonchaerim.schedule_api.exception;

// 회원의 겹치는 시간대에 이미 일정이 존재할 때 던짐 (409 Conflict로 변환됨)
public class ScheduleConflictException extends RuntimeException {

    public ScheduleConflictException(String message) {
        super(message);
    }
}
