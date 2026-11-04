package com.hengce.aps.solver.timefold.constraint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 8 条核心软约束的权重注册表（权重之和必须为 100，与策略规则 C19 一致）。
 *
 * <p>求解适配器（{@code TimefoldSolverAdapter}）由其他流维护，因此这里以进程内静态配置的方式
 * 暴露权重：求解编排在调用 {@code SolverEngine.solve} 前调用 {@link #activate(Map)}，
 * 求解结束后调用 {@link #disable()}。默认关闭，保证 M1 仅硬约束的既有行为不被改变。</p>
 */
public final class SoftConstraintWeights {

    /**
     * 权重合计强制值（C19）。
     */
    public static final int TOTAL_WEIGHT = 100;

    private static final Map<String, Integer> DEFAULTS;

    private static final AtomicReference<Map<String, Integer>> ACTIVE = new AtomicReference<>(Map.of());

    static {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put(ConstraintCodes.MIN_TARDINESS, 20);
        defaults.put(ConstraintCodes.EDD, 15);
        defaults.put(ConstraintCodes.SPT, 10);
        defaults.put(ConstraintCodes.SAME_PRODUCT_CONTINUOUS, 10);
        defaults.put(ConstraintCodes.MIN_SETUP_TIME, 10);
        defaults.put(ConstraintCodes.BALANCE_LOAD, 10);
        defaults.put(ConstraintCodes.PRIORITY_RESPECT, 15);
        defaults.put(ConstraintCodes.MIN_MAKESPAN, 10);
        DEFAULTS = Map.copyOf(defaults);
    }

    private SoftConstraintWeights() {
    }

    /**
     * 默认权重（8 条、合计 100）。
     *
     * @return 默认权重
     */
    public static Map<String, Integer> defaults() {
        return DEFAULTS;
    }

    /**
     * 当前生效的权重；空映射表示软约束关闭。
     *
     * @return 生效权重
     */
    public static Map<String, Integer> active() {
        return ACTIVE.get();
    }

    /**
     * 软约束是否启用。
     *
     * @return {@code true} 表示启用
     */
    public static boolean isEnabled() {
        return !ACTIVE.get().isEmpty();
    }

    /**
     * 激活权重；权重必须覆盖 8 条规则且合计为 {@link #TOTAL_WEIGHT}，
     * 权重为 0 的规则被视为关闭。
     *
     * @param weights 规则编码到权重
     * @throws IllegalArgumentException 权重集合非法
     */
    public static void activate(Map<String, Integer> weights) {
        Map<String, Integer> validated = validate(weights);
        ACTIVE.set(validated);
    }

    /**
     * 关闭软约束，恢复 M1 的仅硬约束行为。
     */
    public static void disable() {
        ACTIVE.set(Map.of());
    }

    /**
     * 当前某条规则的权重。
     *
     * @param code 规则编码
     * @return 权重；未启用时为 0
     */
    public static int weightOf(String code) {
        return ACTIVE.get().getOrDefault(code, 0);
    }

    /**
     * 校验并复制权重映射。
     *
     * @param weights 规则编码到权重
     * @return 不可变副本
     * @throws IllegalArgumentException 缺少规则、权重越界或合计不等于 100
     */
    public static Map<String, Integer> validate(Map<String, Integer> weights) {
        if (weights == null || weights.size() != ConstraintCodes.SOFT_RULE_CODES.size()) {
            throw new IllegalArgumentException("软约束权重必须覆盖 8 条核心规则");
        }
        Map<String, Integer> copy = new LinkedHashMap<>();
        int total = 0;
        for (String code : ConstraintCodes.SOFT_RULE_CODES) {
            Integer weight = weights.get(code);
            if (weight == null) {
                throw new IllegalArgumentException("缺少软约束权重: " + code);
            }
            if (weight < 0 || weight > TOTAL_WEIGHT) {
                throw new IllegalArgumentException("软约束权重越界: " + code + "=" + weight);
            }
            copy.put(code, weight);
            total += weight;
        }
        if (total != TOTAL_WEIGHT) {
            throw new IllegalArgumentException("软约束权重之和必须为 100，实际为 " + total);
        }
        return Map.copyOf(copy);
    }
}
