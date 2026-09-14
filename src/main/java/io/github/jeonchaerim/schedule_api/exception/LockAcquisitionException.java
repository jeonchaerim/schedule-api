package io.github.jeonchaerim.schedule_api.exception;

// 분산락을 제한 시간 안에 얻지 못했을 때(또는 대기 중 인터럽트) 던짐 (409 Conflict로 변환됨)
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String message) {
        super(message);
    }
}
