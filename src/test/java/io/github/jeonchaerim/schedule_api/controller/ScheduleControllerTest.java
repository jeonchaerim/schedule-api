package io.github.jeonchaerim.schedule_api.controller;

import io.github.jeonchaerim.schedule_api.dto.ScheduleResponse;
import io.github.jeonchaerim.schedule_api.service.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScheduleController.class)
class ScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScheduleService scheduleService;

    @Test
    @DisplayName("GET /schedules: 일정 목록을 반환한다")
    void getSchedules_success() throws Exception {
        ScheduleResponse response = new ScheduleResponse(1L, "팀 회의", "테스터", "업무");
        given(scheduleService.findAll()).willReturn(List.of(response));

        mockMvc.perform(get("/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("팀 회의"))
                .andExpect(jsonPath("$[0].memberName").value("테스터"))
                .andExpect(jsonPath("$[0].categoryName").value("업무"));
    }

    @Test
    @DisplayName("GET /schedules: 일정이 없으면 빈 목록을 반환한다")
    void getSchedules_emptyList() throws Exception {
        given(scheduleService.findAll()).willReturn(List.of());

        mockMvc.perform(get("/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /schedules: Service에서 예외가 발생하면 400과 에러 메시지를 반환한다")
    void getSchedules_serviceThrows_returnsBadRequest() throws Exception {
        given(scheduleService.findAll())
                .willThrow(new IllegalArgumentException("잘못된 요청입니다."));

        mockMvc.perform(get("/schedules"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    @DisplayName("GET /schedules/fetch: 일정 목록을 반환한다")
    void getSchedulesWithFetch_success() throws Exception {
        ScheduleResponse response = new ScheduleResponse(1L, "팀 회의", "테스터", "업무");
        given(scheduleService.findAllWithFetch()).willReturn(List.of(response));

        mockMvc.perform(get("/schedules/fetch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("팀 회의"));
    }

    @Test
    @DisplayName("GET /schedules/fetch: Service에서 예외가 발생하면 400과 에러 메시지를 반환한다")
    void getSchedulesWithFetch_serviceThrows_returnsBadRequest() throws Exception {
        given(scheduleService.findAllWithFetch())
                .willThrow(new IllegalArgumentException("잘못된 요청입니다."));

        mockMvc.perform(get("/schedules/fetch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    @DisplayName("GET /schedules/fetch: 일정이 없으면 빈 목록을 반환한다")
    void getSchedulesWithFetch_emptyList() throws Exception {
        given(scheduleService.findAllWithFetch()).willReturn(List.of());

        mockMvc.perform(get("/schedules/fetch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("POST /schedules: 정상 등록 시 생성된 id를 반환한다")
    void create_success() throws Exception {
        given(scheduleService.create(any())).willReturn(10L);

        String requestBody = """
                {
                  "title": "팀 회의",
                  "content": "주간 회의",
                  "startAt": "2026-08-26T10:00:00",
                  "endAt": "2026-08-26T11:00:00",
                  "memberId": 1,
                  "categoryId": 1
                }
                """;

        mockMvc.perform(post("/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string("10"));
    }

    @Test
    @DisplayName("POST /schedules: 시작 시간이 종료 시간보다 늦으면 400을 반환한다")
    void create_invalidPeriod_returnsBadRequest() throws Exception {
        given(scheduleService.create(any()))
                .willThrow(new IllegalArgumentException("시작 시간이 종료 시간보다 늦을 수 없습니다."));

        String requestBody = """
                {
                  "title": "팀 회의",
                  "content": "주간 회의",
                  "startAt": "2026-08-26T11:00:00",
                  "endAt": "2026-08-26T10:00:00",
                  "memberId": 1,
                  "categoryId": 1
                }
                """;

        mockMvc.perform(post("/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("시작 시간이 종료 시간보다 늦을 수 없습니다."));
    }

    @Test
    @DisplayName("PUT /schedules/{id}: 정상 수정 시 200을 반환한다")
    void update_success() throws Exception {
        String requestBody = """
                {
                  "title": "수정된 회의",
                  "content": "내용 수정",
                  "startAt": "2026-08-27T10:00:00",
                  "endAt": "2026-08-27T11:00:00",
                  "categoryId": 1
                }
                """;

        mockMvc.perform(put("/schedules/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        verify(scheduleService).update(eq(1L), any());
    }

    @Test
    @DisplayName("PUT /schedules/{id}: 존재하지 않는 id면 400을 반환한다")
    void update_notFound_returnsBadRequest() throws Exception {
        willThrow(new IllegalArgumentException("일정을 찾을 수 없습니다. id=999"))
                .given(scheduleService).update(eq(999L), any());

        String requestBody = """
                {
                  "title": "수정된 회의",
                  "content": "내용 수정",
                  "startAt": "2026-08-27T10:00:00",
                  "endAt": "2026-08-27T11:00:00",
                  "categoryId": 1
                }
                """;

        mockMvc.perform(put("/schedules/{id}", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("일정을 찾을 수 없습니다. id=999"));
    }

    @Test
    @DisplayName("DELETE /schedules/{id}: 정상 삭제 시 200을 반환한다")
    void delete_success() throws Exception {
        mockMvc.perform(delete("/schedules/{id}", 1L))
                .andExpect(status().isOk());

        verify(scheduleService).delete(1L);
    }

    @Test
    @DisplayName("DELETE /schedules/{id}: 존재하지 않는 id면 400을 반환한다")
    void delete_notFound_returnsBadRequest() throws Exception {
        willThrow(new IllegalArgumentException("일정을 찾을 수 없습니다. id=999"))
                .given(scheduleService).delete(999L);

        mockMvc.perform(delete("/schedules/{id}", 999L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("일정을 찾을 수 없습니다. id=999"));
    }
}
