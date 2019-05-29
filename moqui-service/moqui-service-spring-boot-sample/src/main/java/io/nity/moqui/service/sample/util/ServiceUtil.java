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

package io.nity.moqui.service.sample.util;

import org.apache.commons.lang3.StringUtils;
import org.moqui.BaseException;
import org.moqui.MoquiService;
import org.moqui.context.MessageFacade;
import org.moqui.context.ServiceExecutionContextFactory;

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
    public static boolean isError(Map<String, Object> results) {
        ServiceExecutionContextFactory ecf = MoquiService.getExecutionContextFactory();
        MessageFacade messageFacade = ecf.getExecutionContext().getMessage();

        if (results == null) {
            return true;
        } else {
            String errorMessage = messageFacade.getErrorsString();

            if (StringUtils.isNotBlank(errorMessage)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isSuccess(Map<String, Object> results) {
        if (ServiceUtil.isError(results)) {
            return false;
        }
        return true;
    }

    public static String getErrorMessage(Map<String, Object> result) {
        ServiceExecutionContextFactory ecf = MoquiService.getExecutionContextFactory();
        MessageFacade messageFacade = ecf.getExecutionContext().getMessage();

        if (isError(result)) {
            return messageFacade.getErrorsString();
        } else {
            return null;
        }
    }

    public static String getSuccessMessage(Map<String, Object> result) {
        ServiceExecutionContextFactory ecf = MoquiService.getExecutionContextFactory();
        MessageFacade messageFacade = ecf.getExecutionContext().getMessage();

        if (isSuccess(result)) {
            return messageFacade.getMessagesString();
        } else {
            return null;
        }
    }

    public static Map returnError(String message) {
        ServiceExecutionContextFactory ecf = MoquiService.getExecutionContextFactory();
        MessageFacade messageFacade = ecf.getExecutionContext().getMessage();

        messageFacade.addError(message);
        return null;
    }
    public static Map returnSuccess(String message) {
        ServiceExecutionContextFactory ecf = MoquiService.getExecutionContextFactory();
        MessageFacade messageFacade = ecf.getExecutionContext().getMessage();

        messageFacade.addMessage(message);
        Map results = new HashMap();

        return results;
    }

    public static Map returnSuccess() {
        Map results = new HashMap();
        results.put("status", "success");

        return results;
    }
    public static Map callSync(String name, Map<String, Object> parameters) {
        ServiceExecutionContextFactory ecf = MoquiService.getExecutionContextFactory();
        MessageFacade messageFacade = ecf.getExecutionContext().getMessage();

        Map results = ecf.getService().sync()
                .name(name)
                .parameters(parameters)
                .call();

        if (messageFacade.hasError()) {
            String errStr = messageFacade.getErrorsString();
            messageFacade.clearErrors();
            throw new BaseException(errStr);
        }

        return results;
    }

    public static void callAsync(String name, Map<String, Object> parameters) {
        ServiceExecutionContextFactory ecf = MoquiService.getExecutionContextFactory();

        ecf.getService().async()
                .name(name)
                .parameters(parameters)
                .call();

        return;
    }
}
