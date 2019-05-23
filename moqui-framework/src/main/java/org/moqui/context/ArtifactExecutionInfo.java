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
package org.moqui.context;

import java.io.Writer;
import java.math.BigDecimal;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Information about execution of an artifact as the system is running */
@SuppressWarnings("unused")
public interface ArtifactExecutionInfo {
    enum ArtifactType { AT_XML_SCREEN, AT_XML_SCREEN_TRANS, AT_XML_SCREEN_CONTENT, AT_SERVICE, AT_ENTITY, AT_REST_PATH, AT_OTHER }
    enum AuthzAction { AUTHZA_VIEW, AUTHZA_CREATE, AUTHZA_UPDATE, AUTHZA_DELETE, AUTHZA_ALL }
    enum AuthzType { AUTHZT_ALLOW, AUTHZT_DENY, AUTHZT_ALWAYS }

    ArtifactType AT_XML_SCREEN = ArtifactType.AT_XML_SCREEN;
    ArtifactType AT_XML_SCREEN_TRANS = ArtifactType.AT_XML_SCREEN_TRANS;
    ArtifactType AT_XML_SCREEN_CONTENT = ArtifactType.AT_XML_SCREEN_CONTENT;
    ArtifactType AT_SERVICE = ArtifactType.AT_SERVICE;
    ArtifactType AT_ENTITY = ArtifactType.AT_ENTITY;
    ArtifactType AT_REST_PATH = ArtifactType.AT_REST_PATH;
    ArtifactType AT_OTHER = ArtifactType.AT_OTHER;

    AuthzAction AUTHZA_VIEW = AuthzAction.AUTHZA_VIEW;
    AuthzAction AUTHZA_CREATE = AuthzAction.AUTHZA_CREATE;
    AuthzAction AUTHZA_UPDATE = AuthzAction.AUTHZA_UPDATE;
    AuthzAction AUTHZA_DELETE = AuthzAction.AUTHZA_DELETE;
    AuthzAction AUTHZA_ALL = AuthzAction.AUTHZA_ALL;
    Map<String, AuthzAction> authzActionByName = Collections.unmodifiableMap(Stream.of(
            new SimpleEntry<>("view", AUTHZA_VIEW), new SimpleEntry<>("create", AUTHZA_CREATE),
            new SimpleEntry<>("update", AUTHZA_UPDATE), new SimpleEntry<>("delete", AUTHZA_DELETE),
            new SimpleEntry<>("all", AUTHZA_ALL)).collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue)));

    AuthzType AUTHZT_ALLOW = AuthzType.AUTHZT_ALLOW;
    AuthzType AUTHZT_DENY = AuthzType.AUTHZT_DENY;
    AuthzType AUTHZT_ALWAYS = AuthzType.AUTHZT_ALWAYS;

    String getName();
    ArtifactType getTypeEnum();
    String getTypeDescription();
    AuthzAction getActionEnum();
    String getActionDescription();

    String getAuthorizedUserId();
    void setAuthorizedUserId(String authorizedUserId);
    AuthzType getAuthorizedAuthzType();
    void setAuthorizedAuthzType(AuthzType authorizedAuthzType);
    AuthzAction getAuthorizedActionEnum();
    void setAuthorizedActionEnum(AuthzAction authorizedActionEnum);
    boolean isAuthorizationInheritable();
    void setAuthorizationInheritable(boolean isAuthorizationInheritable);
    boolean getAuthorizationWasRequired();
    boolean getAuthorizationWasGranted();

    long getRunningTime();
    void calcChildTime(boolean recurse);
    BigDecimal getRunningTimeMillis();
    long getThisRunningTime();
    BigDecimal getThisRunningTimeMillis();
    long getChildrenRunningTime();
    BigDecimal getChildrenRunningTimeMillis();
    void setParent(ArtifactExecutionInfo parentAeii);
    List<ArtifactExecutionInfo> getChildList();
    ArtifactExecutionInfo getParent();
    BigDecimal getPercentOfParentTime();

    void print(Writer writer, int level, boolean children);
    void addToMapByTime(Map<String, Map<String, Object>> timeByArtifact, boolean ownTime);
    void consolidateArtifactInfo(List<Map> topLevelList, Map<String, Map<String, Object>> flatMap, Map parentArtifactMap);
    String toBasicString();

}
