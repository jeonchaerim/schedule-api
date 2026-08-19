package io.github.jeonchaerim.schedule_api.repository;

import io.github.jeonchaerim.schedule_api.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
}