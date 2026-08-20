package io.github.jeonchaerim.schedule_api.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String content;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    // 1:N FK에 이 주석 붙임
    // joingColumn으로 실 DB와 매핑될 컬럼 명시
    // ** 지연 로딩: 실제 접근 시점까지 프록시로 두고 쿼리를 미룸
    // 기본값이 EAGER라 명시 필요. EAGER는 불필요한 조인과 JPQL에서의 N+1을 유발
    // LAZY로 명시해놓고, 지연 로딩해두고 실제 fetch join이 필요한 경우에만 별도로 명시하는것이 맞음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Builder
    public Schedule(String title, String content, LocalDateTime startAt,
                    LocalDateTime endAt, Member member, Category category) {
        this.title = title;
        this.content = content;
        this.startAt = startAt;
        this.endAt = endAt;
        this.member = member;
        this.category = category;
    }
}
