package com.hengce.aps.web.solve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.service.schedule.SolveTaskLifecycleService;
import com.hengce.aps.solver.api.task.SolveProgress;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
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
 */
@SpringBootTest(classes = SolveProgressStompTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SolveProgressStompTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolveProgressStompTest.class);

    private static final UUID SANDBOX_ID = UUID.randomUUID();

    @LocalServerPort
    private int port;

    @MockBean
    private SolveTaskLifecycleService lifecycleService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Test
    void subscriptionShouldReceiveSnapshotAndBroadcastProgress() throws Exception {
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

        @SuppressWarnings("unchecked")
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

        Map<String, Object> snapshot = frames.poll(10, TimeUnit.SECONDS);
        assertNotNull(snapshot, "订阅后应立即收到快照");
        LOGGER.info("STOMP 快照消息体: {}",
                new ObjectMapper().writeValueAsString(snapshot));
        assertEquals(1, ((Number) snapshot.get("taskId")).intValue());
        assertEquals("RUNNING", snapshot.get("status"));
        assertEquals(0.35, ((Number) snapshot.get("progress")).doubleValue());
        assertEquals("SOLVING", snapshot.get("phase"));
        assertEquals("0hard/-120soft", snapshot.get("bestScore"));
        assertTrue(snapshot.containsKey("errorCode"), "快照必须显式输出 errorCode 键");
        assertTrue(snapshot.containsKey("message"), "快照必须显式输出 message 键");

        eventPublisher.publishEvent(new SolveProgress(1L, "SOLVED", 1.0, "DONE",
                "0hard/-80soft", 2600L, 1L, null, "求解完成"));
        Map<String, Object> update = frames.poll(5, TimeUnit.SECONDS);
        assertNotNull(update, "应收到增量进度");
        LOGGER.info("STOMP 增量消息体: {}",
                new ObjectMapper().writeValueAsString(update));
        assertEquals("SOLVED", update.get("status"));
        assertEquals("DONE", update.get("phase"));
        assertEquals("0hard/-80soft", update.get("bestScore"));
        assertEquals(1, ((Number) update.get("resultTaskId")).intValue());
        assertTrue(((Number) update.get("elapsedMs")).longValue() > 0L);
        assertTrue(update.containsKey("errorCode"), "增量消息必须显式输出 errorCode 键");
        assertTrue(update.containsKey("message"), "增量消息必须显式输出 message 键");

        session.disconnect();
    }
}
