package io.github.jeonchaerim.schedule_api.repository;

import io.github.jeonchaerim.schedule_api.domain.Category;
import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ScheduleRepositoryTest {

    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private Member member1;
    private Member member2;
    private Category category;
    private Schedule scheduleWithCategory;
    private Schedule scheduleWithoutCategory;

    @BeforeEach
    void setUp() {
        member1 = memberRepository.save(Member.builder().email("member1@test.com").name("회원1").build());
        member2 = memberRepository.save(Member.builder().email("member2@test.com").name("회원2").build());
        category = categoryRepository.save(Category.builder().name("업무").color("#FF5733").build());

        scheduleWithCategory = scheduleRepository.save(Schedule.builder()
                .title("팀 회의")
                .content("주간 회의")
                .startAt(LocalDateTime.of(2026, 8, 26, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 26, 11, 0))
                .member(member1)
                .category(category)
                .build());

        scheduleWithoutCategory = scheduleRepository.save(Schedule.builder()
                .title("개인 일정")
                .content("카테고리 없음")
                .startAt(LocalDateTime.of(2026, 8, 27, 9, 0))
                .endAt(LocalDateTime.of(2026, 8, 27, 10, 0))
                .member(member2)
                .category(null)
                .build());
    }

    @Test
    @DisplayName("save: 일정을 저장하면 id가 생성된다")
    void save_generatesId() {
        Schedule saved = scheduleRepository.save(Schedule.builder()
                .title("새 일정")
                .content("내용")
                .startAt(LocalDateTime.of(2026, 9, 1, 9, 0))
                .endAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .member(member1)
                .category(category)
                .build());

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("findById: 저장된 일정을 조회하면 필드가 일치한다")
    void findById_returnsSchedule() {
        Optional<Schedule> found = scheduleRepository.findById(scheduleWithCategory.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("팀 회의");
        assertThat(found.get().getMember().getId()).isEqualTo(member1.getId());
    }

    @Test
    @DisplayName("findAll: 저장된 일정 전체를 조회한다")
    void findAll_returnsAllSchedules() {
        List<Schedule> schedules = scheduleRepository.findAll();

        assertThat(schedules).hasSize(2);
    }

    @Test
    @DisplayName("delete: 일정을 삭제하면 다시 조회되지 않는다")
    void delete_removesSchedule() {
        Long id = scheduleWithCategory.getId();

        scheduleRepository.delete(scheduleWithCategory);

        assertThat(scheduleRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("수정: update() 호출 후 flush하면 변경 내용이 DB에 반영된다")
    void update_changesArePersisted() {
        Schedule schedule = scheduleRepository.findById(scheduleWithCategory.getId()).orElseThrow();

        schedule.update("수정된 회의", "수정된 내용",
                LocalDateTime.of(2026, 8, 28, 10, 0),
                LocalDateTime.of(2026, 8, 28, 11, 0),
                category);
        scheduleRepository.flush();
        entityManager.clear();   // 1차 캐시를 비워 DB에서 다시 조회하도록 강제

        Schedule updated = scheduleRepository.findById(scheduleWithCategory.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("수정된 회의");
        assertThat(updated.getContent()).isEqualTo("수정된 내용");
    }

    @Test
    @DisplayName("findAllWithMemberAndCategory: 연관관계를 fetch join하고, 카테고리가 없는 일정도 포함한다")
    void findAllWithMemberAndCategory_fetchesAssociationsIncludingNullCategory() {
        entityManager.clear();   // 1차 캐시 대신 fetch join 쿼리로 실제 채워지는지 확인

        List<Schedule> schedules = scheduleRepository.findAllWithMemberAndCategory();

        assertThat(schedules).hasSize(2);

        Schedule withCategory = schedules.stream()
                .filter(s -> s.getId().equals(scheduleWithCategory.getId()))
                .findFirst().orElseThrow();
        assertThat(withCategory.getMember().getName()).isEqualTo("회원1");
        assertThat(withCategory.getCategory().getName()).isEqualTo("업무");

        Schedule withoutCategory = schedules.stream()
                .filter(s -> s.getId().equals(scheduleWithoutCategory.getId()))
                .findFirst().orElseThrow();
        assertThat(withoutCategory.getMember().getName()).isEqualTo("회원2");
        assertThat(withoutCategory.getCategory()).isNull();
    }
}
