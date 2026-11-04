package com.hengce.aps.service.strategy;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.strategy.Strategy;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyMapper;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyRule;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyRuleMapper;
import com.hengce.aps.solver.dsl.RuleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略服务测试：8 条规则权重契约、CRUD 闭环、克隆与导入导出、409/422 拦截。
 */
@ExtendWith(MockitoExtension.class)
class StrategyServiceTest {

    @Mock
    private StrategyMapper strategyMapper;

    @Mock
    private StrategyRuleMapper strategyRuleMapper;

    private StrategyService strategyService;

    @BeforeEach
    void setUp() {
        strategyService = new StrategyService(strategyMapper, strategyRuleMapper);
    }

    @Test
    void createWithOmittedRulesUsesEightDefaultWeights() {
        stubNoDuplicateName();
        when(strategyRuleMapper.selectList(any())).thenReturn(List.of());

        StrategyView view = strategyService.create(createCommand(null));
        ArgumentCaptor<StrategyRule> captor = ArgumentCaptor.forClass(StrategyRule.class);
        verify(strategyRuleMapper, times(8)).insert(captor.capture());

        List<StrategyRule> inserted = captor.getAllValues();
        assertEquals(8, inserted.size());
        BigDecimal total = inserted.stream().map(StrategyRule::getWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(BigDecimal.valueOf(RuleCode.TOTAL_WEIGHT), total);
        for (StrategyRule rule : inserted) {
            assertEquals("OPTIMIZATION", rule.getRulePhase());
            assertEquals("SOFT", rule.getRuleType());
        }
        assertNotNull(view.id());
        assertEquals("STRATEGY-A", view.name());
    }

    @Test
    void createRejectsWeightSumNotHundred() {
        List<StrategyRuleCommand> rules = defaultRuleCommands();
        rules.set(0, new StrategyRuleCommand(RuleCode.MIN_TARDINESS.name(), BigDecimal.valueOf(30), Map.of(), true));

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.create(createCommand(rules)));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("[42203]"), exception.getMessage());
        verify(strategyMapper, never()).insert(any(Strategy.class));
    }

    @Test
    void createRejectsUnknownRuleCode() {
        List<StrategyRuleCommand> rules = defaultRuleCommands();
        rules.set(1, new StrategyRuleCommand("NOT_A_RULE", BigDecimal.valueOf(15), Map.of(), true));

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.create(createCommand(rules)));

        assertTrue(exception.getMessage().contains("[42202]"), exception.getMessage());
    }

    @Test
    void createRejectsDuplicateStrategyVersion() {
        when(strategyMapper.selectCount(any())).thenReturn(1L);

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.create(createCommand(null)));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    @Test
    void updateRequiresFullRuleReplacement() {
        UUID id = UUID.randomUUID();
        when(strategyMapper.selectById(id)).thenReturn(existingStrategy(id));

        BizException exception = assertThrows(BizException.class, () -> strategyService.update(id,
                new StrategyUpdateCommand(1, null, null, null, null, null, null, null)));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("[42207]"));
        verify(strategyMapper, never()).updateById(any(Strategy.class));
    }

    @Test
    void updateRejectsStaleVersion() {
        UUID id = UUID.randomUUID();
        when(strategyMapper.selectById(id)).thenReturn(existingStrategy(id));

        BizException exception = assertThrows(BizException.class, () -> strategyService.update(id,
                new StrategyUpdateCommand(9, "STRATEGY-A", null, null, null, null, null,
                        defaultRuleCommands())));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    @Test
    void updateReplacesRulesInPlace() {
        UUID id = UUID.randomUUID();
        Strategy existing = existingStrategy(id);
        Strategy latest = existingStrategy(id);
        latest.setVersion(2);
        when(strategyMapper.selectById(id)).thenReturn(existing, latest);
        when(strategyMapper.updateById(any(Strategy.class))).thenReturn(1);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id));

        StrategyView view = strategyService.update(id, new StrategyUpdateCommand(1, null, null, null,
                null, null, null, defaultRuleCommands()));

        verify(strategyMapper).updateById(existing);
        verify(strategyRuleMapper, times(8)).updateById(any(StrategyRule.class));
        verify(strategyRuleMapper, never()).deleteById(any(UUID.class));
        assertEquals(8, view.ruleCount());
        assertEquals(BigDecimal.valueOf(100), view.weightSum());
    }

    @Test
    void cloneCopiesAllRulesAndBumpsVersion() {
        UUID id = UUID.randomUUID();
        Strategy source = existingStrategy(id);
        source.setName("STRATEGY-A");
        when(strategyMapper.selectById(id)).thenReturn(source);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id), List.of(), List.of());
        when(strategyMapper.selectList(any())).thenReturn(List.of(source));
        when(strategyMapper.selectCount(any())).thenReturn(0L);

        StrategyView view = strategyService.clone(id, new StrategyCloneCommand(null, null, null));

        assertEquals(2, view.strategyVersion());
        ArgumentCaptor<Strategy> captor = ArgumentCaptor.forClass(Strategy.class);
        verify(strategyMapper).insert(captor.capture());
        assertEquals("STRATEGY-A", captor.getValue().getName());
        verify(strategyRuleMapper, times(8)).insert(any(StrategyRule.class));
    }

    @Test
    void exportThenImportProducesEquivalentRules() {
        UUID id = UUID.randomUUID();
        Strategy source = existingStrategy(id);
        when(strategyMapper.selectById(id)).thenReturn(source);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id));

        StrategyExportPayload payload = strategyService.export(id);
        assertEquals(StrategyExportPayload.FORMAT_VERSION, payload.formatVersion());
        assertEquals(8, payload.rules().size());

        stubNoDuplicateName();
        when(strategyRuleMapper.selectList(any())).thenReturn(List.of());
        strategyService.importStrategy(payload);

        ArgumentCaptor<StrategyRule> captor = ArgumentCaptor.forClass(StrategyRule.class);
        verify(strategyRuleMapper, times(8)).insert(captor.capture());
        BigDecimal total = captor.getAllValues().stream().map(StrategyRule::getWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(BigDecimal.valueOf(100), total);
    }

    @Test
    void listCarriesRulesAndWeightSummary() {
        Page<Strategy> page = new Page<>(1, 20);
        Strategy strategy = existingStrategy(UUID.randomUUID());
        page.setRecords(List.of(strategy));
        page.setTotal(1);
        when(strategyMapper.selectPage(any(), any())).thenReturn(page);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules((UUID) strategy.getId()));

        PageResult<StrategyView> result = strategyService.list(new PageQuery(), null, null);

        assertEquals(1, result.items().size());
        StrategyView view = result.items().get(0);
        assertEquals(8, view.ruleCount());
        assertEquals(BigDecimal.valueOf(100), view.weightSum());
        assertEquals(8, view.rules().size());
        assertEquals("MIN_TARDINESS", view.rules().get(0).code());
        assertEquals("OPTIMIZATION", view.rules().get(0).phase());
        assertEquals("SOFT", view.rules().get(0).type());
    }

    private void stubNoDuplicateName() {
        when(strategyMapper.selectCount(any())).thenReturn(0L);
    }

    private static StrategyCreateCommand createCommand(List<StrategyRuleCommand> rules) {
        return new StrategyCreateCommand("STRATEGY-A", "FORWARD", 1, LocalDate.of(2026, 1, 1), null,
                Map.of(), Map.of(), rules);
    }

    private static Strategy existingStrategy(UUID id) {
        Strategy strategy = new Strategy();
        strategy.setId(id);
        strategy.setName("STRATEGY-A");
        strategy.setDirection("FORWARD");
        strategy.setStrategyVersion(1);
        strategy.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        strategy.setInitialRules(Map.of());
        strategy.setOptimizationRules(Map.of());
        strategy.setApplyCondition(Map.of());
        strategy.setVersion(1);
        return strategy;
    }

    private static List<StrategyRule> existingRules(UUID strategyId) {
        List<StrategyRule> rules = new ArrayList<>();
        int seqNo = 0;
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            StrategyRule rule = new StrategyRule();
            rule.setId(UUID.randomUUID());
            rule.setStrategyId(strategyId);
            rule.setRuleCode(entry.getKey());
            rule.setName(entry.getKey());
            rule.setRulePhase("OPTIMIZATION");
            rule.setRuleType("SOFT");
            rule.setWeight(BigDecimal.valueOf(entry.getValue()));
            rule.setParams(Map.of());
            rule.setEnabled(true);
            rule.setSeqNo(seqNo++);
            rule.setVersion(1);
            rules.add(rule);
        }
        return rules;
    }

    private static List<StrategyRuleCommand> defaultRuleCommands() {
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            rules.add(new StrategyRuleCommand(entry.getKey(), BigDecimal.valueOf(entry.getValue()),
                    Map.of(), true));
        }
        return rules;
    }
}
