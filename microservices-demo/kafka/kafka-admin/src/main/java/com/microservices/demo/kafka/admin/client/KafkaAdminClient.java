package com.microservices.demo.kafka.admin.client;

import com.microservices.demo.config.KafkaConfigData;
import com.microservices.demo.config.RetryConfigData;
import com.microservices.demo.kafka.admin.exception.KafkaClientException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Component
public class KafkaAdminClient {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaAdminClient.class);
    private static final int HTTP_SERVICE_UNAVAILABLE_CODE = 503;
    private static final int HTTP_OK_CODE = 200;

    private final KafkaConfigData kafkaConfigData;
    private final RetryConfigData retryConfigData;
    private final AdminClient adminClient;
    private final RetryTemplate retryTemplate;
    private final HttpClient httpClient;

    public KafkaAdminClient(KafkaConfigData kafkaConfigData, RetryConfigData retryConfigData, AdminClient adminClient,
                            RetryTemplate retryTemplate, HttpClient httpClient) {
        this.kafkaConfigData = kafkaConfigData;
        this.retryConfigData = retryConfigData;
        this.adminClient = adminClient;
        this.retryTemplate = retryTemplate;
        this.httpClient = httpClient;
    }

    public void createTopics() {
        try {
            // Versión simplificada de la expresión "retryContext -> doCreateTopics(retryContext)"
            CreateTopicsResult createTopicsResult = retryTemplate.execute(this::doCreateTopics);
        }
        catch (Throwable t) {
            throw new KafkaClientException("Reached max number of retry for creating kafka topic(s)", t);
        }
        checkTopicsCreated();
    }

    public void checkTopicsCreated() {
        Collection<TopicListing> topics = getTopics();
        int retryCount = 1;
        Integer maxRetry = retryConfigData.getMaxAttempts();
        int multiplier = retryConfigData.getMultiplier().intValue();
        Long sleepTimeMs = retryConfigData.getSleepTimeMs();
        for(String topic: kafkaConfigData.getTopicNamesToCreate()) {
            while(!isTopicCreated(topics, topic)) {
                checkMaxRetry(retryCount++, maxRetry);
                sleep(sleepTimeMs);
                sleepTimeMs *= multiplier;
                topics = getTopics();
            }
        }
    }

    public void checkSchemaRegistry() {
        int retryCount = 1;
        Integer maxRetry = retryConfigData.getMaxAttempts();
        int multiplier = retryConfigData.getMultiplier().intValue();
        Long sleepTimeMs = retryConfigData.getSleepTimeMs();
        while(getSchemaRegistryStatus() != HTTP_OK_CODE) {
            checkMaxRetry(retryCount++, maxRetry);
            sleep(sleepTimeMs);
            sleepTimeMs *= multiplier;
        }
    }

    private Integer getSchemaRegistryStatus() {
        try {
            return httpClient
                    .get()
                    .uri(kafkaConfigData.getSchemaRegistryUrl())
                    .responseSingle((response, bytes) -> Mono.just(response.status().code()))
                    .block();
        }
        catch(Exception e) {
            return HTTP_SERVICE_UNAVAILABLE_CODE;
        }
    }

    private void sleep(Long sleepTimeMs) {
        try {
            Thread.sleep(sleepTimeMs);
        }
        catch(InterruptedException e) {
            throw new KafkaClientException("Error while sleeping for waiting new created topic(s)!!");
        }
    }

    private void checkMaxRetry(int retry, int maxRetry) {
        if(retry > maxRetry)
            throw new KafkaClientException("Reached max number of retry for reading kafka topic(s)");
    }

    private boolean isTopicCreated(Collection<TopicListing> topics, String topicName) {
        if(topics == null)
            return false;
        return topics.stream().anyMatch(topic -> topic.name().equals(topicName));
    }

    private CreateTopicsResult doCreateTopics(RetryContext retryContext) {
        List<String> topicNames = kafkaConfigData.getTopicNamesToCreate();
        LOG.info("Creating {} topic(s), attempts {}", topicNames.size(), retryContext.getRetryCount());
        List<NewTopic> kafkaTopics = topicNames.stream().map(topicName -> new NewTopic(topicName.trim(),
                kafkaConfigData.getNumOfPartitions(),
                kafkaConfigData.getReplicationFactor())
        ).collect(Collectors.toList());
        return adminClient.createTopics(kafkaTopics);
    }

    private Collection<TopicListing> getTopics() {
        Collection<TopicListing> topics;
        try {
            // Versión simplificada de la expresión "retryContext -> doGetTopics(retryContext)"
            topics = retryTemplate.execute(this::doGetTopics);
        }
        catch(Throwable t) {
            throw new KafkaClientException("Reached max number of retry for reading kafka topic(s)", t);
        }
        return topics;
    }

    private Collection<TopicListing> doGetTopics(RetryContext retryContext) throws ExecutionException, InterruptedException {
        LOG.info("Reading Kafka topic {}, attempts {}", kafkaConfigData.getTopicNamesToCreate(), retryContext.getRetryCount());
        Collection<TopicListing> topics = adminClient.listTopics().listings().get();
        if(topics != null)
            topics.forEach(topic -> LOG.debug("Topic with name {}", topic.name()));
        return topics;
    }
}
