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
import org.moqui.BaseException
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactExecutionFacade
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ArtifactExecutionFacadeImpl implements ArtifactExecutionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ArtifactExecutionFacadeImpl.class)

    protected ExecutionContextImpl eci
    protected LinkedList<ArtifactExecutionInfo> artifactExecutionInfoStack = new LinkedList<ArtifactExecutionInfo>()
    protected LinkedList<ArtifactExecutionInfo> artifactExecutionInfoHistory = new LinkedList<ArtifactExecutionInfo>()

    // this is used by ScreenUrlInfo.isPermitted() which is called a lot, but that is transient so put here to have one per EC instance
    protected Map<String, Boolean> screenPermittedCache = null

    protected boolean authzDisabled = false
    protected boolean tarpitDisabled = false
    protected boolean entityEcaDisabled = false
    protected boolean entityAuditLogDisabled = false
    protected boolean entityFkCreateDisabled = false
    protected boolean entityDataFeedDisabled = false

    ArtifactExecutionFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
    }

    Map<String, Boolean> getScreenPermittedCache() {
        if (screenPermittedCache == null) screenPermittedCache = new HashMap<>()
        return screenPermittedCache
    }

    @Override
    ArtifactExecutionInfo peek() { return this.artifactExecutionInfoStack.peekFirst() }

    @Override
    ArtifactExecutionInfo push(String name, ArtifactExecutionInfo.ArtifactType typeEnum, ArtifactExecutionInfo.AuthzAction actionEnum, boolean requiresAuthz) {
        ArtifactExecutionInfoImpl aeii = new ArtifactExecutionInfoImpl(name, typeEnum, actionEnum, "")
        pushInternal(aeii, requiresAuthz, true)
        return aeii
    }
    @Override
    void push(ArtifactExecutionInfo aei, boolean requiresAuthz) {
        ArtifactExecutionInfoImpl aeii = (ArtifactExecutionInfoImpl) aei
        pushInternal(aeii, requiresAuthz, true)
    }
    @Override
    void pushInternal(ArtifactExecutionInfo aeii, boolean requiresAuthz, boolean countTarpit) {
        ArtifactExecutionInfoImpl lastAeii = (ArtifactExecutionInfoImpl) artifactExecutionInfoStack.peekFirst()

        // always do this regardless of the authz checks, etc; keep a history of artifacts run
        if (lastAeii != null) { lastAeii.addChild(aeii); aeii.setParent(lastAeii) }
        else artifactExecutionInfoHistory.add(aeii)

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact push ${username} - ${aeii}")

        if (!isPermitted(aeii, lastAeii, requiresAuthz, countTarpit, true, null)) {
            Deque<ArtifactExecutionInfo> curStack = getStack()
            StringBuilder warning = new StringBuilder()
            warning.append("User ${eci.user.username ?: eci.user.userId} is not authorized for ${aeii.getActionDescription()} on ${aeii.getTypeDescription()} ${aeii.getName()}")

            ArtifactAuthorizationException e = new ArtifactAuthorizationException(warning.toString(), aeii, curStack)
            // end users see this message in vuet mode so better not to add all of this to the main message:
            warning.append("\nCurrent artifact info: ${aeii.toString()}\n")
            warning.append("Current artifact stack:")
            for (ArtifactExecutionInfo warnAei in curStack) warning.append("\n").append(warnAei.toString())
            logger.warn("Artifact authorization failed: " + warning.toString())
            throw e
        }

        // NOTE: if needed the isPermitted method will set additional info in aeii
        this.artifactExecutionInfoStack.addFirst(aeii)
    }


    @Override
    ArtifactExecutionInfo pop(ArtifactExecutionInfo aei) {
        try {
            ArtifactExecutionInfoImpl lastAeii = (ArtifactExecutionInfoImpl) artifactExecutionInfoStack.removeFirst()
            // removed this for performance reasons, generally just checking the name is adequate
            // || aei.typeEnumId != lastAeii.typeEnumId || aei.actionEnumId != lastAeii.actionEnumId
            if (aei != null && !lastAeii.nameInternal.equals(aei.getName())) {
                String popMessage = "Popped artifact (${aei.name}:${aei.getTypeDescription()}:${aei.getActionDescription()}) did not match top of stack (${lastAeii.name}:${lastAeii.getTypeDescription()}:${lastAeii.getActionDescription()}:${lastAeii.actionDetail})"
                logger.warn(popMessage, new BaseException("Pop Error Location"))
                //throw new IllegalArgumentException(popMessage)
            }
            // set end time
            lastAeii.setEndTime()
            // count artifact hit (now done here instead of by each caller)
            //todo
            return lastAeii
        } catch(NoSuchElementException e) {
            logger.warn("Tried to pop from an empty ArtifactExecutionInfo stack", e)
            return null
        }
    }

    @Override
    Deque<ArtifactExecutionInfo> getStack() {
        Deque<ArtifactExecutionInfo> newStackDeque = new LinkedList<>()
        newStackDeque.addAll(this.artifactExecutionInfoStack)
        return newStackDeque
    }
    @Override
    String getStackNameString() {
        StringBuilder sb = new StringBuilder()
        Iterator i = this.artifactExecutionInfoStack.iterator()
        while (i.hasNext()) {
            ArtifactExecutionInfo aei = (ArtifactExecutionInfo) i.next()
            sb.append(aei.name)
            if (i.hasNext()) sb.append(', ')
        }
        return sb.toString()
    }
    @Override
    List<ArtifactExecutionInfo> getHistory() {
        List<ArtifactExecutionInfo> newHistList = new ArrayList<>()
        newHistList.addAll(this.artifactExecutionInfoHistory)
        return newHistList
    }

    String printHistory() {
        StringWriter sw = new StringWriter()
        for (ArtifactExecutionInfo aei in artifactExecutionInfoHistory) aei.print(sw, 0, true)
        return sw.toString()
    }

    void logProfilingDetail() {
        if (!logger.isInfoEnabled()) return

        StringWriter sw = new StringWriter()
        sw.append("========= Hot Spots by Own Time =========\n")
        sw.append("[{time}:{timeMin}:{timeAvg}:{timeMax}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map<String, Object>> ownHotSpotList = ArtifactExecutionInfoImpl.hotSpotByTime(artifactExecutionInfoHistory, true, "-time")
        ArtifactExecutionInfoImpl.printHotSpotList(sw, ownHotSpotList)
        logger.info(sw.toString())

        sw = new StringWriter()
        sw.append("========= Hot Spots by Total Time =========\n")
        sw.append("[{time}:{timeMin}:{timeAvg}:{timeMax}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map<String, Object>> totalHotSpotList = ArtifactExecutionInfoImpl.hotSpotByTime(artifactExecutionInfoHistory, false, "-time")
        ArtifactExecutionInfoImpl.printHotSpotList(sw, totalHotSpotList)
        logger.info(sw.toString())

        /* leave this out by default, sometimes interesting, but big
        sw = new StringWriter()
        sw.append("========= Consolidated Artifact List =========\n")
        sw.append("[{time}:{thisTime}:{childrenTime}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map> consolidatedList = ArtifactExecutionInfoImpl.consolidateArtifactInfo(artifactExecutionInfoHistory)
        ArtifactExecutionInfoImpl.printArtifactInfoList(sw, consolidatedList, 0)
        logger.info(sw.toString())
        */
    }


    void setAnonymousAuthorizedAll() {
        ArtifactExecutionInfo aeii = artifactExecutionInfoStack.peekFirst()
        aeii.setAuthorizationInheritable(true)
        aeii.setAuthorizedUserId(eci.getUser().getUserId() ?: "_NA_")
        if (aeii.authorizedAuthzType != ArtifactExecutionInfo.AUTHZT_ALWAYS) aeii.setAuthorizedAuthzType(ArtifactExecutionInfo.AUTHZT_ALLOW)
        aeii.setAuthorizedActionEnum(ArtifactExecutionInfo.AUTHZA_ALL)
    }

    void setAnonymousAuthorizedView() {
        ArtifactExecutionInfo aeii = artifactExecutionInfoStack.peekFirst()
        aeii.setAuthorizationInheritable(true)
        aeii.setAuthorizedUserId(eci.getUser().getUserId() ?: "_NA_")
        if (aeii.authorizedAuthzType != ArtifactExecutionInfo.AUTHZT_ALWAYS) aeii.setAuthorizedAuthzType(ArtifactExecutionInfo.AUTHZT_ALLOW)
        if (aeii.authorizedActionEnum != ArtifactExecutionInfo.AUTHZA_ALL) aeii.setAuthorizedActionEnum(ArtifactExecutionInfo.AUTHZA_VIEW)
    }

    boolean disableAuthz() { boolean alreadyDisabled = authzDisabled; authzDisabled = true; return alreadyDisabled }
    void enableAuthz() { authzDisabled = false }
    @Override
    boolean getAuthzDisabled() { return authzDisabled }

    boolean disableTarpit() { boolean alreadyDisabled = tarpitDisabled; tarpitDisabled = true; return alreadyDisabled }
    void enableTarpit() { tarpitDisabled = false }
    // boolean getTarpitDisabled() { return tarpitDisabled }

    @Override
    boolean disableEntityEca() { boolean alreadyDisabled = entityEcaDisabled; entityEcaDisabled = true; return alreadyDisabled }
    @Override
    void enableEntityEca() { entityEcaDisabled = false }
    @Override
    boolean entityEcaDisabled() { return entityEcaDisabled }

    @Override
    boolean disableEntityAuditLog() { boolean alreadyDisabled = entityAuditLogDisabled; entityAuditLogDisabled = true; return alreadyDisabled }
    @Override
    void enableEntityAuditLog() { entityAuditLogDisabled = false }
    @Override
    boolean entityAuditLogDisabled() { return entityAuditLogDisabled }

    @Override
    boolean disableEntityFkCreate() { boolean alreadyDisabled = entityFkCreateDisabled; entityFkCreateDisabled = true; return alreadyDisabled }
    @Override
    void enableEntityFkCreate() { entityFkCreateDisabled = false }
    @Override
    boolean entityFkCreateDisabled() { return entityFkCreateDisabled }

    @Override
    boolean disableEntityDataFeed() { boolean alreadyDisabled = entityDataFeedDisabled; entityDataFeedDisabled = true; return alreadyDisabled }
    @Override
    void enableEntityDataFeed() { entityDataFeedDisabled = false }
    @Override
    boolean entityDataFeedDisabled() { return entityDataFeedDisabled }

    /** Checks to see if username is permitted to access given resource.
     *
     * @param resourceAccess Formatted as: "${typeEnumId}:${actionEnumId}:${name}"
     * @param nowTimestamp
     * @param eci
     */
    static boolean isPermitted(String resourceAccess, ExecutionContext eci) {
        int firstColon = resourceAccess.indexOf(":")
        int secondColon = resourceAccess.indexOf(":", firstColon + 1)
        if (firstColon == -1 || secondColon == -1) throw new ArtifactAuthorizationException("Resource access string does not have two colons (':'), must be formatted like: \"\${typeEnumId}:\${actionEnumId}:\${name}\"", null, null)

        ArtifactExecutionInfo.ArtifactType typeEnum = ArtifactExecutionInfo.ArtifactType.valueOf(resourceAccess.substring(0, firstColon))
        ArtifactExecutionInfo.AuthzAction actionEnum = ArtifactExecutionInfo.AuthzAction.valueOf(resourceAccess.substring(firstColon + 1, secondColon))
        String name = resourceAccess.substring(secondColon + 1)

        return eci.getArtifactExecution().isPermitted(new ArtifactExecutionInfoImpl(name, typeEnum, actionEnum, ""),
                null, true, true, false, null)
    }

    @Override
    boolean isPermitted(ArtifactExecutionInfo aeii, ArtifactExecutionInfo lastAeii, boolean requiresAuthz, boolean countTarpit,
                        boolean isAccess, LinkedList<ArtifactExecutionInfo> currentStack) {
        return true
    }

    protected void checkTarpit(ArtifactExecutionInfoImpl aeii) {
        // logger.warn("Count tarpit ${aeii.toBasicString()}", new BaseException("loc"))
        //todo
    }

    static class AuthzFilterInfo {
        String entityFilterSetId
        Map entityFilter
        Map<String, ArrayList<MNode>> memberFieldAliases
        AuthzFilterInfo(String entityFilterSetId, Map entityFilter, Map<String, ArrayList<MNode>> memberFieldAliases) {
            this.entityFilterSetId = entityFilterSetId
            this.entityFilter = entityFilter
            this.memberFieldAliases = memberFieldAliases
        }
    }
    ArrayList<AuthzFilterInfo> getFindFiltersForUser(String findEntityName) {
        //todo
        return null
    }

}
