/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.moqui.service.util;

import org.moqui.MoquiService;
import org.moqui.context.ExecutionContext;
import org.moqui.service.ServiceFacade;
import org.moqui.util.ObjectUtilities;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic Service Utility Class
 */
public class ServiceUtil {

    public static final String module = ServiceUtil.class.getName();

    /**
     * A little short-cut method to check to see if a service returned an error
     */
    private static boolean isError(ExecutionContext ec, Map<String, Object> result) {
        if (result == null) {
            return true;
        } else {
            String errorMessage = ec.getMessage().getErrorsString();

            if (ObjectUtilities.isNotEmpty(errorMessage)) {
                return true;
            }
        }

        return false;
    }

    /**
     * A little short-cut method to check to see if a service returned an error
     */
    public static boolean isError(Map<String, Object> result) {
        ExecutionContext ec = MoquiService.getExecutionContext();

        return isError(ec, result);
    }

    private static boolean isSuccess(ExecutionContext ec, Map<String, Object> result) {
        if (ServiceUtil.isError(ec, result)) {
            return false;
        }
        return true;
    }

    public static boolean isSuccess(Map<String, Object> result) {
        ExecutionContext ec = MoquiService.getExecutionContext();

        return isSuccess(ec, result);
    }

    public static String getErrorMessage(Map<String, Object> result) {
        ExecutionContext ec = MoquiService.getExecutionContext();

        if (isError(ec, result)) {
            return ec.getMessage().getErrorsString();
        } else {
            return null;
        }
    }

    public static String getSuccessMessage(Map<String, Object> result) {
        ExecutionContext ec = MoquiService.getExecutionContext();

        if (isSuccess(ec, result)) {
            return ec.getMessage().getMessagesString();
        } else {
            return null;
        }
    }

    public static Map returnError(String message) {
        ExecutionContext ec = MoquiService.getExecutionContext();

        ec.getMessage().addError(message);
        return null;
    }

    public static Map returnSuccess(String message) {
        ExecutionContext ec = MoquiService.getExecutionContext();

        ec.getMessage().addMessage(message);
        Map result = new HashMap();

        return result;
    }

    public static Map returnSuccess() {
        Map result = new HashMap();
        result.put("status", "success");

        return result;
    }

    public static Map returnSuccess(Map data) {
        Map result = new HashMap();
        result.put("status", "success");

        if(data != null){
            result.putAll(data);
        }

        return result;
    }

    public static Map callSync(String name, Map<String, Object> parameters) {
        ExecutionContext ec = MoquiService.getExecutionContext();

        ServiceFacade service = ec.getService();
        Map result = service.sync()
                .name(name)
                .parameters(parameters)
                .call();

        return result;
    }

    public static void callAsync(String name, Map<String, Object> parameters) {
        ExecutionContext ec = MoquiService.getExecutionContext();

        ServiceFacade service = ec.getService();
        service.async()
                .name(name)
                .parameters(parameters)
                .call();
        return;
    }
}
