package io.github.jeonchaerim.schedule_api.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity  // JPA가 관리하는 엔티티로 등록하기 위함
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 프록시 만들때 필요한 기본 생성자임. protected 이상이어야 함
// member.class 방식으로 만드는거지 new member로 빈 객체 생성을 막기 위하여 public은 지양
public class Member extends BaseTimeEntity {

    @Id // PK . 그리고 프록시일 경우 이 ID 값을 들고 기다리고있음
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // PK 생성을 DB auto_increment에 위임
    // DB에 INSERT를 해야 PK를 알 수 있어 persist() 시점에 즉시 INSERT 발생 (쓰기 지연 X)
    @Column(name = "member_id") // 실제 DB컬럼명 매핑
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    @Builder
    public Member(String email, String name) {
        this.email = email;
        this.name = name;
    }
}