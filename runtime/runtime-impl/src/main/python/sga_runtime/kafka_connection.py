import logging
from typing import List, Dict

from confluent_kafka import Consumer, Producer, Message, TopicPartition
from confluent_kafka.serialization import StringDeserializer

from .api import Sink, Record, Source, CommitCallback
from .kafka_serialization import STRING_SERIALIZER, DOUBLE_SERIALIZER, LONG_SERIALIZER, \
    BOOLEAN_SERIALIZER
from .simplerecord import SimpleRecord

STRING_DESERIALIZER = StringDeserializer()


def apply_default_configuration(streaming_cluster, configs):
    if 'admin' in streaming_cluster['configuration']:
        configs.update(streaming_cluster['configuration']['admin'])


def create_source(agent_id, streaming_cluster, configuration):
    configs = configuration.copy()
    apply_default_configuration(streaming_cluster, configs)
    configs['enable.auto.commit'] = 'false'
    if 'group.id' not in configs:
        configs['group.id'] = 'sga-' + agent_id
    if 'auto.offset.reset' not in configs:
        configs['auto.offset.reset'] = 'earliest'
    if 'key.deserializer' not in configs:
        configs['key.deserializer'] = 'org.apache.kafka.common.serialization.StringDeserializer'
    if 'value.deserializer' not in configs:
        configs['value.deserializer'] = 'org.apache.kafka.common.serialization.StringDeserializer'
    return KafkaSource(configs)


def create_sink(_, streaming_cluster, configuration):
    configs = configuration.copy()
    apply_default_configuration(streaming_cluster, configs)
    if 'key.serializer' not in configs:
        configs['key.serializer'] = 'org.apache.kafka.common.serialization.StringSerializer'
    if 'value.serializer' not in configs:
        configs['value.serializer'] = 'org.apache.kafka.common.serialization.StringSerializer'
    return KafkaSink(configs)


class KafkaRecord(SimpleRecord):
    def __init__(self, message: Message):
        super().__init__(
            STRING_DESERIALIZER(message.value()),
            key=STRING_DESERIALIZER(message.key()),
            origin=message.topic(),
            timestamp=message.timestamp()[1],
            headers=message.headers())
        self._message: Message = message
        self._topic_partition: TopicPartition = TopicPartition(message.topic(), message.partition())

    def topic_partition(self) -> TopicPartition:
        return self._topic_partition

    def offset(self):
        return self._message.offset()


class KafkaSource(Source):
    def __init__(self, configs):
        self.configs = configs.copy()
        self.topic = self.configs.pop('topic')
        self.key_deserializer = self.configs.pop('key.deserializer')
        self.value_deserializer = self.configs.pop('value.deserializer')
        self.consumer: Consumer = None
        self.committed: Dict[TopicPartition, int] = {}

    def start(self):
        self.consumer = Consumer(self.configs)
        self.consumer.subscribe([self.topic])

    def close(self):
        if self.consumer:
            self.consumer.close()

    def read(self) -> List[KafkaRecord]:
        message = self.consumer.poll(1.0)
        if message is None:
            return []
        if message.error():
            logging.error(f"Consumer error: {message.error()}")
            return []
        logging.info(f"Received message from Kafka {message}")
        return [KafkaRecord(message)]

    def commit(self, records: List[KafkaRecord]):
        for record in records:
            topic_partition = record.topic_partition()
            offset = record.offset()
            logging.info(f"Committing offset {offset} on partition {topic_partition} (record: {record})")
            if topic_partition in self.committed and offset != self.committed[topic_partition] + 1:
                raise RuntimeError(f'There is an hole in the commit sequence for partition {record}')
            self.committed[topic_partition] = offset
        offsets = [TopicPartition(topic_partition.topic, partition=topic_partition.partition, offset=offset)
                   for topic_partition, offset in self.committed.items()]
        self.consumer.commit(offsets=offsets, asynchronous=True)


class KafkaSink(Sink):
    def __init__(self, configs):
        self.configs = configs.copy()
        self.topic = self.configs.pop('topic')
        self.key_serializer = self.configs.pop('key.serializer')
        self.value_serializer = self.configs.pop('value.serializer')
        self.producer = None
        self.commit_callback: CommitCallback = None

    def start(self):
        self.producer = Producer(self.configs)

    def set_commit_callback(self, commit_callback: CommitCallback):
        self.commit_callback = commit_callback

    def write(self, records: List[Record]):
        for record in records:
            # TODO: handle send errors
            logging.info(f"Sending record {record}")
            headers = []
            if record.headers():
                for key, value in record.headers():
                    if type(value) == bytes:
                        headers.append((key, value))
                    elif type(value) == str:
                        headers.append((key, STRING_SERIALIZER(value)))
                    elif type(value) == float:
                        headers.append((key, DOUBLE_SERIALIZER(value)))
                    elif type(value) == int:
                        headers.append((key, LONG_SERIALIZER(value)))
                    elif type(value) == bool:
                        headers.append((key, BOOLEAN_SERIALIZER(value)))
                    else:
                        raise ValueError(f'Unsupported header type {type(value)} for header {(key, value)}')
            self.producer.produce(
                self.topic,
                value=STRING_SERIALIZER(record.value()),
                key=STRING_SERIALIZER(record.key()),
                headers=headers)
        self.producer.flush()
        self.commit_callback.commit(records)