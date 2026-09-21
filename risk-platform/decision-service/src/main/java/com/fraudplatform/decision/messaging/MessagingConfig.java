package com.fraudplatform.decision.messaging;

import com.fraudplatform.commons.events.EventType;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonLoggingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Topics, retry and dead-letter policy.
 *
 * <ul>
 *   <li>3 partitions per business topic locally (production: sized from throughput and consumer
 *       parallelism); the config topic has 1 partition so configuration changes are totally ordered.</li>
 *   <li>Each consumer group has its own container factory and DLT: {@code <topic>.<group>.dlt}.
 *       Retries: exponential back-off 200 → 400 → 800 ms (3 retries), then the DLT, with the original
 *       topic/partition/offset and the exception in Kafka headers.</li>
 *   <li>Non-retryable failures (bad payload, permanent downstream 4xx) go to the DLT immediately.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
public class MessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);
    public static final String CASE_CREATOR = "case-creator";
    public static final String LABEL_INGESTOR = "label-ingestor";

    @Bean
    KafkaAdmin.NewTopics topics(@Value("${platform.messaging.partitions:3}") int partitions,
                                @Value("${platform.messaging.replication:1}") short replication) {
        return new KafkaAdmin.NewTopics(
                topic(EventType.Topics.TRANSACTIONS, partitions, replication),
                topic(EventType.Topics.DECISIONS, partitions, replication),
                topic(EventType.Topics.CASES, partitions, replication),
                topic(EventType.Topics.LABELS, partitions, replication),
                topic(EventType.Topics.CONFIG, 1, replication),
                topic(EventType.Topics.deadLetter(EventType.Topics.DECISIONS, CASE_CREATOR), 1, replication),
                topic(EventType.Topics.deadLetter(EventType.Topics.LABELS, LABEL_INGESTOR), 1, replication));
    }

    private static NewTopic topic(String name, int partitions, short replication) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replication)
                .config("retention.ms", String.valueOf(7L * 24 * 3600 * 1000)).build();
    }

    private static ConcurrentKafkaListenerContainerFactory<String, String> factory(
            ConsumerFactory<String, String> cf, KafkaTemplate<String, String> template, String group) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            log.error("DLT group={} topic={} partition={} offset={} cause={}", group, record.topic(), record.partition(),
                    record.offset(), ex.getCause() == null ? ex.toString() : ex.getCause().toString());
            return new TopicPartition(EventType.Topics.deadLetter(record.topic(), group), -1);
        });
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxAttempts(3);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NonRetryableEventException.class, DeserializationException.class,
                tools.jackson.core.JacksonException.class);
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(cf);
        f.setCommonErrorHandler(handler);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        f.setConcurrency(1);
        return f;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> caseCreatorFactory(ConsumerFactory<String, String> cf,
                                                                              KafkaTemplate<String, String> template) {
        return factory(cf, template, CASE_CREATOR);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> labelIngestorFactory(ConsumerFactory<String, String> cf,
                                                                                KafkaTemplate<String, String> template) {
        return factory(cf, template, LABEL_INGESTOR);
    }

    /** Config refresh is best-effort (the scheduled refresh is the safety net): log and move on, no DLT. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> broadcastFactory(ConsumerFactory<String, String> cf) {
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(cf);
        f.setCommonErrorHandler(new CommonLoggingErrorHandler());
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return f;
    }
}
