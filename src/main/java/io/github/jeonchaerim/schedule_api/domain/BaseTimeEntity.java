package io.github.jeonchaerim.schedule_api.domain;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass // 이 클래스의 필드를 자식 엔티티에게 물려주되, 이 클래스 자체는 테이블로 만들지 마라. 자식 엔티티에 필드가 생김
@EntityListeners(AuditingEntityListener.class) // 이 엔티티에 이벤트 리스너를 붙임->엔티티가 저장(persist)되거나 수정될때 콜백되어서 컬럼에 현재시간을 넣어준다
public abstract class BaseTimeEntity {

    @CreatedDate    // 처음 저장될 때 한 번만 값이 들어감
    private LocalDateTime createdAt;

    @LastModifiedDate   // 저장 시점에도 들어가고, 수정될 때마다 갱신돼. Dirty Checking으로 UPDATE가 나갈 때 같이 갱신되는 거고, 그래서 3주차 Dirty Checking 실습이랑 이어져.
    private LocalDateTime updatedAt;
}