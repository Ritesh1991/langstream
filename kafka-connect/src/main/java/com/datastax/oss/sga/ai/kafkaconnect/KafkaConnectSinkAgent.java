package com.datastax.oss.sga.ai.kafkaconnect;

import com.dastastax.oss.sga.kafka.runner.KafkaConsumerRecord;
import com.datastax.oss.sga.api.runner.code.AgentContext;
import com.datastax.oss.sga.api.runner.code.AgentSink;
import com.datastax.oss.sga.api.runner.code.Record;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.confluent.connect.avro.AvroData;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.generic.GenericData;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkConnector;
import org.apache.kafka.connect.sink.SinkConnectorContext;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * This is an implementation of {@link AgentSink} that allows you to run any Kafka Connect Sinks.
 * It is a special implementation because it bypasses the SGA Agent APIs and uses directly the
 * Kafka Consumer. This is needed in order to implement correctly the APIs.
 * It is not expected that this Sink runs together with a custom Source or a Processor,
 * it works only if the Source is directly a Kafka Consumer Source.
 */
@Slf4j
public class KafkaConnectSinkAgent implements AgentSink {

    private static class SgaSinkRecord extends SinkRecord {

        final int estimatedSize;
        public SgaSinkRecord(String topic, int partition,
                             Schema keySchema, Object key,
                             Schema valueSchema, Object value,
                             long kafkaOffset, Long timestamp,
                             TimestampType timestampType, int estimatedSize) {
            super(topic, partition, keySchema, key, valueSchema, value, kafkaOffset,
                    timestamp, timestampType);
            this.estimatedSize = estimatedSize;
        }
    }

    private String kafkaConnectorFQClassName;
    @VisibleForTesting
    KafkaConnectSinkTaskContext taskContext;
    private SinkConnector connector;
    private SinkTask task;

    private long maxBatchSize;
    private final AtomicLong currentBatchSize = new AtomicLong(0L);

