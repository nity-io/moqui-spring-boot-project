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
import org.moqui.context.ExecutionContext
import org.moqui.context.WebFacade
import org.moqui.entity.EntityDataLoader
import org.moqui.service.ServiceFacade

def createSample() {
    ExecutionContext ec = ec

    def parameters = [
            sampleText: RandomStringUtils.randomAlphabetic(10)
    ]
    def results = ec.service.sync().name("create", "sample.SampleEntity").parameters(parameters).call()

    return results
}

def updateSample() {
    ExecutionContext ec = ec

    testGPath()

    def sampleId = context.sampleId
    def sampleText = context.sampleText

    def parameters = [
            sampleId  : sampleId,
            sampleText: sampleText
    ]
    def results = ec.service.sync().name("update", "sample.SampleEntity").parameters(parameters).call()

    return results
}

def test(){
    ExecutionContext ec = ec
    WebFacade web = ec.getWeb()
    ServiceFacade service = ec.getService()

}
