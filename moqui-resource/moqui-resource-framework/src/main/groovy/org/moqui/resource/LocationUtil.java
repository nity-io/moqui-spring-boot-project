package org.moqui.resource;

import org.apache.commons.lang3.StringUtils;

/**
 * Resource location util
 */
public class LocationUtil {
    public static String parse(String location) {
        if(StringUtils.isEmpty(location)){
            return location;
        }

        if(!location.startsWith("//")){
            return location;
        }

        String instancePurpose = System.getProperty("instance_purpose");
        if (StringUtils.isEmpty(location) || instancePurpose == "production") {
            //Production Mode
            return "classpath:" + location;
        }else{
            return "component://webroot" + location.substring(1);
        }
    }
}
