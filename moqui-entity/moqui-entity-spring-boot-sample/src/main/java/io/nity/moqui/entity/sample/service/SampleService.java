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

package io.nity.moqui.entity.sample.service;

import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可以按平常的方式使用service
 */
@Service
public class SampleService {

    @Autowired
    private EntityFacade entity;

    @Transactional(rollbackFor = Throwable.class)
    public String createSample() {
        EntityValue sample = entity.makeValue("sample.SampleEntity")
                .set("sampleText", "test text")
                .setSequencedIdPrimary()
                .create();

        return sample.toString();
    }

    @Transactional(rollbackFor = Throwable.class)
    public String updateSample() {
        EntityValue sample = entity.find("sample.SampleEntity")
                .condition("sampleId", "100000")
                .one();

        sample.set("sampleText", "updated test text")
                .update();

        return sample.toString();
    }

    @Transactional(rollbackFor = Throwable.class)
    public String cacheSample() {
        EntityValue sample = entity.find("sample.SampleEntity")
                .condition("sampleId", "100000")
                .useCache(true)
                .one();

        return sample.toString();
    }

}