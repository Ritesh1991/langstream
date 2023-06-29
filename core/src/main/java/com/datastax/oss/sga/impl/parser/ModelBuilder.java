/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.sga.impl.parser;

import com.datastax.oss.sga.api.model.AgentConfiguration;
import com.datastax.oss.sga.api.model.ApplicationInstance;
import com.datastax.oss.sga.api.model.Instance;
import com.datastax.oss.sga.api.model.Module;
import com.datastax.oss.sga.api.model.Pipeline;
import com.datastax.oss.sga.api.model.Resource;
import com.datastax.oss.sga.api.model.Secret;
import com.datastax.oss.sga.api.model.Secrets;
import com.datastax.oss.sga.api.model.Connection;
import com.datastax.oss.sga.api.model.TopicDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class ModelBuilder {

    static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());


    /**
     * Builds an in memory model of the application from the given directories.
     * We read from multiple directories to allow for a modular approach to defining the application.
     * For instance, you can have a directory with the application definition and one directory with the
     * instance.yaml file and the secrets.yaml file.
     * This is server side code and it is expected that the directories are local to the server.
     * @param directories
     * @return a fully built application instance (application model + instance + secrets)
     * @throws Exception
     */
    public static ApplicationInstance buildApplicationInstance(List<Path> directories) throws Exception {
        ApplicationInstance application = new ApplicationInstance();
        for (Path directory : directories) {
            log.info("Parsing directory: {}", directory.toAbsolutePath());
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory);) {
                for (Path path : paths) {
                    parseFile(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8), application);
                }
            }
        }
        return application;
    }

    public static ApplicationInstance buildApplicationInstance(Map<String, String> files) throws Exception {
        ApplicationInstance application = new ApplicationInstance();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            parseFile(entry.getKey(), entry.getValue(), application);
        }
        return application;
    }

    private static void parseFile(String fileName, String content, ApplicationInstance application) throws IOException {
        if (!fileName.endsWith(".yaml")) {
            // skip
            log.info("Skipping {}", fileName);
            return;
        }

        switch (fileName) {
            case "configuration.yaml":
                parseConfiguration(content, application);
                break;
            case "secrets.yaml":
                parseSecrets(content, application);
                break;
            case "instance.yaml":
                parseInstance(content, application);
                break;
            default:
                parsePipelineFile(content, application);
                break;
        }
    }

    private static void parseConfiguration(String content, ApplicationInstance application) throws IOException {
        ConfigurationFileModel configurationFileModel = mapper.readValue(content, ConfigurationFileModel.class);
        ConfigurationNodeModel configurationNode = configurationFileModel.configuration();
        if (configurationNode != null && configurationNode.resources != null) {
            configurationNode.resources.forEach(r-> {
                application.getResources().put(r.id(), r);
            });

        }
        log.info("Configuration: {}", configurationFileModel);
    }

    private static void parsePipelineFile(String content, ApplicationInstance application) throws IOException {
        PipelineFileModel configuration = mapper.readValue(content, PipelineFileModel.class);
        Module module = application.getModule(configuration.getModule());
        log.info("Configuration: {}", configuration);

        Pipeline pipeline = module.addPipeline(configuration.getId());
        pipeline.setName(configuration.getName());
        AgentConfiguration last = null;

        if (configuration.getTopics() != null) {
            for (TopicDefinition topicDefinition : configuration.getTopics()) {
                module.addTopic(topicDefinition);
            }
        }

        int autoId = 1;
        if (configuration.getPipeline() != null) {
            for (AgentModel agent : configuration.getPipeline()) {
                AgentConfiguration agentConfiguration = agent.toAgentConfiguration();
                if (agentConfiguration.getId() == null) {
                    // ensure that we always have a name
                    // please note that this algorithm should not be changed in order to not break
                    // compatibility with existing configuration files
                    agentConfiguration.setId(agentConfiguration.getType() + "_" + autoId++);
                }
                if (agent.getInput() != null) {
                    agentConfiguration.setInput(new Connection(module.resolveTopic(agent.getInput())));
                }
                if (agent.getOutput() != null) {
                    agentConfiguration.setOutput(new Connection(module.resolveTopic(agent.getOutput())));
                }
                if (last != null && agentConfiguration.getOutput() == null) {
                    // assume that the previous agent is the output of this one
                    agentConfiguration.setOutput(new Connection(last));
                }
                pipeline.addAgentConfiguration(agentConfiguration);
                last = agentConfiguration;
            }
        }
    }

    private static void parseSecrets(String content, ApplicationInstance application) throws IOException {
        SecretsFileModel secretsFileModel = mapper.readValue(content, SecretsFileModel.class);
        log.info("Secrets: {}", secretsFileModel);
        application.setSecrets(new Secrets(secretsFileModel.secrets()
                .stream().collect(Collectors.toMap(Secret::id, Function.identity()))));
    }

    private static void parseInstance(String content, ApplicationInstance application) throws IOException {
        InstanceFileModel instance = mapper.readValue(content, InstanceFileModel.class);
        log.info("Instance Configuration: {}", instance);
        application.setInstance(instance.instance);
    }


    @Data
    public static final class PipelineFileModel {
        private String module = Module.DEFAULT_MODULE;
        private String id;
        private String name;
        private List<AgentModel> pipeline = new ArrayList<>();

        private List<TopicDefinition> topics = new ArrayList<>();
    }

    @Data
    public static final class AgentModel {
        private String id;
        private String name;
        private String type;
        private String input;
        private String output;
        private Map<String, Object> configuration = new HashMap<>();

        AgentConfiguration toAgentConfiguration() {
            AgentConfiguration res =  new AgentConfiguration();
            res.setId(id);
            res.setName(name);
            res.setType(type);
            res.setConfiguration(configuration);
            return res;
        }
    }

    public record ConfigurationNodeModel(List<Resource> resources) {}

    public record ConfigurationFileModel(ConfigurationNodeModel configuration) {}

    public record SecretsFileModel(List<Secret> secrets) {
    }

    public record InstanceFileModel(Instance instance) {
    }
}

