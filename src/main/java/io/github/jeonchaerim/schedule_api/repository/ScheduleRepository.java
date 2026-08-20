package io.github.jeonchaerim.schedule_api.repository;

import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
   // 직접 명시
    @Query("select s from Schedule s " +
            "join fetch s.member " +
            "join fetch s.category")
    List<Schedule> findAllWithMemberAndCategory();

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