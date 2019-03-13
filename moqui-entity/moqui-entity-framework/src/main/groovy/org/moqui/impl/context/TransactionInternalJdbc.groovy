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
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionInternal
import org.moqui.entity.EntityFacade
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.DataSource
import javax.transaction.TransactionManager
import javax.transaction.UserTransaction
import java.sql.Connection

@CompileStatic
class TransactionInternalJdbc implements TransactionInternal {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionInternalJdbc.class)

    protected ExecutionContextFactoryImpl ecfi

    protected UserTransaction ut
    protected TransactionManager tm

    protected DataSource dataSource

    @Override
    TransactionInternal init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf

        dataSource = ecfi.dataSource


        return this
    }

    @Override
    TransactionManager getTransactionManager() { return tm }

    @Override
    UserTransaction getUserTransaction() { return ut }

    @Override
    DataSource getDataSource(EntityFacade ef, MNode datasourceNode) {
        return dataSource;
    }

    @Override
    void destroy() {
        logger.info("Shutting down TransactionInternalJdbc, no implement")
    }
}
