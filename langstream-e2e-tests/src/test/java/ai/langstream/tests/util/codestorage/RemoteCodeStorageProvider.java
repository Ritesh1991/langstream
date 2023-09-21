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
package ai.langstream.tests.util.codestorage;

import ai.langstream.tests.util.CodeStorageProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RemoteCodeStorageProvider implements CodeStorageProvider {

    private static final String SYS_PROPERTIES_PREFIX = "langstream.tests.codestorageremote.props.";
    private static final String ENV_PREFIX = "LANGSTREAM_TESTS_CODESTORAGEREMOTE_PROPS_";
    private static final String TYPE;
    private static final Map<String, String> CONFIG;

    static {
        CONFIG = new HashMap<>();

        final Map<String, String> envs = System.getenv();
        for (Map.Entry<String, String> env : envs.entrySet()) {
            if (env.getKey().startsWith(ENV_PREFIX)) {
                final String key =
                        env.getKey().substring(ENV_PREFIX.length()).toLowerCase().replace("_", ".");
                final String value = env.getValue();
                log.info("Loading remote codestorage config from env: {}={}", key, value);
                CONFIG.put(key, value);
            }
        }

        final Set<Object> props = System.getProperties().keySet();
        for (Object prop : props) {
            String asString = prop.toString();
            if (asString.startsWith(SYS_PROPERTIES_PREFIX)) {
                final String key = asString.substring(SYS_PROPERTIES_PREFIX.length());
                final String value = System.getProperty(asString);
                log.info("Loading remote codestorage config from sys: {}={}", key, value);
                CONFIG.put(key, value);
            }
        }
        String type = System.getProperty("langstream.tests.codestorageremote.type");
        if (type == null) {
            type = System.getenv("LANGSTREAM_TESTS_CODESTORAGEREMOTE_TYPE");
        }
        TYPE = type;
    }

    @Override
    public CodeStorageConfig start() {
        if (TYPE == null) {
            throw new IllegalArgumentException(
                    "langstream.tests.codestorageremote.type must be set");
        }
        return new CodeStorageConfig(TYPE, CONFIG);
    }

    @Override
    public void cleanup() {}

    @Override
    public void stop() {}
}