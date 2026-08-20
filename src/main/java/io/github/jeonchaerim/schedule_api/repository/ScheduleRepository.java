package io.github.jeonchaerim.schedule_api.repository;

import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    @Query("select s from Schedule s " +
            "join fetch s.member " +
            "join fetch s.category")
    List<Schedule> findAllWithMemberAndCategory();
}