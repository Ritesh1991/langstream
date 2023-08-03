/**
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
package com.datastax.oss.sga.runtime.impl.k8s.agents;

import com.datastax.oss.sga.api.model.AgentConfiguration;
import com.datastax.oss.sga.api.runtime.ComponentType;
import com.datastax.oss.sga.impl.common.AbstractAgentProvider;
import com.datastax.oss.sga.runtime.impl.k8s.KubernetesClusterRuntime;

import java.util.List;
import java.util.Set;

public class KafkaConnectAgentsProvider extends AbstractAgentProvider {

    public KafkaConnectAgentsProvider() {
        super(Set.of("sink", "source"), List.of(KubernetesClusterRuntime.CLUSTER_TYPE));
    }

    @Override
    protected ComponentType getComponentType(AgentConfiguration agentConfiguration) {
        switch (agentConfiguration.getType()) {
            case "sink":
                return ComponentType.SINK;
                case "source":
                    return ComponentType.SOURCE;
            default:
                throw new IllegalStateException();
        }
    }

}