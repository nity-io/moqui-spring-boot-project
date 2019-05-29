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

package io.nity.moqui.service.sample.web;

import io.nity.moqui.service.sample.service.GreeterService;
import io.nity.moqui.service.sample.service.SampleService;
import io.nity.moqui.service.sample.util.ServiceUtil;
import org.apache.commons.lang3.RandomStringUtils;
import org.moqui.entity.EntityFacade;
import org.moqui.impl.context.ServiceExecutionContextFactoryImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

@RestController
public class GreeterController {

    @Autowired
    private ServiceExecutionContextFactoryImpl ecfi;

    @Autowired
    private EntityFacade entityFacade;

    @Autowired
    private GreeterService greeterService;
    @Autowired
    private SampleService sampleService;

    @RequestMapping(value = {"/greet"})
    public String greet() {

        String result = greeterService.sayHello("World");

        return result;
    }


    @RequestMapping(value = {"/greetJdbc"})
    public String greetJdbc() {

        String result = greeterService.sayHelloJdbc("World");

        return result;
    }

    @RequestMapping(value = {"/greetTwo"})
    public String greetTwo() {

        String result = greeterService.sayHelloTwo("World");

        return result;
    }

    @RequestMapping(value = {"/serviceCreateSample"})
    public String serviceCreateSample() throws MalformedURLException {
        Map map = new HashMap();
        Map result = ServiceUtil.callSync("io.nity.moqui.entity.sample.SampleServices.bizCreateSample", map);

        boolean isSuccess = ServiceUtil.isSuccess(result);

        return String.valueOf(isSuccess);
    }

    @RequestMapping(value = {"/serviceUpdateSample"})
    public String serviceUpdateSample() throws MalformedURLException {
        sampleService.updateSample();

        Map map = new HashMap();
        map.put("sampleId", "100000");
        map.put("sampleText", RandomStringUtils.randomAlphabetic(10));
        Map result = ServiceUtil.callSync("io.nity.moqui.entity.sample.SampleServices.bizUpdateSample", map);

        boolean isSuccess = ServiceUtil.isSuccess(result);

        return String.valueOf(isSuccess);
    }

    @RequestMapping(value = {"/clearCache"})
    public String clearCache() {

        ecfi.getCache().clearAllCaches();

        System.out.println("hi");

        return "clearCache";
    }

}
