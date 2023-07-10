package com.datastax.oss.sga.pulsar;

import com.datastax.oss.sga.api.model.AgentConfiguration;
import com.datastax.oss.sga.api.model.ApplicationInstance;
import com.datastax.oss.sga.api.model.Module;
import com.datastax.oss.sga.api.model.Pipeline;
import com.datastax.oss.sga.api.model.SchemaDefinition;
import com.datastax.oss.sga.api.model.StreamingCluster;
import com.datastax.oss.sga.api.model.TopicDefinition;
import com.datastax.oss.sga.api.runtime.AgentImplementation;
import com.datastax.oss.sga.api.runtime.AgentImplementationProvider;
import com.datastax.oss.sga.api.runtime.ClusterRuntime;
import com.datastax.oss.sga.api.runtime.PhysicalApplicationInstance;
import com.datastax.oss.sga.api.runtime.PluginsRegistry;
import com.datastax.oss.sga.api.runtime.StreamingClusterRuntime;
import com.datastax.oss.sga.api.runtime.TopicImplementation;
import com.datastax.oss.sga.impl.common.AbstractAgentProvider;
import com.datastax.oss.sga.pulsar.agents.AbstractPulsarFunctionAgentProvider;
import com.datastax.oss.sga.pulsar.agents.AbstractPulsarSinkAgentProvider;
import com.datastax.oss.sga.pulsar.agents.AbstractPulsarSourceAgentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.functions.FunctionConfig;
import org.apache.pulsar.common.io.SinkConfig;
import org.apache.pulsar.common.io.SourceConfig;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.schema.SchemaType;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class PulsarStreamingClusterRuntime implements StreamingClusterRuntime {

    static final ObjectMapper mapper = new ObjectMapper();


    private PulsarAdmin buildPulsarAdmin(StreamingCluster streamingCluster) throws Exception {
        final PulsarClusterRuntimeConfiguration pulsarClusterRuntimeConfiguration =
                getPulsarClusterRuntimeConfiguration(streamingCluster);
        Map<String, Object> adminConfig = pulsarClusterRuntimeConfiguration.getAdmin();
        if (adminConfig == null) {
            adminConfig = new HashMap<>();
        }
        if (adminConfig.get("serviceUrl") == null) {
            adminConfig.put("serviceUrl", "http://localhost:8080");
        }
        return PulsarAdmin
                .builder()
                .loadConf(adminConfig)
                .build();
    }

    private PulsarClusterRuntimeConfiguration getPulsarClusterRuntimeConfiguration(StreamingCluster streamingCluster) {
        final Map<String, Object> configuration = streamingCluster.configuration();
        return mapper.convertValue(configuration, PulsarClusterRuntimeConfiguration.class);
    }

    @Override
    @SneakyThrows
    public void deploy(PhysicalApplicationInstance applicationInstance) {
        ApplicationInstance logicalInstance = applicationInstance.getApplicationInstance();
        try (PulsarAdmin admin = buildPulsarAdmin(logicalInstance.getInstance().streamingCluster())) {
            for (TopicImplementation topic : applicationInstance.getLogicalTopics()) {
                deployTopic(admin, (PulsarTopic) topic);
            }

        }
    }

    private static void deployTopic(PulsarAdmin admin, PulsarTopic topic) throws PulsarAdminException {
        String createMode = topic.createMode();
        String namespace = topic.name().tenant() + "/" + topic.name().namespace();
        String topicName = topic.name().tenant() + "/" + topic.name().namespace() + "/" + topic.name().name();
        log.info("Listing topics in namespace {}", namespace);
        List<String> existing = admin.topics().getList(namespace);
        log.info("Existing topics: {}", existing);
        String fullyQualifiedName = TopicName.get(topicName).toString();
        log.info("Looking for : {}", fullyQualifiedName);
        boolean exists = existing.contains(fullyQualifiedName);
        if (exists) {
            log.info("Topic {} already exists", topicName);
        } else {
            log.info("Topic {} does not exist", topicName);
        }
        switch (createMode) {
            case TopicDefinition.CREATE_MODE_CREATE_IF_NOT_EXISTS: {
                if (!exists) {
                    log.info("Topic {} does not exist, creating", topicName);
                    admin
                            .topics().createNonPartitionedTopic(topicName);
                }
                break;
            }
            case TopicDefinition.CREATE_MODE_NONE: {
                // do nothing
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown create mode " + createMode);
        }

        // deploy schema
        if (!StringUtils.isEmpty(topic.schemaType())) {
            List<SchemaInfo> allSchemas = admin.schemas().getAllSchemas(topicName);
            if (allSchemas.isEmpty()) {
                log.info("Deploying schema for topic {}", topicName);
                SchemaInfo schemaInfo = SchemaInfo
                        .builder()
                        .type(SchemaType.valueOf(topic.schemaType().toUpperCase()))
                        .name(topic.schemaName())
                        .properties(Map.of())
                        .schema(topic.schema().getBytes(StandardCharsets.UTF_8))
                        .build();
                admin.schemas().createSchema(topicName, schemaInfo);
            } else {
                log.info("Topic {} already has some schemas, skipping. ({})", topicName, allSchemas);
            }
        }
    }

    private static void deleteTopic(PulsarAdmin admin, PulsarTopic topic) throws PulsarAdminException {
        String topicName = topic.name().tenant() + "/" + topic.name().namespace() + "/" + topic.name().name();
        String fullyQualifiedName = TopicName.get(topicName).toString();
        log.info("Deleting topic {}", fullyQualifiedName);
        try {
            admin
                    .topics()
                    .delete(fullyQualifiedName, true);
        } catch (PulsarAdminException.NotFoundException notFoundException) {
            log.info("Topic {} didn't exit. Not a problem", fullyQualifiedName);
        }
    }

    @Override
    @SneakyThrows
    public void delete(PhysicalApplicationInstance applicationInstance) {
        ApplicationInstance logicalInstance = applicationInstance.getApplicationInstance();
        try (PulsarAdmin admin = buildPulsarAdmin(logicalInstance.getInstance().streamingCluster())) {
            for (TopicImplementation topic : applicationInstance.getLogicalTopics()) {
                deleteTopic(admin, (PulsarTopic) topic);
            }
        }
    }

    @Override
    public TopicImplementation createTopicImplementation(TopicDefinition topicDefinition, PhysicalApplicationInstance applicationInstance) {
        final PulsarClusterRuntimeConfiguration config =
                getPulsarClusterRuntimeConfiguration(applicationInstance.getApplicationInstance().getInstance().streamingCluster());

        SchemaDefinition schema = topicDefinition.getSchema();
        String name = topicDefinition.getName();
        String tenant = config.getDefaultTenant();
        String creationMode = topicDefinition.getCreationMode();
        String namespace = config.getDefaultNamespace();
        PulsarName topicName
                = new PulsarName(tenant, namespace, name);
        String schemaType = schema != null ? schema.type() : null;
        String schemaDefinition = schema != null ? schema.schema() : null;
        String schemaName =  schema != null ? schema.name() : null;
        PulsarTopic pulsarTopic = new PulsarTopic(topicName,
                schemaName,
                schemaType,
                schemaDefinition,
                creationMode);
        return pulsarTopic;
    }

}