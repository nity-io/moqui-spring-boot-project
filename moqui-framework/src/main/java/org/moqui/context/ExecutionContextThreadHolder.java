package org.moqui.context;

import java.util.HashMap;
import java.util.Map;

/**
 * TODO
 *
 * @date: 2019-05-15 14:10
 */
public class ExecutionContextThreadHolder {
    public static final ThreadLocal<ExecutionContext> activeContext = new ThreadLocal<>();
    public static final Map<Long, ExecutionContext> activeContextMap = new HashMap<>();


}
