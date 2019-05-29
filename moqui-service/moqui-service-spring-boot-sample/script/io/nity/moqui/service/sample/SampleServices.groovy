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

package io.nity.moqui.entity.sample

import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.time.DateFormatUtils
import org.moqui.MoquiService
import org.moqui.context.ExecutionContext

import java.lang.reflect.Method

def createSample() {
    ExecutionContext ec = ec

    def parameters = [
            sampleText: RandomStringUtils.randomAlphabetic(10)
    ]
    println("createSample parameters:$parameters")
    def results = MoquiService.getExecutionContextFactory().service.sync().name("create", "sample.SampleEntity").parameters(parameters).call()

    return results
}

def updateSample() {
    ExecutionContext ec = ec
    ExecutionContext ec1 = MoquiService.getExecutionContext()

    def sampleId = context.sampleId
    def sampleText = context.sampleText + "|" + DateFormatUtils.format(new Date(), "yyyy-MM-dd'T'HH:mm:ss")

    def name = "org.moqui.MoquiEntity"
    Class<?> moquiEntityClass = Thread.currentThread().getContextClassLoader().loadClass(name)
    Method method = moquiEntityClass.getMethod("getEntity")
    Object entityFacade = method.invoke(null)

    def parameters = [
            sampleId  : sampleId,
            sampleText: sampleText
    ]
    println("updateSample parameters:$parameters")
    def results = MoquiService.getExecutionContextFactory().service.sync().name("update", "sample.SampleEntity").parameters(parameters).call()

    return results
}
