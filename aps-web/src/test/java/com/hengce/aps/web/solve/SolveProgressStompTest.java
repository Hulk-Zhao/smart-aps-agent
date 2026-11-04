package com.hengce.aps.web.solve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import com.hengce.aps.solver.api.task.SolveProgress;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * STOMP 实测：连接 {@code /ws/solve-tasks/{id}}，订阅 {@code /topic/solve-tasks/{id}}，
 * 验证订阅即收到快照、事件广播能收到增量消息。
 *
 * <p><b>确定性说明</b>：Spring 的 {@code StompSubProtocolHandler} 先把 SUBSCRIBE 提交给
 * {@code clientInboundChannel}（默认线程池异步派发），随后才同步发布 {@code SessionSubscribeEvent}；
 * 我们的快照是在该事件监听器里立即推给 {@code brokerChannel} 的。两条消息走不同执行器，
 * 快照可能先于“订阅注册”到达 broker，被静默丢弃——这正是本测试历史上偶发“10 秒收不到快照”的原因。
 * 本测试用同线程执行器接管入站派发，使“注册完成”严格先于“事件发布”，快照不再可能丢；
 * 并在测试开头断言该闸门确实生效，避免闸门失效后重新退化为抖动。</p>
 */
@SpringBootTest(classes = {SolveProgressStompTestApplication.class, SolveProgressStompTest.InlineInboundChannelConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SolveProgressStompTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolveProgressStompTest.class);

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    /**
     * 等待单帧的上限，仅作为失败兜底；断言条件是“帧出现”，不是“等够多少毫秒”。
     */
    private static final long FRAME_TIMEOUT_SECONDS = 10L;

    /**
     * 同线程执行器：投递即在当前线程执行，消除入站派发的调度不确定性。
     */
    private static final TaskExecutor INLINE_INBOUND_EXECUTOR = Runnable::run;

    @LocalServerPort
    private int port;

    @MockBean
    private SolveTaskLifecycleService lifecycleService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 把 STOMP 入站派发改成同线程执行，作为测试闸门（不改生产代码，仅本测试上下文生效）。
     *
     * <p>为什么用 Bean 定义后处理器而不是同名 {@code @Bean}：{@code @TestConfiguration} 的注册顺序
     * 早于 {@code @SpringBootTest} 主配置，同名覆盖会被框架定义再覆盖回来（实测仍是
     * {@code ThreadPoolTaskExecutor}）；后处理器在刷新期替换，与注册顺序无关。</p>
     */
    @TestConfiguration
    static class InlineInboundChannelConfiguration {

        /**
         * 在 Bean 定义阶段把 {@code clientInboundChannelExecutor} 换成同线程执行器。
         *
         * @return Bean 定义注册后处理器
         */
        @Bean
        static BeanDefinitionRegistryPostProcessor inlineInboundChannelExecutorPostProcessor() {
            return registry -> {
                if (registry.containsBeanDefinition("clientInboundChannelExecutor")) {
                    registry.removeBeanDefinition("clientInboundChannelExecutor");
                }
                registry.registerBeanDefinition("clientInboundChannelExecutor",
                        BeanDefinitionBuilder.genericBeanDefinition(TaskExecutor.class, () -> INLINE_INBOUND_EXECUTOR)
                                .getBeanDefinition());
            };
        }
    }

    @Test
    void subscriptionShouldReceiveSnapshotAndBroadcastProgress() throws Exception {
        assertSame(INLINE_INBOUND_EXECUTOR,
                applicationContext.getBean("clientInboundChannelExecutor", TaskExecutor.class),
                "测试闸门未生效：clientInboundChannel 仍是线程池派发，快照会与订阅注册竞态");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SolveTaskDetail detail = new SolveTaskDetail(1L, SANDBOX_ID, null, "TIMEFOLD", "RUNNING",
                0.35, "SOLVING", "0hard/-120soft", null, null, 1500L, null, null, null,
                false, Map.of(), now, null, now, now, List.of());
        when(lifecycleService.detail(1L)).thenReturn(detail);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws/solve-tasks/1",
                new StompSessionHandlerAdapter() { })
                .get(5, TimeUnit.SECONDS);

        LinkedBlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
        session.subscribe("/topic/solve-tasks/1", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @SuppressWarnings("unchecked")
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.add((Map<String, Object>) payload);
            }
        });

        Map<String, Object> snapshot = awaitFrame(frames, frame -> true, "订阅后应推送的进度快照");
        LOGGER.info("STOMP 快照消息体: {}", new ObjectMapper().writeValueAsString(snapshot));
        assertProgressKeys(snapshot, "快照");
        assertEquals(1, ((Number) snapshot.get("taskId")).intValue());
        assertEquals("RUNNING", snapshot.get("status"));
        assertEquals(0.35, ((Number) snapshot.get("progress")).doubleValue());
        assertEquals("SOLVING", snapshot.get("phase"));
        assertEquals("0hard/-120soft", snapshot.get("bestScore"));

        eventPublisher.publishEvent(new SolveProgress(1L, "SOLVED", 1.0, "DONE",
                "0hard/-80soft", 2600L, 1L, null, "求解完成"));

        Map<String, Object> terminal = awaitFrame(frames, frame -> "DONE".equals(frame.get("phase")),
                "phase=DONE 的终态帧");
        LOGGER.info("STOMP 增量消息体: {}", new ObjectMapper().writeValueAsString(terminal));
        assertProgressKeys(terminal, "增量");
        assertEquals("SOLVED", terminal.get("status"));
        assertEquals("DONE", terminal.get("phase"));
        assertEquals("0hard/-80soft", terminal.get("bestScore"));
        assertEquals(1, ((Number) terminal.get("resultTaskId")).intValue());
        assertEquals(2600L, ((Number) terminal.get("elapsedMs")).longValue());

        session.disconnect();
    }

    /**
     * C20 契约：可空键恒存在，值可为 {@code null}；不以“键缺失”表达语义。
     *
     * @param frame 进度帧
     * @param what 帧来源描述
     */
    private static void assertProgressKeys(Map<String, Object> frame, String what) {
        for (String key : List.of("bestScore", "resultTaskId", "errorCode", "message")) {
            assertTrue(frame.containsKey(key), what + "必须显式输出 " + key + " 键，实帧=" + frame);
        }
    }

    /**
     * 条件等待：等到满足条件的帧出现即返回，超时才判红；不依赖固定等待时长。
     *
     * @param frames 帧队列
     * @param condition 目标帧条件
     * @param what 目标帧描述，用于失败信息
     * @return 满足条件的帧
     * @throws InterruptedException 线程中断
     */
    private static Map<String, Object> awaitFrame(LinkedBlockingQueue<Map<String, Object>> frames,
                                                  Predicate<Map<String, Object>> condition,
                                                  String what) throws InterruptedException {
        List<Map<String, Object>> received = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FRAME_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            long remainMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            Map<String, Object> frame = frames.poll(remainMs, TimeUnit.MILLISECONDS);
            if (frame == null) {
                break;
            }
            received.add(frame);
            if (condition.test(frame)) {
                return frame;
            }
        }
        return fail("在 " + FRAME_TIMEOUT_SECONDS + "s 内未等到" + what + "，已收到帧=" + received);
    }
}
