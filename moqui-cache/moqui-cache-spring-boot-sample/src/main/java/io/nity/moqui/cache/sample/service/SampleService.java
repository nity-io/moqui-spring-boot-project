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

package io.nity.moqui.cache.sample.service;

import org.moqui.cache.context.CacheFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.cache.Cache;
import java.util.Date;

/**
 * 可以按平常的方式使用service
 */
@Service
public class SampleService {

    private static final Logger logger = LoggerFactory.getLogger(SampleService.class);

    @Autowired
    private CacheFacade cacheFacade;

    public String sayHello(String name) {
        Date operateTime = new Date();
        Cache<String, String> helloCache = cacheFacade.getCache("hello", String.class, String.class);
        String helloContent = null;

        if((helloContent = helloCache.get("hello")) != null){
            logger.info("cache hit");
        }else{
            logger.info("cache init");
            helloCache.put("hello", "hello world");

        }

        logger.info(helloContent);
        logger.info(cacheFacade.toString());

        long cost = System.currentTimeMillis() - operateTime.getTime();
        logger.info("sayHello cost:"+cost);

        return "hello world";
    }

}