package com.ledgerline.projection;

import com.ledgerline.projection.consumer.ProjectionQuarantine;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.*;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectionRecoveryIntegrationTest {
    static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    static {
        db.start(); kafka.start();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers",kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic("p1-fixture",1,(short)1))).all().get();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.kafka.consumer.group-id", () -> "p1-recovery");
        r.add("spring.kafka.listener.auto-startup", () -> "true");
        r.add("projection.kafka.transfers-topic", () -> "p1-fixture");
    }
    @Autowired JdbcClient jdbc;
    @Autowired ProjectionQuarantine quarantine;

    @Test @Order(1) void poisonIsDurableBeforeOffsetsAdvanceAndHealthyTrafficRecovers() throws Exception {
        try (var producer = producer(); var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            var tp = new TopicPartition("p1-fixture",0);
            await().atMost(Duration.ofSeconds(60)).until(() -> listeners.getListenerContainers().stream()
                .anyMatch(c -> c.getAssignedPartitions()!=null && c.getAssignedPartitions().contains(tp)));
            org.apache.kafka.clients.producer.RecordMetadata poison;
            jdbc.sql("ALTER TABLE projection_quarantine RENAME TO blocked_quarantine").update();
            try {
                poison = producer.send(new ProducerRecord<String,String>("p1-fixture",0,"bad","{malformed" )).get();
                await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
                    var offset=admin.listConsumerGroupOffsets("p1-recovery").partitionsToOffsetAndMetadata().get().get(tp);
                    assertTrue(offset==null || offset.offset()<=poison.offset(),"Parking failure must not acknowledge poison");
                });
            } finally { jdbc.sql("ALTER TABLE blocked_quarantine RENAME TO projection_quarantine").update(); }
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertEquals(1L, quarantine.pending());
                var committed = admin.listConsumerGroupOffsets("p1-recovery").partitionsToOffsetAndMetadata().get().get(tp);
                assertNotNull(committed);
                assertTrue(committed.offset() > poison.offset());
            });
            assertEquals("{malformed", jdbc.sql("SELECT payload FROM projection_quarantine").query(String.class).single());
            UUID event = UUID.randomUUID();
            String payload = "{\"eventId\":\""+event+"\",\"transactionId\":\""+UUID.randomUUID()+"\",\"fromAccountId\":\""+UUID.randomUUID()+"\",\"toAccountId\":\""+UUID.randomUUID()+"\",\"amountMinor\":7,\"currency\":\"USD\",\"timestamp\":\"2026-10-30T00:00:00Z\"}";
            var good = producer.send(new ProducerRecord<String,String>("p1-fixture",0,"ok",payload)).get();
            producer.send(new ProducerRecord<String,String>("p1-fixture",0,"duplicate",payload)).get();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertEquals(1L, jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id=?").param(event).query(Long.class).single());
                assertEquals(2L,jdbc.sql("SELECT count(*) FROM statement_view").query(Long.class).single());
                assertEquals(0L,jdbc.sql("SELECT sum(amount_minor) FROM statement_view").query(Long.class).single());
                assertTrue(admin.listConsumerGroupOffsets("p1-recovery").partitionsToOffsetAndMetadata().get().get(tp).offset() > good.offset()+1);
            });
            assertEquals(1L,quarantine.pending(),"Healthy traffic never resolves a parked obligation");
        }
    }
    @Test @Order(2) void unavailableQuarantineCannotPretendToRecoverAndRetryIsIdempotent() {
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("p1-direct",0,9,"key","hostile fake-token: do not execute");
        jdbc.sql("ALTER TABLE projection_quarantine RENAME TO unavailable_quarantine").update();
        try { assertThrows(org.springframework.dao.DataAccessException.class, () -> quarantine.park(record,new RuntimeException())); }
        finally { jdbc.sql("ALTER TABLE unavailable_quarantine RENAME TO projection_quarantine").update(); }
        quarantine.park(record,new RuntimeException());
        quarantine.park(record,new RuntimeException());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM projection_quarantine WHERE topic='p1-direct'").query(Long.class).single());
        jdbc.sql("DELETE FROM projection_quarantine WHERE topic='p1-direct'").update();
    }
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;
    @Test @Order(3) void listenerDrainsAndStopsWithinDeclaredBudget() throws Exception {
        var stopped = new java.util.concurrent.CountDownLatch(1);
        listeners.stop(stopped::countDown);
        assertTrue(stopped.await(15,java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(listeners.isRunning());
        assertEquals(1L,quarantine.pending());
        assertEquals(2L,jdbc.sql("SELECT count(*) FROM statement_view").query(Long.class).single());
    }
    @Test @Order(4) void quiescedBackupRestoresAllProjectionAndQuarantineFingerprints() throws Exception {
        assertFalse(listeners.isRunning());
        assertEquals(0, db.execInContainer("pg_dump","-U",db.getUsername(),"-d",db.getDatabaseName(),"-f","/tmp/p1.sql").getExitCode());
        assertEquals(0, db.execInContainer("createdb","-U",db.getUsername(),"p1_restore").getExitCode());
        assertEquals(0, db.execInContainer("psql","-U",db.getUsername(),"-d","p1_restore","-v","ON_ERROR_STOP=1","-f","/tmp/p1.sql").getExitCode());
        for (String table : List.of("processed_events","statement_view","daily_account_summary","projection_quarantine")) {
            String sql="SELECT coalesce(md5(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text)),'empty') FROM "+table+" t";
            var original=db.execInContainer("psql","-U",db.getUsername(),"-d",db.getDatabaseName(),"-Atc",sql);
            var restored=db.execInContainer("psql","-U",db.getUsername(),"-d","p1_restore","-Atc",sql);
            assertEquals(0,original.getExitCode()); assertEquals(0,restored.getExitCode());
            assertEquals(original.getStdout(),restored.getStdout(),table);
        }
    }
    @Test @Order(5) void readOnlyLagProbeReportsOffsetsAndDependencyUnknownWithoutCommitting() throws Exception {
        try(var probe=new com.ledgerline.projection.observability.KafkaLagProbe(kafka.getBootstrapServers(),"p1-fixture","p1-recovery");
            var admin=AdminClient.create(Map.of("bootstrap.servers",kafka.getBootstrapServers()))) {
            var before=admin.listConsumerGroupOffsets("p1-recovery").partitionsToOffsetAndMetadata().get();
            assertEquals(1,probe.read().get("consumer_lag_known"));
            assertEquals(0L,probe.read().get("consumer_lag"));
            assertEquals(before,admin.listConsumerGroupOffsets("p1-recovery").partitionsToOffsetAndMetadata().get());
            var docker=org.testcontainers.DockerClientFactory.instance().client();
            docker.pauseContainerCmd(kafka.getContainerId()).exec();
            try {
                var unknown=probe.read(); assertEquals(0,unknown.get("consumer_lag_known")); assertNull(unknown.get("consumer_lag"));
            } finally { docker.unpauseContainerCmd(kafka.getContainerId()).exec(); }
        }
    }
    private KafkaProducer<String,String> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class));
    }
}
