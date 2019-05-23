/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.moqui.MoquiWeb
import org.moqui.impl.context.WebExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpSessionAttributeListener
import javax.servlet.http.HttpSessionBindingEvent
import java.sql.Timestamp

@CompileStatic
class MoquiSessionAttributeListener implements HttpSessionAttributeListener {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiSessionAttributeListener.class)
    private HashMap<String, String> visitIdBySession = new HashMap<>()

    @Override void attributeAdded(HttpSessionBindingEvent event) {
        if ("moqui.visitId".equals(event.name)) visitIdBySession.put(event.session.id, event.value.toString())
    }
    @Override void attributeReplaced(HttpSessionBindingEvent event) {
        if ("moqui.visitId".equals(event.name)) {
            String sessionId = event.session.id
            String oldValue = event.value.toString()
            if (!oldValue) oldValue = visitIdBySession.get(sessionId)
            String newValue = event.session.getAttribute("moqui.visitId")
            if (newValue) visitIdBySession.put(sessionId, newValue)
            if (oldValue) closeVisit(oldValue, sessionId)
        }
    }

    @Override void attributeRemoved(HttpSessionBindingEvent event) {
        if ("moqui.visitId".equals(event.name)) {
            String sessionId = event.session.id
            String visitId = event.value
            if (!visitId) {
                if (logger.traceEnabled) logger.trace("Not closing visit for session ${sessionId}, no value for removed moqui.visitId session attribute")
                return
            }
            closeVisit(visitId, sessionId)
        }
    }
    static void closeVisit(String visitId, String sessionId) {
        WebExecutionContextFactoryImpl ecfi = (WebExecutionContextFactoryImpl) MoquiWeb.getExecutionContextFactory()
        if (ecfi.confXmlRoot.first("server-stats").attribute("visit-enabled") == "false") return

        // set thruDate on Visit
        Timestamp thruDate = new Timestamp(System.currentTimeMillis())
        ecfi.serviceFacade.sync().name("update", "moqui.server.Visit").parameter("visitId", visitId).parameter("thruDate", thruDate)
                .disableAuthz().call()
        if (logger.traceEnabled) logger.trace("Closed visit ${visitId} at ${thruDate} for session ${sessionId}")
    }
}
