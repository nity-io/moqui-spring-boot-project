package io.nity.moqui.cache.sample;
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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.moqui.context.CacheFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.cache.Cache;

@RunWith(SpringRunner.class)
@SpringBootTest
public class CacheSampleTest {

    @Autowired
    private CacheFacade cacheFacade;

    @Test
    public void testAddCacheElement() {
        Cache testCache = cacheFacade.getLocalCache("CacheFacadeTests");

        testCache.put("key1", "value1");
        //long hitCountBefore = testCache.getStats().getCacheHits();

        Assert.assertEquals(testCache.get("key1"), "value1");

        //Assert.assertEquals(testCache.getStats().getCacheHits(), hitCountBefore + 1);
    }

    @Test
    public void testMaxElements() {
        Cache testCache = cacheFacade.getLocalCache("sample.local.test");

        for(int i=0;i<10;i++){
            testCache.put("key"+i, "value"+i);
        }

        for(int i=0;i<10;i++){
            Object value = testCache.get("key" + i);

            if(value == null){
                System.out.println("cache miss");
            }else{
                System.out.println("cache hit");
            }
        }

    }
}
