package com.hengce.aps.service.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 排产策略服务：CRUD、克隆、导入导出与 8 条软约束权重校验。
 *
 * <p>权重契约（C19）：策略的优化规则必须恰好是 8 条白名单规则，权重 0..100 且合计 100；
 * 权重为 0 表示该规则在求解期关闭。规则语义白名单复用 {@link RuleCode}，避免策略与 DSL 两处口径漂移。</p>
 */
@Service
public class StrategyService {

    /**
     * 规则阶段：优化阶段。
     */
    public static final String PHASE_OPTIMIZATION = "OPTIMIZATION";

    /**
     * 规则类型：软约束。
     */
    public static final String TYPE_SOFT = "SOFT";

    /**
     * 权限方向白名单。
     */
    private static final Set<String> DIRECTIONS = Set.of("FORWARD", "BACKWARD", "BOTTLENECK");

    /**
     * 排序字段白名单（对外字段名到数据库列名）。
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "direction", "direction",
            "strategyVersion", "strategy_version",
            "effectiveFrom", "effective_from",
            "createdAt", "created_at");

    private static final String TOTAL_WEIGHT_MESSAGE = "8 条规则权重之和必须为 100";

    private final StrategyMapper strategyMapper;

    private final StrategyRuleMapper strategyRuleMapper;

    /**
     * 构造函数。
     *
     * @param strategyMapper 策略 Mapper
     * @param strategyRuleMapper 策略规则 Mapper
     */
    public StrategyService(StrategyMapper strategyMapper, StrategyRuleMapper strategyRuleMapper) {
        this.strategyMapper = strategyMapper;
        this.strategyRuleMapper = strategyRuleMapper;
    }

    /**
     * 分页查询策略。
     *
     * @param query 分页参数
     * @param name 名称关键字（模糊）
     * @param direction 排产方向（精确）
     * @return 分页结果
     */
    public PageResult<StrategyView> list(PageQuery query, String name, String direction) {
        PageQuery normalized = query == null ? new PageQuery() : query;
        normalized.normalize();
        LambdaQueryWrapper<Strategy> wrapper = Wrappers.lambdaQuery(Strategy.class);
        if (name != null && !name.isBlank()) {
            wrapper.like(Strategy::getName, name.trim());
        }
        if (direction != null && !direction.isBlank()) {
            wrapper.eq(Strategy::getDirection, direction.trim());
        }
        applySort(wrapper, normalized.getSort());
        Page<Strategy> page = new Page<>(normalized.getPage(), normalized.getSize());
        Page<Strategy> result = strategyMapper.selectPage(page, wrapper);
        List<UUID> strategyIds = new ArrayList<>();
        for (Strategy strategy : result.getRecords()) {
            strategyIds.add((UUID) strategy.getId());
        }
        Map<UUID, List<StrategyRule>> rulesByStrategy = findRulesByStrategyIds(strategyIds);
        List<StrategyView> items = new ArrayList<>();
        for (Strategy strategy : result.getRecords()) {
            UUID strategyId = (UUID) strategy.getId();
            items.add(toView(strategy, rulesByStrategy.getOrDefault(strategyId, List.of())));
        }
        return new PageResult<>(items, normalized.getPage(), normalized.getSize(), result.getTotal(), 0);
    }

    /**
     * 查询策略详情。
     *
     * @param id 策略主键
     * @return 策略视图
     */
    public StrategyView get(UUID id) {
        Strategy strategy = findOrThrow(id);
        return toView(strategy, findRules(id));
    }

