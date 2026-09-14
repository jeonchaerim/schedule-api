package io.github.jeonchaerim.schedule_api.service;

import io.github.jeonchaerim.schedule_api.domain.Category;
import io.github.jeonchaerim.schedule_api.domain.Member;
import io.github.jeonchaerim.schedule_api.domain.Schedule;
import io.github.jeonchaerim.schedule_api.dto.ScheduleCreateRequest;
import io.github.jeonchaerim.schedule_api.dto.ScheduleResponse;
import io.github.jeonchaerim.schedule_api.dto.ScheduleUpdateRequest;
import io.github.jeonchaerim.schedule_api.exception.LockAcquisitionException;
import io.github.jeonchaerim.schedule_api.exception.ScheduleConflictException;
import io.github.jeonchaerim.schedule_api.repository.CategoryRepository;
import io.github.jeonchaerim.schedule_api.repository.MemberRepository;
import io.github.jeonchaerim.schedule_api.repository.ScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;

    @InjectMocks
    private ScheduleService scheduleService;

    @Test
    @DisplayName("findAll: 일정 목록을 ScheduleResponse로 변환하여 반환한다 (지연 로딩 버전)")
    void findAll_success() {
        Member member = Member.builder().email("test@test.com").name("테스터").build();
        Category category = Category.builder().name("업무").color("#FFFFFF").build();
        Schedule schedule = Schedule.builder()
                .title("팀 회의")
                .content("주간 회의")
                .startAt(LocalDateTime.of(2026, 8, 26, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 26, 11, 0))
                .member(member)
                .category(category)
                .build();
        ReflectionTestUtils.setField(schedule, "id", 1L);

        given(scheduleRepository.findAll()).willReturn(List.of(schedule));

        List<ScheduleResponse> result = scheduleService.findAll();

        assertThat(result).hasSize(1);
        ScheduleResponse response = result.get(0);
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.memberName()).isEqualTo("테스터");
        assertThat(response.categoryName()).isEqualTo("업무");
    }

    @Test
    @DisplayName("create: 회원과 카테고리가 존재하고 겹치는 일정이 없으면 생성하고 id를 반환한다")
    void create_success() throws InterruptedException {
        Member member = Member.builder().email("test@test.com").name("테스터").build();
        ReflectionTestUtils.setField(member, "id", 1L);

        Category category = Category.builder().name("업무").color("#FFFFFF").build();
        ReflectionTestUtils.setField(category, "id", 1L);

        ScheduleCreateRequest request = new ScheduleCreateRequest(
                "팀 회의", "주간 회의",
                LocalDateTime.of(2026, 8, 26, 10, 0),
                LocalDateTime.of(2026, 8, 26, 11, 0),
                member.getId(), category.getId());

        Schedule saved = Schedule.builder()
                .title(request.title())
                .content(request.content())
                .startAt(request.startAt())
                .endAt(request.endAt())
                .member(member)
                .category(category)
                .build();
        ReflectionTestUtils.setField(saved, "id", 10L);

        given(memberRepository.findById(member.getId())).willReturn(Optional.of(member));
        given(categoryRepository.findById(category.getId())).willReturn(Optional.of(category));
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        given(scheduleRepository.existsOverlapping(anyLong(), any(), any())).willReturn(false);
        given(scheduleRepository.save(any(Schedule.class))).willReturn(saved);

        Long resultId = scheduleService.create(request);

        assertThat(resultId).isEqualTo(10L);
        // 단위 테스트에는 실제 트랜잭션이 없어 unlockAfterCommit()의 else 분기(즉시 unlock)를 타게 됨 —
        // 커밋 이후로 미루는 분기(if, TransactionSynchronization)는 실제 트랜잭션이 필요해 concurrencyTest에서 검증
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("create: 겹치는 시간대의 일정이 이미 있으면 ScheduleConflictException이 발생하고 저장하지 않는다")
    void create_overlapping_throwsScheduleConflictException() throws InterruptedException {
        Member member = Member.builder().email("test@test.com").name("테스터").build();
        ReflectionTestUtils.setField(member, "id", 1L);

        ScheduleCreateRequest request = new ScheduleCreateRequest(
                "팀 회의", "주간 회의",
                LocalDateTime.of(2026, 8, 26, 10, 0),
                LocalDateTime.of(2026, 8, 26, 11, 0),
                member.getId(), null);

        given(memberRepository.findById(member.getId())).willReturn(Optional.of(member));
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);
        given(scheduleRepository.existsOverlapping(anyLong(), any(), any())).willReturn(true);

        assertThatThrownBy(() -> scheduleService.create(request))
                .isInstanceOf(ScheduleConflictException.class)
                .hasMessageContaining("1");

        verify(scheduleRepository, never()).save(any());
        // 예외로 빠져나가도 finally에서 락은 반드시 풀려야 함
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("create: 락을 제한 시간 안에 못 잡으면 LockAcquisitionException이 발생하고 겹침 조회조차 하지 않는다")
    void create_lockNotAcquired_throwsLockAcquisitionException() throws InterruptedException {
        Member member = Member.builder().email("test@test.com").name("테스터").build();
        ReflectionTestUtils.setField(member, "id", 1L);

        ScheduleCreateRequest request = new ScheduleCreateRequest(
                "팀 회의", "주간 회의",
                LocalDateTime.of(2026, 8, 26, 10, 0),
                LocalDateTime.of(2026, 8, 26, 11, 0),
                member.getId(), null);

        given(memberRepository.findById(member.getId())).willReturn(Optional.of(member));
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        given(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);

        assertThatThrownBy(() -> scheduleService.create(request))
                .isInstanceOf(LockAcquisitionException.class);

        verify(scheduleRepository, never()).existsOverlapping(any(), any(), any());
        verify(scheduleRepository, never()).save(any());
        // 애초에 락을 못 잡았으니 unlock()도 호출되면 안 됨
        verify(rLock, never()).unlock();
    }

    @Test
    @DisplayName("create: 존재하지 않는 회원 id면 락을 잡기 전에 IllegalArgumentException이 발생한다")
    void create_memberNotFound_throwsException() {
        ScheduleCreateRequest request = new ScheduleCreateRequest(
                "팀 회의", "주간 회의",
                LocalDateTime.of(2026, 8, 26, 10, 0),
                LocalDateTime.of(2026, 8, 26, 11, 0),
                999L, null);

        given(memberRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> scheduleService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");

        verify(categoryRepository, never()).findById(any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("findAllWithFetch: 일정 목록을 ScheduleResponse로 변환하여 반환한다")
    void findAllWithFetch_success() {
        Member member = Member.builder().email("test@test.com").name("테스터").build();
        Category category = Category.builder().name("업무").color("#FFFFFF").build();
        Schedule schedule = Schedule.builder()
                .title("팀 회의")
                .content("주간 회의")
                .startAt(LocalDateTime.of(2026, 8, 26, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 26, 11, 0))
                .member(member)
                .category(category)
                .build();
        ReflectionTestUtils.setField(schedule, "id", 1L);

        given(scheduleRepository.findAllWithMemberAndCategory()).willReturn(List.of(schedule));

        List<ScheduleResponse> result = scheduleService.findAllWithFetch();

        assertThat(result).hasSize(1);
        ScheduleResponse response = result.get(0);
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("팀 회의");
        assertThat(response.memberName()).isEqualTo("테스터");
        assertThat(response.categoryName()).isEqualTo("업무");
    }

    @Test
    @DisplayName("update: categoryId가 없으면 카테고리 조회 없이 나머지 필드만 수정하고 save는 호출하지 않는다(더티 체킹)")
    void update_withoutCategoryId_updatesFieldsWithoutSave() {
        Member member = Member.builder().email("test@test.com").name("테스터").build();
        Category originalCategory = Category.builder().name("업무").color("#FFFFFF").build();
        Schedule schedule = Schedule.builder()
                .title("팀 회의")
                .content("주간 회의")
                .startAt(LocalDateTime.of(2026, 8, 26, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 26, 11, 0))
                .member(member)
                .category(originalCategory)
                .build();

        ScheduleUpdateRequest request = new ScheduleUpdateRequest(
                "수정된 회의", "내용 수정",
                LocalDateTime.of(2026, 8, 27, 10, 0),
                LocalDateTime.of(2026, 8, 27, 11, 0),
                null);

        given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));

        scheduleService.update(1L, request);

        assertThat(schedule.getTitle()).isEqualTo("수정된 회의");
        assertThat(schedule.getContent()).isEqualTo("내용 수정");
        // categoryId가 없으면 Schedule.update()가 category를 null로 덮어씀 (카테고리 유지가 아님 — 현재 동작)
        assertThat(schedule.getCategory()).isNull();
        verify(categoryRepository, never()).findById(any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("update: 존재하지 않는 일정 id면 IllegalArgumentException이 발생한다")
    void update_notFound_throwsException() {
        ScheduleUpdateRequest request = new ScheduleUpdateRequest(
                "수정된 회의", "내용 수정",
                LocalDateTime.of(2026, 8, 27, 10, 0),
                LocalDateTime.of(2026, 8, 27, 11, 0),
                null);

        given(scheduleRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> scheduleService.update(999L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");

        verify(categoryRepository, never()).findById(any());
    }

    @Test
    @DisplayName("delete: 존재하지 않는 일정 id면 IllegalArgumentException이 발생한다")
    void delete_notFound_throwsException() {
        given(scheduleRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> scheduleService.delete(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");

        verify(scheduleRepository, never()).delete(any());
    }
}
