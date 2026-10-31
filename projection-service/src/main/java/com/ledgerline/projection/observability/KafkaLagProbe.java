package com.ledgerline.projection.observability;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed read-only broker operations; no topic/group/offset mutation tool. */
@Component
@ConditionalOnProperty(name="reconciliation.enabled",havingValue="true")
public class KafkaLagProbe implements AutoCloseable {
    private final AdminClient admin;
    private final String topic,group;
    public KafkaLagProbe(@Value("${spring.kafka.bootstrap-servers}") String bootstrap,
        @Value("${projection.kafka.transfers-topic:ledger.transfers.v1}") String topic,
        @Value("${spring.kafka.consumer.group-id:ledgerline-projection}") String group) {
        this.topic=topic; this.group=group;
        admin=AdminClient.create(Map.of("bootstrap.servers",bootstrap,"request.timeout.ms","700","default.api.timeout.ms","900"));
    }
    public synchronized Map<String,Object> read() {
        try {
            var description=admin.describeTopics(List.of(topic)).allTopicNames().get(1,TimeUnit.SECONDS).get(topic);
            if(description.partitions().size()>32) throw new IllegalStateException("Lag scope exceeds bound");
            var requested=new HashMap<TopicPartition,OffsetSpec>();
            for(var p:description.partitions()) requested.put(new TopicPartition(topic,p.partition()),OffsetSpec.latest());
            var ends=admin.listOffsets(requested).all().get(1,TimeUnit.SECONDS);
            var committed=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(1,TimeUnit.SECONDS);
            long lag=0;
            for(var entry:ends.entrySet()) {
                var offset=committed.get(entry.getKey());
                lag=Math.addExact(lag,Math.max(0,entry.getValue().offset()-(offset==null?0:offset.offset())));
            }
            return Map.of("consumer_lag_known",1,"consumer_lag",lag);
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            var unknown=new LinkedHashMap<String,Object>();
            unknown.put("consumer_lag_known",0); unknown.put("consumer_lag",null);
            return unknown; // Unknown is never a fabricated zero-lag success.
        }
    }
    @PreDestroy @Override public void close() { admin.close(Duration.ofSeconds(1)); }
}