    /**
     * 创建策略。
     *
     * @param command 创建命令
     * @return 创建后的策略视图
     */
    @Transactional
    public StrategyView create(StrategyCreateCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "策略名称不能为空");
        }
        List<StrategyRuleCommand> rules = normalizeRules(command.rules());
        Strategy strategy = new Strategy();
        strategy.setId(UUID.randomUUID());
        strategy.setName(command.name().trim());
        strategy.setDirection(normalizeDirection(command.direction()));
        strategy.setStrategyVersion(command.strategyVersion() == null ? 1 : command.strategyVersion());
        strategy.setEffectiveFrom(command.effectiveFrom() == null ? LocalDate.now() : command.effectiveFrom());
        strategy.setEffectiveTo(command.effectiveTo());
        validateEffectiveRange(strategy.getEffectiveFrom(), strategy.getEffectiveTo());
        strategy.setInitialRules(command.initialRules() == null ? Map.of() : command.initialRules());
        strategy.setApplyCondition(command.applyCondition() == null ? Map.of() : command.applyCondition());
        strategy.setOptimizationRules(weightSummary(rules));
        if (existsByNameAndVersion(strategy.getName(), strategy.getStrategyVersion(), null)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "同名策略版本已存在: " + strategy.getName() + " v" + strategy.getStrategyVersion());
        }
        try {
            strategyMapper.insert(strategy);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "同名策略版本已存在: " + strategy.getName() + " v" + strategy.getStrategyVersion());
        }
        insertRules((UUID) strategy.getId(), rules);
        return toView(strategy, findRules((UUID) strategy.getId()));
    }

    /**
     * 更新策略；版本不一致返回 409/40901。
     *
     * @param id 策略主键
     * @param command 更新命令
     * @return 更新后的策略视图
     */
    @Transactional
    public StrategyView update(UUID id, StrategyUpdateCommand command) {
        Strategy existing = findOrThrow(id);
        if (command == null || command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (!Objects.equals(existing.getVersion(), command.version())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "策略已被其他请求修改，请刷新后重试");
        }
        if (command.name() != null && !command.name().isBlank()) {
            existing.setName(command.name().trim());
        }
        if (command.direction() != null) {
            existing.setDirection(normalizeDirection(command.direction()));
        }
        if (command.effectiveFrom() != null) {
            existing.setEffectiveFrom(command.effectiveFrom());
        }
        if (command.effectiveTo() != null) {
            existing.setEffectiveTo(command.effectiveTo());
        }
        validateEffectiveRange(existing.getEffectiveFrom(), existing.getEffectiveTo());
        if (command.initialRules() != null) {
            existing.setInitialRules(command.initialRules());
        }
        if (command.applyCondition() != null) {
            existing.setApplyCondition(command.applyCondition());
        }
        if (existsByNameAndVersion(existing.getName(), existing.getStrategyVersion(), id)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "同名策略版本已存在: " + existing.getName() + " v" + existing.getStrategyVersion());
        }
        if (command.rules() == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "[42207] PUT 为全量替换，rules 不能为空（C19）");
        }
        List<StrategyRuleCommand> rules = normalizeRules(command.rules());
        existing.setOptimizationRules(weightSummary(rules));
        int rows = strategyMapper.updateById(existing);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "策略已被其他请求修改，请刷新后重试");
        }
        replaceRules(id, rules);
        Strategy latest = findOrThrow(id);
        return toView(latest, findRules(id));
    }

    /**
     * 逻辑删除策略及其规则。
     *
     * @param id 策略主键
     */
    @Transactional
    public void delete(UUID id) {
        Strategy existing = findOrThrow(id);
        for (StrategyRule rule : findRules(id)) {
            strategyRuleMapper.deleteById(rule.getId());
        }
        strategyMapper.deleteById(existing.getId());
    }

    /**
     * 克隆策略：生成同内容的新版本。
     *
     * @param id 源策略主键
     * @param command 克隆命令
     * @return 克隆后的策略视图
     */
    @Transactional
    public StrategyView clone(UUID id, StrategyCloneCommand command) {
        Strategy source = findOrThrow(id);
        List<StrategyRule> sourceRules = findRules(id);
        StrategyCloneCommand safeCommand = command == null
                ? new StrategyCloneCommand(null, null, null) : command;
        String name = safeCommand.name() == null || safeCommand.name().isBlank()
                ? source.getName() : safeCommand.name().trim();
        int nextVersion = safeCommand.strategyVersion() == null
                ? nextStrategyVersion(name) : safeCommand.strategyVersion();
        Strategy clone = new Strategy();
        clone.setId(UUID.randomUUID());
        clone.setName(name);
        clone.setDirection(source.getDirection());
        clone.setStrategyVersion(nextVersion);
        clone.setEffectiveFrom(safeCommand.effectiveFrom() == null
                ? source.getEffectiveFrom() : safeCommand.effectiveFrom());
        clone.setEffectiveTo(source.getEffectiveTo());
        clone.setInitialRules(copyMap(source.getInitialRules()));
        clone.setOptimizationRules(copyMap(source.getOptimizationRules()));
        clone.setApplyCondition(copyMap(source.getApplyCondition()));
        if (existsByNameAndVersion(name, nextVersion, null)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "同名策略版本已存在: " + name + " v" + nextVersion);
        }
        strategyMapper.insert(clone);
        UUID cloneId = (UUID) clone.getId();
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (StrategyRule rule : sourceRules) {
            rules.add(new StrategyRuleCommand(rule.getRuleCode(), rule.getWeight(),
                    copyMap(rule.getParams()), rule.getEnabled()));
        }
        if (!rules.isEmpty()) {
            replaceRules(cloneId, rules);
        }
        return toView(clone, findRules(cloneId));
    }

    /**
     * 导出策略为 JSON 载荷。
     *
     * @param id 策略主键
     * @return 导入导出载荷
     */
    public StrategyExportPayload export(UUID id) {
        Strategy strategy = findOrThrow(id);
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (StrategyRule rule : findRules(id)) {
            rules.add(new StrategyRuleCommand(rule.getRuleCode(), rule.getWeight(),
                    copyMap(rule.getParams()), rule.getEnabled()));
        }
        return new StrategyExportPayload(
                StrategyExportPayload.FORMAT_VERSION,
                strategy.getName(),
                strategy.getDirection(),
                strategy.getStrategyVersion(),
                strategy.getEffectiveFrom(),
                strategy.getEffectiveTo(),
                copyMap(strategy.getInitialRules()),
                copyMap(strategy.getApplyCondition()),
                rules);
    }

    /**
     * 导入策略；载荷版本与业务版本必须合法，规则按 8 条白名单校验。
     *
     * @param payload 导入载荷
     * @return 创建后的策略视图
     */
    @Transactional
    public StrategyView importStrategy(StrategyExportPayload payload) {
        if (payload == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导入载荷不能为空");
        }
        if (payload.formatVersion() != null
                && !StrategyExportPayload.FORMAT_VERSION.equals(payload.formatVersion())) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "不支持的导入格式版本: " + payload.formatVersion());
        }
        StrategyCreateCommand command = new StrategyCreateCommand(
                payload.name(),
                payload.direction(),
                payload.strategyVersion(),
                payload.effectiveFrom(),
                payload.effectiveTo(),
                payload.initialRules(),
                payload.applyCondition(),
                payload.rules());
        return create(command);
    }

    private Strategy findOrThrow(UUID id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "策略主键不能为空");
        }
        Strategy strategy = strategyMapper.selectById(id);
        if (strategy == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "策略不存在: " + id);
        }
        return strategy;
    }

    private List<StrategyRule> findRules(UUID strategyId) {
        return strategyRuleMapper.selectList(Wrappers.lambdaQuery(StrategyRule.class)
                .eq(StrategyRule::getStrategyId, strategyId)
                .orderByAsc(StrategyRule::getSeqNo));
    }

    /**
     * 批量查询规则，避免列表页 N+1 查询。
     *
     * @param strategyIds 策略主键集合
     * @return 策略主键到规则列表
     */
    private Map<UUID, List<StrategyRule>> findRulesByStrategyIds(List<UUID> strategyIds) {
        Map<UUID, List<StrategyRule>> grouped = new LinkedHashMap<>();
        if (strategyIds.isEmpty()) {
            return grouped;
        }
        List<StrategyRule> rules = strategyRuleMapper.selectList(Wrappers.lambdaQuery(StrategyRule.class)
                .in(StrategyRule::getStrategyId, strategyIds)
                .orderByAsc(StrategyRule::getSeqNo));
        for (StrategyRule rule : rules) {
            grouped.computeIfAbsent(rule.getStrategyId(), key -> new ArrayList<>()).add(rule);
        }
        return grouped;
    }

    private boolean existsByNameAndVersion(String name, Integer strategyVersion, UUID excludeId) {
        LambdaQueryWrapper<Strategy> wrapper = Wrappers.lambdaQuery(Strategy.class)
                .eq(Strategy::getName, name)
                .eq(Strategy::getStrategyVersion, strategyVersion);
        if (excludeId != null) {
            wrapper.ne(Strategy::getId, excludeId);
        }
        return strategyMapper.selectCount(wrapper) > 0;
    }

    private int nextStrategyVersion(String name) {
        List<Strategy> existing = strategyMapper.selectList(Wrappers.lambdaQuery(Strategy.class)
                .eq(Strategy::getName, name));
        int max = 0;
        for (Strategy strategy : existing) {
            max = Math.max(max, strategy.getStrategyVersion());
        }
        return max + 1;
    }

    private void insertRules(UUID strategyId, List<StrategyRuleCommand> rules) {
        int seqNo = 0;
        for (StrategyRuleCommand command : rules) {
            StrategyRule rule = new StrategyRule();
            rule.setId(UUID.randomUUID());
            rule.setStrategyId(strategyId);
            applyRule(rule, command, seqNo++);
            strategyRuleMapper.insert(rule);
        }
    }

    private void replaceRules(UUID strategyId, List<StrategyRuleCommand> commands) {
        Map<String, StrategyRule> existing = new LinkedHashMap<>();
        for (StrategyRule rule : findRules(strategyId)) {
            existing.put(rule.getRuleCode(), rule);
        }
        int seqNo = 0;
        Set<String> kept = new LinkedHashSet<>();
        for (StrategyRuleCommand command : commands) {
            StrategyRule rule = existing.get(command.code());
            boolean created = rule == null;
            if (created) {
                rule = new StrategyRule();
                rule.setId(UUID.randomUUID());
                rule.setStrategyId(strategyId);
            }
            applyRule(rule, command, seqNo++);
            if (created) {
                strategyRuleMapper.insert(rule);
            } else {
                strategyRuleMapper.updateById(rule);
            }
            kept.add(command.code());
        }
        for (StrategyRule rule : existing.values()) {
            if (!kept.contains(rule.getRuleCode())) {
                strategyRuleMapper.deleteById(rule.getId());
            }
        }
    }

    private void applyRule(StrategyRule rule, StrategyRuleCommand command, int seqNo) {
        rule.setRuleCode(command.code());
        RuleCode ruleCode = RuleCode.fromCode(command.code()).orElseThrow();
        rule.setName(ruleCode.getDisplayName());
        rule.setRulePhase(PHASE_OPTIMIZATION);
        rule.setRuleType(TYPE_SOFT);
        rule.setWeight(command.weight());
        rule.setParams(command.params() == null ? Map.of() : command.params());
        rule.setEnabled(command.enabled() == null
                ? command.weight().compareTo(BigDecimal.ZERO) > 0 : command.enabled());
        rule.setSeqNo(seqNo);
    }

    /**
     * 校验并归一化规则集合：必须 8 条、白名单编码、权重 0..100 且合计 100。
     *
     * @param rules 入参规则；为空时使用默认权重
     * @return 按白名单顺序排列的规则
     */
    List<StrategyRuleCommand> normalizeRules(List<StrategyRuleCommand> rules) {
        List<StrategyRuleCommand> source = rules == null || rules.isEmpty() ? defaultRules() : rules;
        if (source.size() != RuleCode.CORE_RULE_COUNT) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "[42207] 规则必须恰好 " + RuleCode.CORE_RULE_COUNT + " 条，实际为 " + source.size());
        }
        Map<String, StrategyRuleCommand> byCode = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (StrategyRuleCommand command : source) {
            if (command == null || command.code() == null) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "[42207] 规则编码不能为空");
            }
            RuleCode ruleCode = RuleCode.fromCode(command.code()).orElse(null);
            if (ruleCode == null) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "[42202] 未知规则编码: " + command.code());
            }
            if (byCode.containsKey(ruleCode.name())) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "[42206] 规则重复: " + ruleCode.name());
            }
            BigDecimal weight = command.weight();
            if (weight == null || weight.compareTo(BigDecimal.ZERO) < 0
                    || weight.compareTo(BigDecimal.valueOf(RuleCode.MAX_WEIGHT)) > 0) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "[42203] 规则权重必须是 0..100: " + ruleCode.name());
            }
            if (command.params() != null) {
                for (String key : command.params().keySet()) {
                    if (!ruleCode.getAllowedParams().contains(key)) {
                        throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                                "[42204] 规则 " + ruleCode.name() + " 不支持参数 " + key);
                    }
                }
            }
            BigDecimal scaled = weight.scale() <= 4 ? weight : weight.setScale(4, java.math.RoundingMode.HALF_UP);
            byCode.put(ruleCode.name(), new StrategyRuleCommand(ruleCode.name(), scaled,
                    command.params(), command.enabled()));
            total = total.add(scaled);
        }
        if (total.compareTo(BigDecimal.valueOf(RuleCode.TOTAL_WEIGHT)) != 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "[42203] " + TOTAL_WEIGHT_MESSAGE + "，实际为 " + total.stripTrailingZeros().toPlainString());
        }
        List<StrategyRuleCommand> ordered = new ArrayList<>();
        for (RuleCode ruleCode : RuleCode.values()) {
            ordered.add(byCode.get(ruleCode.name()));
        }
        ordered.sort(Comparator.comparingInt(command -> RuleCode.fromCode(command.code()).orElseThrow().ordinal()));
        return ordered;
    }

    private static List<StrategyRuleCommand> defaultRules() {
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (RuleCode ruleCode : RuleCode.values()) {
            rules.add(new StrategyRuleCommand(ruleCode.name(),
                    BigDecimal.valueOf(ruleCode.getDefaultWeight()), Map.of(), true));
        }
        return rules;
    }

    private static Map<String, Object> weightSummary(List<StrategyRuleCommand> rules) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (StrategyRuleCommand rule : rules) {
            summary.put(rule.code(), rule.weight());
        }
        return summary;
    }

    private static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return "FORWARD";
        }
        String normalized = direction.trim().toUpperCase(java.util.Locale.ROOT);
        if (!DIRECTIONS.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "排产方向必须是 FORWARD/BACKWARD/BOTTLENECK");
        }
        return normalized;
    }

    private static void validateEffectiveRange(LocalDate from, LocalDate to) {
        if (to != null && from != null && to.isBefore(from)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "失效日期不能早于生效日期");
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> copyMap(Map<String, V> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static void applySort(LambdaQueryWrapper<Strategy> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByDesc(Strategy::getCreatedAt);
            return;
        }
        String[] parts = sort.split(",");
        String column = SORT_COLUMNS.get(parts[0].trim());
        if (column == null) {
            wrapper.orderByDesc(Strategy::getCreatedAt);
            return;
        }
        boolean ascending = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim());
        wrapper.last("ORDER BY " + column + (ascending ? " ASC" : " DESC"));
    }

    private static StrategyView toView(Strategy strategy, List<StrategyRule> rules) {
        List<StrategyRuleView> ruleViews = new ArrayList<>();
        BigDecimal weightSum = BigDecimal.ZERO;
        for (StrategyRule rule : rules) {
            ruleViews.add(new StrategyRuleView(rule.getRuleCode(), rule.getName(), rule.getRulePhase(),
                    rule.getRuleType(), rule.getWeight(), copyMap(rule.getParams()),
                    rule.getEnabled(), rule.getSeqNo()));
            if (rule.getWeight() != null) {
                weightSum = weightSum.add(rule.getWeight());
            }
        }
        return new StrategyView((UUID) strategy.getId(), strategy.getName(), strategy.getDirection(),
                strategy.getStrategyVersion(), copyMap(strategy.getInitialRules()),
                copyMap(strategy.getOptimizationRules()), strategy.getEffectiveFrom(), strategy.getEffectiveTo(),
                copyMap(strategy.getApplyCondition()), ruleViews, ruleViews.size(), weightSum,
                strategy.getVersion(), strategy.getCreatedAt(), strategy.getUpdatedAt());
    }
}
