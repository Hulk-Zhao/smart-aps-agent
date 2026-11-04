package com.hengce.aps.service.schedule;

import java.time.Duration;
import java.time.Instant;

/**
 * 求解重试的可注入时钟（C37 §25.8.1）。
 *
 * <p>生产实现 {@link SystemSolveRetryClock}；单测注入假时钟，既提供
 * {@code metrics.retryAt[]} 的确定性时间戳，也替代 30 秒真实等待。</p>
 */
public interface SolveRetryClock {

    /**
     * 当前 UTC 时间，用于 {@code metrics.retryAt[]}。
     *
     * @return 当前时刻
     */
    Instant nowUtc();

    /**
     * 退避等待。
     *
     * @param delay 等待时长
     * @throws InterruptedException 等待被中断（取消/关闭）
     */
    void await(Duration delay) throws InterruptedException;
}
