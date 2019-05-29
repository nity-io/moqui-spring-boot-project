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

package io.nity.moqui.resource.sample.web

import org.apache.commons.text.StringEscapeUtils
import org.moqui.context.ResourceFacade
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
public class SampleController {

    @Autowired
    private ResourceFacade resourceFacade

    @RequestMapping(value = "/moquiConf")
    String moquiConfCache() {
        def reference = resourceFacade.getLocationReference("classpath://MoquiConf.xml")
        def text = reference.getText()

        return StringEscapeUtils.escapeXml11(text)
    }

    @RequestMapping(value = "/moquiConfText")
    String moquiConf() {
        def text = resourceFacade.getLocationText("classpath://MoquiConf.xml", false)

        return StringEscapeUtils.escapeXml11(text)
    }

    @RequestMapping(value = "/moquiConfTextCache")
    String moquiConfTextCache() {
        def text = resourceFacade.getLocationText("classpath://MoquiConf.xml", true)

        return StringEscapeUtils.escapeXml11(text)
    }

}
