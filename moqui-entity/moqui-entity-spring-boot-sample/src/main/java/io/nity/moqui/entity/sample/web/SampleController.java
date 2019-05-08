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

package io.nity.moqui.entity.sample.web;

import io.nity.moqui.entity.sample.service.SampleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SampleController {

    @Autowired
    private SampleService sampleService;

    @RequestMapping(value = {"/createSample"})
    public String greet() {

        String result = sampleService.createSample();

        return result;
    }

    @RequestMapping(value = {"/updateSample"})
    public String updateSample() {

        String result = sampleService.updateSample();

        return result;
    }

    @RequestMapping(value = {"/cacheSample"})
    public String cacheSample() {

        String result = sampleService.cacheSample();

        return result;
    }

}
