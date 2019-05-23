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

import org.moqui.context.CacheFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.cache.Cache;
import java.util.Date;
import java.util.Iterator;
import java.util.UUID;

/**
 * 可以按平常的方式使用service
 */
@Service
public class SampleService {

    private static final Logger logger = LoggerFactory.getLogger(SampleService.class);

    @Autowired
    private CacheFacade cacheFacade;

    public String sayHello() {
        Date operateTime = new Date();
        Cache<String, String> localHelloCache = cacheFacade.getCache("sample.local.hello", String.class, String.class);
        String helloContent = null;

        if ((helloContent = localHelloCache.get("hello")) != null) {
            logger.info("cache hit");
        } else {
            logger.info("cache miss");
            localHelloCache.put("hello", "hello world");
            helloContent = localHelloCache.get("hello");
        }

        logger.info(helloContent);
        logger.info(cacheFacade.toString());

        long cost = System.currentTimeMillis() - operateTime.getTime();
        logger.info("sayHello cost:" + cost);

        return "hello world";
    }

    public String testMaxElements() {
        Cache testCache = cacheFacade.getLocalCache("sample.local.max-elements.test");

        String key = UUID.randomUUID().toString();
        testCache.put(key, key + ":value");

        Iterator<Cache.Entry> iterator = testCache.iterator();
        int size = 0;
        while (iterator.hasNext()) {
            size++;
            Cache.Entry next = iterator.next();
            System.out.println(next.getKey() + ":" + next.getValue());
        }
        System.out.println("");

        return "entryList size:" + size;
    }

    public String testExpireTimeIdle() {
        Cache testCache = cacheFacade.getLocalCache("sample.local.expire-time-idle.test");

        String key = UUID.randomUUID().toString();
        testCache.put(key, key + ":value");

        Iterator<Cache.Entry> iterator = testCache.iterator();
        int size = 0;
        while (iterator.hasNext()) {
            size++;
            Cache.Entry next = iterator.next();
            System.out.println(next.getKey() + ":" + next.getValue());
        }
        System.out.println("");

        return "entryList size:" + size;
    }

    public String addDistributed() {
        Cache testCache = cacheFacade.getLocalCache("sample.distributed.test");

        System.out.println("");
        System.out.println("addDistributed...");

        String key = UUID.randomUUID().toString();
        testCache.put(key, key + ":value");

        Iterator<Cache.Entry> iterator = testCache.iterator();
        int size = 0;
        while (iterator.hasNext()) {
            size++;
            Cache.Entry next = iterator.next();
            System.out.println(next.getKey() + ":" + next.getValue());
        }
        System.out.println("");

        return "entryList size:" + size;
    }

    public String getDistributed() {
        System.out.println("");
        System.out.println("getDistributed...");
        long start = System.currentTimeMillis();
        int size = 0;

        Cache testCache = cacheFacade.getLocalCache("sample.distributed.test");

        Iterator<Cache.Entry> iterator = testCache.iterator();

        while (iterator.hasNext()) {
            size++;
            Cache.Entry next = iterator.next();
            System.out.println(next.getKey() + ":" + next.getValue());
        }

        long end = System.currentTimeMillis();
        long cost = end - start;

        System.out.println("cost:" + cost);
        return "entryList size:" + size;
    }

}