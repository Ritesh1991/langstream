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
package com.datastax.oss.sga.api.runtime;

import com.datastax.oss.sga.api.model.AgentConfiguration;
import com.datastax.oss.sga.api.model.ApplicationInstance;
import com.datastax.oss.sga.api.model.TopicDefinition;

/**
 * This is the interface that the SGA framework uses to interact with the StreamingCluster. It is used to
 * model a physical cluster runtime with Brokers (Pulsar, Kafka....)
 */
public interface StreamingClusterRuntime {

    /**
     * Deploy the topics on the StreamingCluster
     * @param applicationInstance
     */
    void deploy(PhysicalApplicationInstance applicationInstance);

    /**
     * Undeploy all the resources created on the StreamingCluster
     * @param applicationInstance
     */
    void delete(PhysicalApplicationInstance applicationInstance);

    /**
     * Map a Logical TopicDefinition to a Physical TopicImplementation
     * @param topicDefinition
     * @return
     */
    TopicImplementation createTopicImplementation(TopicDefinition topicDefinition, PhysicalApplicationInstance applicationInstance);

}