/*
 * Copyright 2019 The nity.io gRPC Spring Boot Project Authors
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

import org.moqui.context.ExecutionContext;
import org.moqui.context.TransactionFacade;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.ArtifactExecutionFacadeImpl;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.service.ServiceFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 可以按平常的方式使用service
 */
@Service
public class GreeterService {

    @Autowired
    private ExecutionContextFactoryImpl ecfi;
    @Autowired
    private EntityFacade entity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public String sayHello(String name) {
        //TransactionFacade transaction = ec.getTransaction();

        //boolean begin = transaction.begin(60);
        ExecutionContextImpl ec = ecfi.getEci();
        ArtifactExecutionFacadeImpl artifactExecutionFacade = ec.artifactExecutionFacade;

        artifactExecutionFacade.disableAuthz();

        long deleteCount = entity.find("moqui.test.TestEntity").deleteAll();
        System.out.println("deleteCount:" + deleteCount);

        if(true){
            //throw new RuntimeException("test error");
        }

        Map map = new HashMap<String, Object>();
        map.put("testId", "CRDTST1");
        map.put("testMedium", "Test Name");

        entity.makeValue("moqui.test.TestEntity").setAll(map).create();

        map = new HashMap<String, Object>();
        map.put("testId", "CRDTST1");
        map.put("testMedium", "Test Name Update");

        //ServiceFacade service = ec.getService();
        //service.sync()
        //        .name("update", "moqui.test.TestEntity")
        //        .requireNewTransaction(true)
        //        .parameters(map)
        //        .disableAuthz()
        //        .call();

        EntityValue testEntity = entity.find("moqui.test.TestEntity").condition("testId", "CRDTST1").one();

        //transaction.commit(begin);

        return testEntity.toString();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public String sayHelloJdbc(String name) {

        String sql = "insert into request_log (id, request_name,created_date) values (?,?,?)";
//        Object[] args = [name, new Timestamp(System.currentTimeMillis())];
        String id = UUID.randomUUID().toString().replace("-", "");
        int result = jdbcTemplate.update(sql, id, name, new Timestamp(System.currentTimeMillis()));
        if (result > 0) {
            System.out.println("GreeterService_sayHello 数据保存成功 name:" + name);
        }

        return "Hello";
    }

}