    private long lingerMs;
    private final ScheduledExecutorService scheduledExecutor =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                    .setNameFormat("kafka-adaptor-sink-flush-%d")
                    .build());
    protected final ConcurrentLinkedDeque<KafkaConsumerRecord> pendingFlushQueue = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean isFlushRunning = new AtomicBoolean(false);
    private volatile boolean isRunning = false;

    private Map<String, String> kafkaSinkConfig;
    private Map<String, String> adapterConfig;

    private Consumer<?, ?> consumer;

    private AgentContext context;

    private final AvroData avroData = new AvroData(1000);
    private final ConcurrentLinkedDeque<ConsumerCommand> consumerCqrsQueue = new ConcurrentLinkedDeque<>();

    // has to be the same consumer as used to read records to process,
    // otherwise pause/resume won't work
    @Override
    public void setContext(AgentContext context) throws Exception {
        this.context = context;
    }

    protected void submitCommand(ConsumerCommand cmd) {
        consumerCqrsQueue.add(cmd);
    }

    @Override
    public boolean handlesCommit() {
        return true;
    }

    @Override
    public void write(List<Record> records) {
        if (!isRunning) {
            log.warn("Sink is stopped. Cannot send the records");
            throw new IllegalStateException("Sink is stopped. Cannot send the records");
        }
        try {
            Collection<SinkRecord> sinkRecords = records.stream()
                    .map(this::toSinkRecord)
                    .collect(Collectors.toList());
            task.put(sinkRecords);

            records.stream()
                    .map(x -> {
                        KafkaConsumerRecord kr = KafkaConnectSinkAgent.getKafkaRecord(x);
                        currentBatchSize.addAndGet(kr.estimateRecordSize());
                        taskContext.updateOffset(kr.getTopicPartition(),
                                kr.offset());
                        return kr;
                    })
                    .forEach(pendingFlushQueue::add);

        } catch (Exception ex) {
            log.error("Error sending the records {}", records, ex);
            this.close();
            throw new IllegalStateException("Error sending the records", ex);
        }
        flushIfNeeded(false);
    }

    private static int getRecordSize(KafkaConsumerRecord r) {
        return r.estimateRecordSize();
    }

    private SgaSinkRecord toSinkRecord(Record record) {
        KafkaConsumerRecord kr = getKafkaRecord(record);

        return new SgaSinkRecord(kr.origin(),
                kr.partition(),
                toKafkaSchema(kr.key(), kr.keySchema()),
                toKafkaData(kr.key(), kr.keySchema()),
                toKafkaSchema(kr.value(), kr.valueSchema()),
                toKafkaData(kr.value(), kr.valueSchema()),
                kr.offset(),
                kr.timestamp(),
                kr.timestampType(),
                kr.estimateRecordSize());
    }

    private Schema toKafkaSchema(Object input, Schema schema) {
        if (input instanceof GenericData.Record rec && schema == null) {
            return avroData.toConnectSchema(rec.getSchema());
        }
        return schema;
    }

    private Object toKafkaData(Object input, Schema schema) {
        if (input instanceof GenericData.Record rec) {
            if (schema == null) {
                return avroData.toConnectData(rec.getSchema(), rec);
            } else {
                return avroData.toConnectData(avroData.fromConnectSchema(schema), rec);
            }
        }
        return input;
    }

    private static KafkaConsumerRecord getKafkaRecord(Record record) {
        KafkaConsumerRecord kr;
        if (record instanceof KafkaConsumerRecord) {
            kr = (KafkaConsumerRecord) record;
        } else {
            throw new IllegalArgumentException("Record is not a KafkaRecord");
        }
        return kr;
    }

    private void flushIfNeeded(boolean force) {
        if (isFlushRunning.get()) {
            return;
        }
        if (force || currentBatchSize.get() >= maxBatchSize) {
            scheduledExecutor.submit(this::flush);
        }
    }

    // flush always happens on the same thread
    public void flush() {
        if (log.isDebugEnabled()) {
            log.debug("flush requested, pending: {}, batchSize: {}",
                    currentBatchSize.get(), maxBatchSize);
        }

        if (pendingFlushQueue.isEmpty()) {
            return;
        }

        if (!isFlushRunning.compareAndSet(false, true)) {
            return;
        }

        final KafkaConsumerRecord lastNotFlushed = pendingFlushQueue.getLast();
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = null;
        try {
            Map<TopicPartition, OffsetAndMetadata> currentOffsets = taskContext.currentOffsets();
            committedOffsets = task.preCommit(currentOffsets);
            if (committedOffsets == null || committedOffsets.isEmpty()) {
                log.info("Task returned empty committedOffsets map; skipping flush; task will retry later");
                return;
            }
            if (log.isDebugEnabled() && !areMapsEqual(committedOffsets, currentOffsets)) {
                log.debug("committedOffsets {} differ from currentOffsets {}", committedOffsets, currentOffsets);
            }

            submitCommand(new ConsumerCommand(ConsumerCommand.Command.COMMIT, committedOffsets));
            cleanUpFlushQueueAndUpdateBatchSize(lastNotFlushed, committedOffsets);
            log.info("Flush succeeded");
        } catch (Throwable t) {
            log.error("error flushing pending records", t);
            submitCommand(new ConsumerCommand(ConsumerCommand.Command.THROW,
                    new IllegalStateException("Error flushing pending records", t)));
            this.close();
        } finally {
            isFlushRunning.compareAndSet(true, false);
        }
    }

    // must be called from the same thread as the rest of teh consumer calls
    @Override
    public void commit() throws Exception {

        // can pause create deadlock for the runner?
        while (!consumerCqrsQueue.isEmpty()) {
            ConsumerCommand cmd = consumerCqrsQueue.poll();
            if (cmd == null) {
                break;
            }
            if (log.isDebugEnabled()) {
                log.debug("Executing command {}, ag: {}", cmd.command(), cmd.arg());
            }
            switch (cmd.command()) {
                case COMMIT -> {
                    Map<TopicPartition, OffsetAndMetadata> offsets = (Map<TopicPartition, OffsetAndMetadata>)cmd.arg();
                    consumer.commitSync(offsets);
                }
                case PAUSE -> {
                    List<TopicPartition> partitions = (List<TopicPartition>) cmd.arg();
                    consumer.pause(partitions);
                }
                case RESUME -> {
                    List<TopicPartition> partitions = (List<TopicPartition>) cmd.arg();
                    consumer.resume(partitions);
                }
                case SEEK -> {
                    AbstractMap.SimpleEntry<TopicPartition, Long> arg =
                            (AbstractMap.SimpleEntry<TopicPartition, Long>) cmd.arg();
                    consumer.seek(arg.getKey(), arg.getValue());
                    taskContext.updateOffset(arg.getKey(), arg.getValue());
                }
                case REPARTITION -> {
                    Collection<TopicPartition> partitions = (Collection<TopicPartition>) cmd.arg();
                    task.open(partitions);
                }
                case THROW -> {
                    Exception ex = (Exception) cmd.arg();
                    log.error("Exception throw requested", ex);
                    throw ex;
                }
            }
        }

    }

    @VisibleForTesting
    protected void cleanUpFlushQueueAndUpdateBatchSize(KafkaConsumerRecord lastNotFlushed,
                                                       Map<TopicPartition, OffsetAndMetadata> committedOffsets) {
        // lastNotFlushed is needed in case of default preCommit() implementation
        // which calls flush() and returns currentOffsets passed to it.
        // We don't want to ack messages added to pendingFlushQueue after the preCommit/flush call

        for (KafkaConsumerRecord r : pendingFlushQueue) {
            OffsetAndMetadata lastCommittedOffset = committedOffsets.get(r.getTopicPartition());

            if (lastCommittedOffset == null) {
                if (r == lastNotFlushed) {
                    break;
                }
                continue;
            }

            if (r.offset() > lastCommittedOffset.offset()) {
                if (r == lastNotFlushed) {
                    break;
                }
                continue;
            }

            pendingFlushQueue.remove(r);
            currentBatchSize.addAndGet(-1 * getRecordSize(r));
            if (r == lastNotFlushed) {
                break;
            }
        }
    }
    private static boolean areMapsEqual(Map<TopicPartition, OffsetAndMetadata> first,
                                        Map<TopicPartition, OffsetAndMetadata> second) {
        if (first.size() != second.size()) {
            return false;
        }

        return first.entrySet().stream()
                .allMatch(e -> e.getValue().equals(second.get(e.getKey())));
    }

    @Override
    public void init(Map<String, Object> config) {
        if (isRunning) {
            log.warn("Agent already started {} / {}", this.getClass(), kafkaConnectorFQClassName);
            return;
        }
        config = new HashMap<>(config);

        adapterConfig = (Map<String, String>)config.remove("adapterConfig");
        if (adapterConfig == null) {
            adapterConfig = new HashMap<>();
        }

        kafkaSinkConfig = (Map) config;

        kafkaConnectorFQClassName = (String) config.get("connector.class");
        Objects.requireNonNull(kafkaConnectorFQClassName, "Kafka connector sink class is not set (connector.class)");

        log.info("Kafka sink started : \n\t{}\n\t{}", kafkaSinkConfig, adapterConfig);
    }

    @SneakyThrows
    @Override
    public void start() {
        if (isRunning) {
            log.warn("Agent already started {} / {}", this.getClass(), kafkaConnectorFQClassName);
            return;
        }
        this.consumer = (Consumer<?, ?>) context.getTopicConsumer().getNativeConsumer();
        log.info("Getting consumer from context {}", consumer);
        Objects.requireNonNull(consumer);

        Class<?> clazz = Class.forName(kafkaConnectorFQClassName, true, Thread.currentThread().getContextClassLoader());
        connector = (SinkConnector) clazz.getConstructor().newInstance();

        Class<? extends Task> taskClass = connector.taskClass();

        SinkConnectorContext cnCtx = new SinkConnectorContext() {
            @Override
            public void requestTaskReconfiguration() {
                throw new UnsupportedOperationException("requestTaskReconfiguration is not supported");
            }

            @Override
            public void raiseError(Exception e) {
                throw new UnsupportedOperationException("raiseError is not supported", e);
            }
        };

        connector.initialize(cnCtx);
        connector.start(kafkaSinkConfig);

        List<Map<String, String>> configs = connector.taskConfigs(1);
        Preconditions.checkNotNull(configs);
        Preconditions.checkArgument(configs.size() == 1);

        // configs may contain immutable/unmodifiable maps
        configs = configs.stream()
                .map(HashMap::new)
                .collect(Collectors.toList());

        task = (SinkTask) taskClass.getConstructor().newInstance();
        taskContext = new KafkaConnectSinkTaskContext(configs.get(0),
                this::submitCommand,
                () -> KafkaConnectSinkAgent.this.flushIfNeeded(true));
        task.initialize(taskContext);
        task.start(configs.get(0));

        maxBatchSize = Long.parseLong(adapterConfig.getOrDefault("batchSize", "16384"));
        // kafka's default is 2147483647L but that's too big for normal cases
        lingerMs = Long.parseLong(adapterConfig.getOrDefault("lingerTimeMs", "60000"));

        scheduledExecutor.scheduleWithFixedDelay(() ->
                this.flushIfNeeded(true), lingerMs, lingerMs, TimeUnit.MILLISECONDS);
        isRunning = true;

        log.info("Kafka sink started : \n\t{}\n\t{}", kafkaSinkConfig, adapterConfig);
    }

    @Override
    public void close() {
        if (!isRunning) {
            log.warn("Agent already stopped {} / {}", this.getClass(), kafkaConnectorFQClassName);
            return;
        }

        isRunning = false;
        flushIfNeeded(true);
        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(10 * lingerMs, TimeUnit.MILLISECONDS)) {
                log.error("scheduledExecutor did not terminate in {} ms", 10 * lingerMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("scheduledExecutor's shutdown was interrupted", e);
        }

        try {
            if (task != null) {
                task.stop();
            }
        } catch (Throwable t) {
            log.error("Error stopping the task", t);
        }
        try {
            if (connector != null) {
                connector.stop();
            }
        } catch (Throwable t) {
            log.error("Error stopping the connector", t);
        }

        log.info("Kafka sink stopped.");
    }

    @Override
    public void setCommitCallback(CommitCallback callback) {
        // useless
    }
}