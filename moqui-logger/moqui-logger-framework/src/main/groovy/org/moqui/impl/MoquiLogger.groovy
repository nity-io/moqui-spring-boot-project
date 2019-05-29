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
package org.moqui

import org.moqui.context.LoggerFacade
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class MoquiLogger {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiLogger.class);

    private static LoggerFacade loggerFacadeInternal;

    static LoggerFacade getLogger() {
        return loggerFacadeInternal
    }

    static void setLoggerFacade(LoggerFacade loggerFacade) {
        MoquiLogger.loggerFacadeInternal = loggerFacade
    }

}
