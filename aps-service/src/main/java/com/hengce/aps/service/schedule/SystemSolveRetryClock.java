package com.hengce.aps.service.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 生产用重试时钟：UTC 系统时间 + 线程休眠退避。
 */
@Component
public class SystemSolveRetryClock implements SolveRetryClock {

    /**
     * 时间源。
     */
    private final Clock clock;

    /**
     * 使用系统 UTC 时钟构造。
     */
    public SystemSolveRetryClock() {
        this(Clock.systemUTC());
    }

    /**
     * 指定时间源构造（测试或定制部署）。
     *
     * @param clock 时间源
     */
    public SystemSolveRetryClock(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Instant nowUtc() {
        return clock.instant();
    }

    @Override
    public void await(Duration delay) throws InterruptedException {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        Thread.sleep(delay.toMillis());
    }
}
