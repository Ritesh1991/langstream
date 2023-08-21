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
package ai.langstream.api.codestorage;

import java.util.Map;

public interface CodeStorageProvider {

    /**
     * Create an Implementation of a CodeStorage implementation.
     * @param codeStorageType
     * @return the implementation
     */
    CodeStorage createImplementation(String codeStorageType, Map<String, Object> configuration);

    /**
     * Returns the ability of an Agent to be deployed on the give runtimes.
     * @param codeStorageType
     * @return true if this provider that can create the implementation
     */
    boolean supports(String codeStorageType);

}