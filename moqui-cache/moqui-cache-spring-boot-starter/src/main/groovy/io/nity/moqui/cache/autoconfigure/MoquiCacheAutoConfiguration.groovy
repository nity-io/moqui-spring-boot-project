/*
 * Copyright 2019 The nity.io Moqui Spring Boot Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.nity.moqui.cache.autoconfigure

import groovy.transform.CompileStatic
import org.moqui.cache.context.CacheFacade
import org.moqui.cache.impl.context.ExecutionContextFactoryImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@CompileStatic
@Configuration
class MoquiCacheAutoConfiguration {

    @Bean
    ExecutionContextFactoryImpl executionContextFactoryImpl() {
        ExecutionContextFactoryImpl executionContextFactoryImpl = new ExecutionContextFactoryImpl()

        return executionContextFactoryImpl
    }

    @Bean
    CacheFacade cacheFacade(ExecutionContextFactoryImpl executionContextFactoryImpl) {
        CacheFacade cacheFacade = executionContextFactoryImpl.getCache()

        return cacheFacade
    }

}
