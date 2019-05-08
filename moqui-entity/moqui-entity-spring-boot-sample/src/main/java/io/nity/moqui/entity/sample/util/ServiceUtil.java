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

package io.nity.moqui.entity.sample.util;

import org.apache.commons.lang3.StringUtils;
import org.moqui.BaseException;
import org.moqui.context.ExecutionContext;

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
    public static boolean isError(ExecutionContext ec, Map<String, Object> results) {
        if (results == null) {
            return true;
        } else {
            String errorMessage = ec.getMessage().getErrorsString();

            if (StringUtils.isNotBlank(errorMessage)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isSuccess(ExecutionContext ec, Map<String, Object> results) {
        if (ServiceUtil.isError(ec, results)) {
            return false;
        }
        return true;
    }

    public static String getErrorMessage(ExecutionContext ec, Map<String, Object> result) {
        if (isError(ec, result)) {
            return ec.getMessage().getErrorsString();
        } else {
            return null;
        }
    }

    public static String getSuccessMessage(ExecutionContext ec, Map<String, Object> result) {
        if (isSuccess(ec, result)) {
            return ec.getMessage().getMessagesString();
        } else {
            return null;
        }
    }

    public static Map returnError(ExecutionContext ec, String message) {
        ec.getMessage().addError(message);
        return null;
    }
    public static Map returnSuccess(ExecutionContext ec, String message) {
        ec.getMessage().addMessage(message);
        Map results = new HashMap();

        return results;
    }

    public static Map returnSuccess() {
        Map results = new HashMap();
        results.put("status", "success");

        return results;
    }
    public static Map callSync(ExecutionContext ec, String name, Map<String, Object> parameters) {
        Map results = ec.getService().sync()
                .name(name)
                .parameters(parameters)
                .call();

        if (ec.getMessage().hasError()) {
            String errStr = ec.getMessage().getErrorsString();
            ec.getMessage().clearErrors();
            throw new BaseException(errStr);
        }

        return results;
    }

    public static void callAsync(ExecutionContext ec, String name, Map<String, Object> parameters) {
        ec.getService().async()
                .name(name)
                .parameters(parameters)
                .call();
        return;
    }
}
