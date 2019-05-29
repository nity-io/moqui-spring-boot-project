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
package org.moqui.impl.service

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.moqui.BaseArtifactException
import org.moqui.BaseException
import org.moqui.MoquiEntity
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ServiceExecutionContextFactoryImpl
import org.moqui.service.ServiceCallJob
import org.moqui.service.ServiceCallSync
import org.moqui.service.ServiceException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.*

@CompileStatic
class ServiceCallJobImpl extends ServiceCallImpl implements ServiceCallJob {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceCallJobImpl.class)

    private String jobName
    private EntityValue serviceJob
    private Future<Map<String, Object>> runFuture = (Future) null
    private String withJobRunId = (String) null
    private Timestamp lastRunTime = (Timestamp) null
    private boolean clearLock = false

    ServiceCallJobImpl(String jobName, ServiceFacadeImpl sfi) {
        super(sfi)
        ExecutionContext eci = sfi.ecfi.getEci()

        // get ServiceJob, make sure exists
        this.jobName = jobName
        serviceJob = sfi.ecfi.entityFacade.fastFindOne("moqui.service.job.ServiceJob", true, true, jobName)
        if (serviceJob == null) throw new BaseArtifactException("No ServiceJob record found for jobName ${jobName}")

        // set ServiceJobParameter values
        EntityList serviceJobParameters = sfi.ecfi.entity.find("moqui.service.job.ServiceJobParameter")
                .condition("jobName", jobName).useCache(true).disableAuthz().list()
        for (EntityValue serviceJobParameter in serviceJobParameters)
            parameters.put((String) serviceJobParameter.parameterName, serviceJobParameter.parameterValue)

        // set the serviceName so rest of ServiceCallImpl works
        serviceNameInternal((String) serviceJob.serviceName)
    }

    @Override
    ServiceCallJob parameters(Map<String, ?> map) { parameters.putAll(map); return this }
    @Override
    ServiceCallJob parameter(String name, Object value) { parameters.put(name, value); return this }

    ServiceCallJobImpl withJobRunId(String jobRunId) { withJobRunId = jobRunId; return this }
    ServiceCallJobImpl withLastRunTime(Timestamp lastRunTime) { this.lastRunTime = lastRunTime; return this }
    ServiceCallJobImpl clearLock() { clearLock = true; return this }

    @Override
    String run() throws ServiceException {
        ServiceExecutionContextFactoryImpl ecfi = sfi.ecfi
        ExecutionContext eci = ecfi.getEci()
        validateCall(eci)

        String jobRunId
        if (withJobRunId == null) {
            // create the ServiceJobRun record
            String parametersString = JsonOutput.toJson(parameters)
            Map jobRunResult = ecfi.service.sync().name("create", "moqui.service.job.ServiceJobRun")
                    .parameters([jobName:jobName, userId:eci.user.userId, parameters:parametersString] as Map<String, Object>)
                    .disableAuthz().requireNewTransaction(true).call()
            jobRunId = jobRunResult.jobRunId
        } else {
            jobRunId = withJobRunId
        }

        // run it
        ServiceJobCallable callable = new ServiceJobCallable(ecfi, eci, serviceJob, jobRunId, lastRunTime, clearLock, parameters)
        if (sfi.distributedExecutorService == null || serviceJob.localOnly == 'Y') {
            runFuture = ecfi.workerPool.submit(callable)
        } else {
            runFuture = sfi.distributedExecutorService.submit(callable)
        }

        return jobRunId
    }

    @Override
    boolean cancel(boolean mayInterruptIfRunning) {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.cancel(mayInterruptIfRunning)
    }
    @Override
    boolean isCancelled() {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.isCancelled()
    }
    @Override
    boolean isDone() {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.isDone()
    }
    @Override
    Map<String, Object> get() throws InterruptedException, ExecutionException {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.get()
    }
    @Override
    Map<String, Object> get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.get(timeout, unit)
    }

    static class ServiceJobCallable implements Callable<Map<String, Object>>, Externalizable {
        transient ServiceExecutionContextFactoryImpl ecfi
        String threadUsername, currentUserId
        String jobName, jobDescription, serviceName, topic, jobRunId
        Map<String, Object> parameters
        Timestamp lastRunTime = (Timestamp) null
        boolean clearLock
        int transactionTimeout

        // default constructor for deserialization only!
        ServiceJobCallable() { }

        ServiceJobCallable(ServiceExecutionContextFactoryImpl ecfi, ExecutionContext eci, Map<String, Object> serviceJob, String jobRunId, Timestamp lastRunTime,
                           boolean clearLock, Map<String, Object> parameters) {
            this.ecfi = ecfi
            threadUsername = eci.getUser().username
            currentUserId = eci.getUser().userId
            jobName = (String) serviceJob.jobName
            jobDescription = (String) serviceJob.description
            serviceName = (String) serviceJob.serviceName
            topic = (String) serviceJob.topic
            transactionTimeout = (serviceJob.transactionTimeout ?: 1800) as int
            this.jobRunId = jobRunId
            this.lastRunTime = lastRunTime
            this.clearLock = clearLock
            this.parameters = new HashMap<>(parameters)
        }

        @Override
        void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(threadUsername) // might be null
            out.writeObject(currentUserId) // might be null
            out.writeUTF(jobName) // never null
            out.writeObject(jobDescription) // might be null
            out.writeUTF(serviceName) // never null
            out.writeObject(topic) // might be null
            out.writeUTF(jobRunId) // never null
            out.writeObject(lastRunTime) // might be null
            out.writeBoolean(clearLock)
            out.writeInt(transactionTimeout)
            out.writeObject(parameters)
        }
        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            threadUsername = (String) objectInput.readObject()
            currentUserId = (String) objectInput.readObject()
            jobName = objectInput.readUTF()
            jobDescription = objectInput.readObject()
            serviceName = objectInput.readUTF()
            topic = (String) objectInput.readObject()
            jobRunId = objectInput.readUTF()
            lastRunTime = (Timestamp) objectInput.readObject()
            clearLock = objectInput.readBoolean()
            transactionTimeout = objectInput.readInt()
            parameters = (Map<String, Object>) objectInput.readObject()
        }

        ServiceExecutionContextFactoryImpl getEcfi() {
            if (ecfi == null) ecfi = (ServiceExecutionContextFactoryImpl) MoquiEntity.getExecutionContextFactory()
            return ecfi
        }

        @Override
        Map<String, Object> call() throws Exception {
            ExecutionContext threadEci = (ExecutionContext) null
            try {
                ServiceExecutionContextFactoryImpl ecfi = getEcfi()
                threadEci = ecfi.getEci()
                if (threadUsername != null && threadUsername.length() > 0)
                    threadEci.getUser().internalLoginUser(threadUsername, false)

                // set hostAddress, hostName, runThread, startTime on ServiceJobRun
                InetAddress localHost = ecfi.getLocalhostAddress()
                // NOTE: no need to run async or separate thread, is in separate TX because no wrapping TX for these service calls
                ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRun")
                        .parameters([jobRunId:jobRunId, hostAddress:(localHost?.getHostAddress() ?: '127.0.0.1'),
                            hostName:(localHost?.getHostName() ?: 'localhost'), runThread:Thread.currentThread().getName(),
                            startTime:threadEci.user.nowTimestamp] as Map<String, Object>)
                        .disableAuthz().call()

                if (lastRunTime != (Object) null) parameters.put("lastRunTime", lastRunTime)

                // NOTE: authz is disabled because authz is checked before queueing
                Map<String, Object> results = new HashMap<>()
                try {
                    results = ecfi.serviceFacade.sync().name(serviceName).parameters(parameters)
                            .transactionTimeout(transactionTimeout).disableAuthz().call()
                } catch (Throwable t) {
                    logger.error("Error in service job call", t)
                    threadEci.getMessage().addError(t.toString())
                }

                // set endTime, results, messages, errors on ServiceJobRun
                if (results.containsKey(null)) {
                    logger.warn("Service Job ${jobName} results has a null key with value ${results.get(null)}, removing")
                    results.remove(null)
                }
                String resultString = (String) null
                try {
                    resultString = JsonOutput.toJson(results)
                } catch (Exception e) {
                    logger.warn("Error writing JSON for Service Job ${jobName} results: ${e.toString()}\n${results}")
                }
                boolean hasError = threadEci.getMessage().hasError()
                String messages = threadEci.getMessage().getMessagesString()
                if (messages != null && messages.length() > 4000) messages = messages.substring(0, 4000)
                String errors = hasError ? threadEci.getMessage().getErrorsString() : null
                if (errors != null && errors.length() > 4000) errors = errors.substring(0, 4000)
                Timestamp nowTimestamp = threadEci.getUser().nowTimestamp

                // before calling other services clear out errors or they won't run
                if (hasError) threadEci.getMessage().clearErrors()

                // clear the ServiceJobRunLock if there is one
                if (clearLock) {
                    ServiceCallSync scs = ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRunLock")
                            .parameter("jobName", jobName).parameter("jobRunId", null)
                            .disableAuthz()
                    // if there was an error set lastRunTime to previous
                    if (hasError) scs.parameter("lastRunTime", lastRunTime)
                    scs.call()
                }

                // NOTE: no need to run async or separate thread, is in separate TX because no wrapping TX for these service calls
                ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRun")
                        .parameters([jobRunId:jobRunId, endTime:nowTimestamp, results:resultString,
                            messages:messages, hasError:(hasError ? 'Y' : 'N'), errors:errors] as Map<String, Object>)
                        .disableAuthz().call()

                // if topic send NotificationMessage
                if (topic) {
                    throw new BaseException("no implemented")
//                    NotificationMessage nm = threadEci.makeNotificationMessage().topic(topic)
//                    Map<String, Object> msgMap = new HashMap<>()
//                    msgMap.put("serviceCallRun", [jobName:jobName, description:jobDescription, jobRunId:jobRunId,
//                            endTime:nowTimestamp, messages:messages, hasError:hasError, errors:errors])
//                    msgMap.put("parameters", parameters)
//                    msgMap.put("results", results)
//                    nm.message(msgMap)
//
//                    if (currentUserId) nm.userId(currentUserId)
//                    EntityList serviceJobUsers = threadEci.entity.find("moqui.service.job.ServiceJobUser")
//                            .condition("jobName", jobName).useCache(true).disableAuthz().list()
//                    for (EntityValue serviceJobUser in serviceJobUsers)
//                        if (serviceJobUser.receiveNotifications != 'N')
//                            nm.userId((String) serviceJobUser.userId)
//
//                    nm.type(hasError ? NotificationMessage.danger : NotificationMessage.success)
//                    nm.send()
                }

                return results
            } catch (Throwable t) {
                logger.error("Error in service job handling", t)
                // better to not throw? seems to cause issue with scheduler: throw t
                return null
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }
}
