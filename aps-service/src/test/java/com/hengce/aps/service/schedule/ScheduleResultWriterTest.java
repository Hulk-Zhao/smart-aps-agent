package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository.NewTaskRow;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.ScoreFact;
import com.hengce.aps.solver.api.SolveMetrics;
import com.hengce.aps.solver.api.TaskAssignment;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 结果落库单测：资源内顺序 1..n、setup 窗口口径、事务内清理与归因 taskIds 回填。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleResultWriterTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @InjectMocks
    private ScheduleResultWriter writer;

    @Test
    void persistShouldSortByStartTimeAndAssignContinuousSequence() {
        UUID tenantId = UUID.randomUUID();
        UUID sandboxId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.parse("2026-11-04T02:00:00Z");
        List<TaskAssignment> assignments = List.of(
                assignment(orderId, "SO-1", 30, resourceId,
                        base.plusHours(2), base.plusHours(2).plusMinutes(5)),
                assignment(orderId, "SO-1", 10, resourceId, base, base.plusMinutes(30)),
                assignment(orderId, "SO-1", 20, resourceId,
                        base.plusHours(1), base.plusHours(1).plusMinutes(10)));
        when(scheduleRepository.insertTask(any())).thenReturn(101L, 102L, 103L);
        when(scheduleRepository.deleteTasks(any(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<NewTaskRow> captor = ArgumentCaptor.forClass(NewTaskRow.class);

        writer.persist(assembled(tenantId, sandboxId, orderId), solution(assignments), 7L);

        verify(scheduleRepository, org.mockito.Mockito.times(3)).insertTask(captor.capture());
        List<NewTaskRow> rows = captor.getAllValues();
        assertEquals(3, rows.size());
        assertEquals(List.of(1, 2, 3),
                rows.stream().map(NewTaskRow::sequenceInResource).toList());
        assertEquals(List.of(10, 20, 30),
                rows.stream().map(NewTaskRow::operationIndex).toList());
        for (NewTaskRow row : rows) {
            assertEquals(row.startTime(), row.setupEndTime());
            assertTrue(row.endTime().isAfter(row.startTime()));
        }
        assertEquals(1800,
                rows.get(0).setupEndTime().toEpochSecond() - rows.get(0).setupStartTime().toEpochSecond());
    }

    @Test
    void persistShouldBackfillJustificationTaskIds() {
        UUID tenantId = UUID.randomUUID();
        UUID sandboxId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.parse("2026-11-04T02:00:00Z");
        List<TaskAssignment> assignments = List.of(
                assignment(orderId, "SO-1", 10, resourceId, base, base.plusHours(1)),
                assignment(orderId, "SO-1", 20, resourceId, base.plusHours(1), base.plusHours(2)));
        when(scheduleRepository.insertTask(any())).thenReturn(11L, 12L);
        when(scheduleRepository.deleteTasks(any(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<List<Long>> idCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);

        writer.persist(assembled(tenantId, sandboxId, orderId), solution(assignments), 7L);

        verify(scheduleRepository, org.mockito.Mockito.times(2))
                .insertJustification(any(), org.mockito.ArgumentMatchers.anyLong(), codeCaptor.capture(),
                        any(), idCaptor.capture(), any());
        for (List<Long> taskIds : idCaptor.getAllValues()) {
            assertFalse(taskIds.isEmpty());
        }
        assertTrue(codeCaptor.getAllValues().contains(ScheduleResultWriter.CODE_RESOURCE_NO_OVERLAP));
        assertTrue(codeCaptor.getAllValues().contains(ScheduleResultWriter.CODE_OPERATION_DEPENDENCY));
    }

    private static ScheduleProblemAssembler.AssembledProblem assembled(UUID tenantId, UUID sandboxId,
                                                                        UUID orderId) {
        return new ScheduleProblemAssembler.AssembledProblem(tenantId, sandboxId, null, List.of(orderId));
    }

    private static ScheduleSolution solution(List<TaskAssignment> assignments) {
        return new ScheduleSolution("SOLVED",
                new ScoreFact(BigDecimal.ZERO, BigDecimal.ZERO, "0hard/0soft"),
                assignments,
                List.of(),
                new SolveMetrics(0, 0, 0, "TIME_LIMIT", List.of(), assignments.size(), Map.of()),
                "ok");
    }

    private static TaskAssignment assignment(UUID orderId, String orderNo, int operationIndex,
                                              UUID resourceId, OffsetDateTime setupStart,
                                              OffsetDateTime start) {
        return new TaskAssignment(null, orderId, orderNo, operationIndex, null, UUID.randomUUID(),
                resourceId, "R-1", List.of(), setupStart, start, start, start.plusHours(1),
                BigDecimal.TEN, null, "PLANNED", false, 1);
    }
}
