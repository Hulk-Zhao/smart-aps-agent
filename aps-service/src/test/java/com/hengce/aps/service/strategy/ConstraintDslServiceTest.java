package com.hengce.aps.service.strategy;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.strategy.ConstraintDsl;
import com.hengce.aps.infrastructure.persistence.strategy.ConstraintDslMapper;
import com.hengce.aps.solver.dsl.ConstraintDslDocument;
import com.hengce.aps.solver.dsl.ConstraintDslSerializer;
import com.hengce.aps.solver.dsl.DslValidationReport;
import com.hengce.aps.solver.dsl.RuleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 约束 DSL 服务测试：422xx 拦截、规范化数组、升版、激活与激活失败自动回滚。
 */
@ExtendWith(MockitoExtension.class)
class ConstraintDslServiceTest {

    @Mock
    private ConstraintDslMapper constraintDslMapper;

    private ConstraintDslService constraintDslService;

    @BeforeEach
    void setUp() {
        constraintDslService = new ConstraintDslService(constraintDslMapper, true);
    }

    @Test
    void validateReturnsStructured422xxReport() {
        String yaml = compliantYaml().replace("ruleCode: EDD", "ruleCode: UNKNOWN_RULE");

        DslValidationReport report = constraintDslService.validate(yaml);

        assertFalse(report.valid());
        assertEquals("42202", report.primaryCode());
        assertTrue(report.issues().size() >= 1);
        assertTrue(report.issues().stream().allMatch(issue -> issue.code().startsWith("422")));
    }

    @Test
    void createRejectsInvalidDslWith42200AndIssueDetails() {
        BizException exception = assertThrows(BizException.class, () -> constraintDslService.create(
                new ConstraintDslCreateCommand("dsl-default", compatibleSingularYaml())));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("[42207]"), exception.getMessage());
        verify(constraintDslMapper, times(0)).insert(any(ConstraintDsl.class));
    }

    @Test
    void createNormalizesToArrayAndStoresDraftVersion() {
        when(constraintDslMapper.selectMaxVersionNo("dsl-default")).thenReturn(2);

        ConstraintDslView view = constraintDslService.create(
                new ConstraintDslCreateCommand("dsl-default", compliantYaml()));

        ArgumentCaptor<ConstraintDsl> captor = ArgumentCaptor.forClass(ConstraintDsl.class);
        verify(constraintDslMapper).insert(captor.capture());
        ConstraintDsl entity = captor.getValue();
        assertEquals(3, entity.getVersionNo());
        assertEquals(ConstraintDslStatus.DRAFT.name(), entity.getStatus());
        assertTrue(entity.getYamlText().contains("constraints:"), entity.getYamlText());
        assertFalse(entity.getYamlText().contains("\nconstraint:"), entity.getYamlText());
        assertEquals(64, entity.getChecksum().length());
        assertEquals(8, entity.getCompiledSpec().size());
        assertEquals(8, view.compiledSpec().size());
        assertEquals(3, view.versionNo());
    }

    @Test
    void activateDeactivatesPreviousVersionAndActivatesTarget() {
        UUID targetId = UUID.randomUUID();
        ConstraintDsl target = dsl(targetId, ConstraintDslStatus.DRAFT.name(), 2);
        ConstraintDsl previous = dsl(UUID.randomUUID(), ConstraintDslStatus.ACTIVE.name(), 1);
        when(constraintDslMapper.selectById(targetId)).thenReturn(target);
        when(constraintDslMapper.selectActiveByCode("dsl-default")).thenReturn(previous);
        when(constraintDslMapper.updateById(any(ConstraintDsl.class))).thenReturn(1);

        ConstraintDslView view = constraintDslService.activate(targetId);

        assertEquals(ConstraintDslStatus.ROLLBACK.name(), previous.getStatus());
        assertEquals(ConstraintDslStatus.ACTIVE.name(), target.getStatus());
        assertEquals(ConstraintDslStatus.ACTIVE.name(), view.status());
        verify(constraintDslMapper, times(2)).updateById(any(ConstraintDsl.class));
    }

    @Test
    void activationFailureRestoresPreviousActiveVersion() {
        UUID targetId = UUID.randomUUID();
        ConstraintDsl target = dsl(targetId, ConstraintDslStatus.DRAFT.name(), 2);
        ConstraintDsl previous = dsl(UUID.randomUUID(), ConstraintDslStatus.ACTIVE.name(), 1);
        when(constraintDslMapper.selectById(targetId)).thenReturn(target);
        when(constraintDslMapper.selectActiveByCode("dsl-default")).thenReturn(previous);
        when(constraintDslMapper.updateById(any(ConstraintDsl.class))).thenReturn(1, 0, 1);

        BizException exception = assertThrows(BizException.class,
                () -> constraintDslService.activate(targetId));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("已回滚"), exception.getMessage());
        assertTrue(exception.getMessage().contains("v1"), exception.getMessage());
        assertEquals(ConstraintDslStatus.ACTIVE.name(), previous.getStatus(),
                "激活失败后必须恢复上一激活版本");
    }

    @Test
    void rollbackActivatesHistoricalVersionAndRecordsSource() {
        UUID targetId = UUID.randomUUID();
        ConstraintDsl target = dsl(targetId, ConstraintDslStatus.ROLLBACK.name(), 1);
        ConstraintDsl current = dsl(UUID.randomUUID(), ConstraintDslStatus.ACTIVE.name(), 2);
        when(constraintDslMapper.selectById(targetId)).thenReturn(target);
        when(constraintDslMapper.selectActiveByCode("dsl-default")).thenReturn(current);
        when(constraintDslMapper.updateById(any(ConstraintDsl.class))).thenReturn(1);

        ConstraintDslView view = constraintDslService.rollback(targetId);

        assertEquals(ConstraintDslStatus.ACTIVE.name(), view.status());
        assertEquals((UUID) current.getId(), view.rollbackOf());
        assertEquals(ConstraintDslStatus.ROLLBACK.name(), current.getStatus());
    }

    @Test
    void rollbackRejectsAlreadyActiveVersion() {
        UUID targetId = UUID.randomUUID();
        ConstraintDsl target = dsl(targetId, ConstraintDslStatus.ACTIVE.name(), 1);
        when(constraintDslMapper.selectById(targetId)).thenReturn(target);

        BizException exception = assertThrows(BizException.class,
                () -> constraintDslService.rollback(targetId));
        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("无需回滚"), exception.getMessage());
    }

    private static ConstraintDsl dsl(UUID id, String status, int versionNo) {
        ConstraintDsl entity = new ConstraintDsl();
        entity.setId(id);
        entity.setCode("dsl-default");
        entity.setVersionNo(versionNo);
        entity.setStatus(status);
        entity.setYamlText(compliantYaml());
        entity.setChecksum("checksum");
        entity.setCompiledSpec(List.of(Map.of("code", RuleCode.MIN_TARDINESS.name())));
        entity.setVersion(1);
        return entity;
    }

    /**
     * 生成 8 条规则、权重合计 100 的合规 DSL。
     *
     * @return YAML 文本
     */
    static String compliantYaml() {
        return new ConstraintDslSerializer().serialize(ConstraintDslDocument.standard());
    }

    private static String compatibleSingularYaml() {
        return "version: 1\nconstraint:\n  code: MIN_TARDINESS\n  weight: 20\n";
    }
}
