package io.github.jeonchaerim.schedule_api.repository;

import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
   // 직접 명시
    @Query("select s from Schedule s " +
            "join fetch s.member " +
            "left join fetch s.category")
    List<Schedule> findAllWithMemberAndCategory();

    // 겹침 판정: 기존.startAt < 새.endAt AND 기존.endAt > 새.startAt (표준 구간 겹침 조건)
    // 딱 맞닿는 경우(10:00~11:00, 11:00~12:00)는 안 겹치는 걸로 처리됨(부등호가 등호 없이 strict)
    @Query("select case when count(s) > 0 then true else false end " +
            "from Schedule s " +
            "where s.member.id = :memberId " +
            "and s.startAt < :endAt " +
            "and s.endAt > :startAt")
    boolean existsOverlapping(@Param("memberId") Long memberId,
                               @Param("startAt") LocalDateTime startAt,
                               @Param("endAt") LocalDateTime endAt);

    /*
    // 레포지토리 작성 방식 3가지
    // ① 기본 제공 — JpaRepository에 이미 있음
    //    scheduleRepository.findAll();

    // ② 메서드 이름으로 생성 — 이름을 파싱해서 JPA가 쿼리를 만듦
    List<Schedule> findByMemberId(Long memberId);
                        (필드명, 컬럼명 X)
    // → SELECT * FROM schedule WHERE member_id = ?
    // 조합 가능
    // 1.findBy + 필드명 + and/order by
    //findByTitle(String title)                      // where title = ?
    //findByTitleContaining(String keyword)          // where title like %?%
    //findByStartAtAfter(LocalDateTime time)         // where start_at > ?
    //findByStartAtBetween(LocalDateTime a, LocalDateTime b)
    //findByMemberIdAndCategoryId(Long m, Long c)    // and 조건
    //findByMemberIdOrderByStartAtDesc(Long id)      // order by
    // 2. countBy
    //countByMemberId(Long id)                       // select count(*)
    // 3. existsBy
    //existsByEmail(String email)                    // 존재 여부만
    // 4. deleteBy
    //deleteByMemberId(Long id)
    //..
    //findTop10ByOrderByStartAtDesc()                // limit 10

    // ③ @Query로 직접 — 복잡한 건 이걸로
    @Query("select s from Schedule s join fetch s.member")
    List<Schedule> findAllWithMember();

     */
}