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
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.AuthenticationRequiredException
import org.moqui.context.ExecutionContext
import org.moqui.context.UserFacade
import org.moqui.impl.context.ArtifactExecutionInfoImpl.ArtifactAuthzCheck
import org.moqui.util.MNode
import org.moqui.util.StringUtilities
import org.moqui.util.WebUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession
import java.sql.Timestamp

@CompileStatic
class UserFacadeImpl implements UserFacade {
    protected final static Logger logger = LoggerFactory.getLogger(UserFacadeImpl.class)
    protected final static Set<String> allUserGroupIdOnly = new HashSet(["ALL_USERS"])

    protected ExecutionContextImpl eci
    protected Timestamp effectiveTime = (Timestamp) null

    protected UserInfo currentInfo
    protected Deque<UserInfo> userInfoStack = new LinkedList<UserInfo>()

    // there may be non-web visits, so keep a copy of the visitId here
    protected String visitId = (String) null
    protected String visitorIdInternal = (String) null
    protected String clientIpInternal = (String) null

    // we mostly want this for the Locale default, and may be useful for other things
    protected HttpServletRequest request = (HttpServletRequest) null
    protected HttpServletResponse response = (HttpServletResponse) null
    // NOTE: a better practice is to always get from the request, but for WebSocket handshakes we don't have a request
    protected HttpSession session = (HttpSession) null

    UserFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
        pushUser(null)
    }

    @Override
    void initFromHttpRequest(HttpServletRequest request, HttpServletResponse response) {
        this.request = request
        this.response = response
        this.session = request.getSession()

        // get client IP address, handle proxy original address if exists
        String forwardedFor = request.getHeader("X-Forwarded-For")
        if (forwardedFor != null && !forwardedFor.isEmpty()) { clientIpInternal = forwardedFor.split(",")[0].trim() }
        else { clientIpInternal = request.getRemoteAddr() }

        String preUsername = getUsername()

        //todo
    }



    @Override Locale getLocale() { return currentInfo.localeCache }
    @Override void setLocale(Locale locale) {
        if (currentInfo.userAccount != null) {
            //todo
        }
        currentInfo.localeCache = locale
    }

    @Override TimeZone getTimeZone() { return currentInfo.tzCache }
    Calendar getCalendarSafe() {
        return Calendar.getInstance(currentInfo.tzCache != null ? currentInfo.tzCache : TimeZone.getDefault(),
                currentInfo.localeCache != null ? currentInfo.localeCache :
                        (request != null ? request.getLocale() : Locale.getDefault()))
    }
    @Override void setTimeZone(TimeZone tz) {
        if (currentInfo.userAccount != null) {
            //todo
        }
        currentInfo.tzCache = tz
    }

    @Override String getCurrencyUomId() { return currentInfo.currencyUomId }
    @Override void setCurrencyUomId(String uomId) {
        if (currentInfo.userAccount != null) {
            //todo
        }
        currentInfo.currencyUomId = uomId
    }

    @Override String getPreference(String preferenceKey) {
        String userId = getUserId()
        return getPreference(preferenceKey, userId)
    }
    String getPreference(String preferenceKey, String userId) {
        if (preferenceKey == null || preferenceKey.isEmpty()) return null

        // look in system properties for preferenceKey or key with '.' replaced by '_'; overrides DB values
        String sysPropVal = System.getProperty(preferenceKey)
        if (sysPropVal == null || sysPropVal.isEmpty()) {
            String underscoreKey = preferenceKey.replace('.' as char, '_' as char)
            sysPropVal = System.getProperty(underscoreKey)
        }
        if (sysPropVal != null && !sysPropVal.isEmpty()) return sysPropVal

        return null;
    }

    @Override Map<String, String> getPreferences(String keyRegexp) {
        String userId = getUserId()
        boolean hasKeyFilter = keyRegexp != null && !keyRegexp.isEmpty()

        Map<String, String> prefMap = new HashMap<>()

        return prefMap
    }

    @Override void setPreference(String preferenceKey, String preferenceValue) {
        String userId = getUserId()
        if (!userId) throw new IllegalStateException("Cannot set preference with key ${preferenceKey}, no user logged in.")

        //todo
    }

    @Override Map<String, Object> getContext() { return currentInfo.getUserContext() }

    @Override Timestamp getNowTimestamp() {
        // NOTE: review Timestamp and nowTimestamp use, have things use this by default (except audit/etc where actual date/time is needed
        return ((Object) this.effectiveTime != null) ? this.effectiveTime : new Timestamp(System.currentTimeMillis())
    }

    @Override Calendar getNowCalendar() {
        Calendar nowCal = getCalendarSafe()
        nowCal.setTimeInMillis(getNowTimestamp().getTime())
        return nowCal
    }

    @Override ArrayList<Timestamp> getPeriodRange(String period, String poffset) { return getPeriodRange(period, poffset, null) }
    @Override ArrayList<Timestamp> getPeriodRange(String period, String poffset, String pdate) {
        int offset = (poffset ?: "0") as int
        java.sql.Date sqlDate = (pdate != null && !pdate.isEmpty()) ? eci.l10nFacade.parseDate(pdate, null) : null
        return getPeriodRange(period, offset, sqlDate)
    }
    @Override ArrayList<Timestamp> getPeriodRange(String period, int offset, java.sql.Date sqlDate) {
        period = (period ?: "day").toLowerCase()
        boolean perIsNumber = Character.isDigit(period.charAt(0))

        Calendar basisCal = getCalendarSafe()
        if (sqlDate != null) basisCal.setTimeInMillis(sqlDate.getTime())
        basisCal.set(Calendar.HOUR_OF_DAY, 0); basisCal.set(Calendar.MINUTE, 0)
        basisCal.set(Calendar.SECOND, 0); basisCal.set(Calendar.MILLISECOND, 0)
        // this doesn't seem to work to set the time to midnight: basisCal.setTime(new java.sql.Date(nowTimestamp.time))
        Calendar fromCal = (Calendar) basisCal.clone()
        Calendar thruCal
        if (perIsNumber && period.endsWith("d")) {
            int days = Integer.parseInt(period.substring(0, period.length() - 1))
            if (offset < 0) {
                fromCal.add(Calendar.DAY_OF_YEAR, offset * days)
                thruCal = (Calendar) basisCal.clone()
                // also include today (or anchor date in pdate)
                thruCal.add(Calendar.DAY_OF_YEAR, 1)
            } else {
                // fromCal already set to basisCal, just set thruCal
                thruCal = (Calendar) basisCal.clone()
                thruCal.add(Calendar.DAY_OF_YEAR, (offset + 1) * days)
            }
        } else if (perIsNumber && period.endsWith("r")) {
            int days = Integer.parseInt(period.substring(0, period.length() - 1))
            if (offset < 0) offset = -offset
            fromCal.add(Calendar.DAY_OF_YEAR, -offset * days)
            thruCal = (Calendar) basisCal.clone()
            thruCal.add(Calendar.DAY_OF_YEAR, offset * days)
        } else if (period == "week") {
            fromCal.set(Calendar.DAY_OF_WEEK, fromCal.getFirstDayOfWeek())
            fromCal.add(Calendar.WEEK_OF_YEAR, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.WEEK_OF_YEAR, 1)
        } else if (period == "weeks") {
            if (offset < 0) {
                // from end of month of basis date go back offset months (add negative offset to from after copying for thru)
                fromCal.set(Calendar.DAY_OF_WEEK, fromCal.getFirstDayOfWeek())
                thruCal = (Calendar) fromCal.clone()
                thruCal.add(Calendar.WEEK_OF_YEAR, 1)
                fromCal.add(Calendar.WEEK_OF_YEAR, offset + 1)
            } else {
                // from beginning of month of basis date go forward offset months (add offset to thru)
                fromCal.set(Calendar.DAY_OF_WEEK, fromCal.getFirstDayOfWeek())
                thruCal = (Calendar) fromCal.clone()
                thruCal.add(Calendar.WEEK_OF_YEAR, offset == 0 ? 1 : offset)
            }
        } else if (period == "month") {
            fromCal.set(Calendar.DAY_OF_MONTH, fromCal.getActualMinimum(Calendar.DAY_OF_MONTH))
            fromCal.add(Calendar.MONTH, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.MONTH, 1)
        } else if (period == "months") {
            if (offset < 0) {
                // from end of month of basis date go back offset months (add negative offset to from after copying for thru)
                fromCal.set(Calendar.DAY_OF_MONTH, fromCal.getActualMinimum(Calendar.DAY_OF_MONTH))
                thruCal = (Calendar) fromCal.clone()
                thruCal.add(Calendar.MONTH, 1)
                fromCal.add(Calendar.MONTH, offset + 1)
            } else {
                // from beginning of month of basis date go forward offset months (add offset to thru)
                fromCal.set(Calendar.DAY_OF_MONTH, fromCal.getActualMinimum(Calendar.DAY_OF_MONTH))
                thruCal = (Calendar) fromCal.clone()
                thruCal.add(Calendar.MONTH, offset == 0 ? 1 : offset)
            }
        } else if (period == "quarter") {
            fromCal.set(Calendar.DAY_OF_MONTH, fromCal.getActualMinimum(Calendar.DAY_OF_MONTH))
            int quarterNumber = (fromCal.get(Calendar.MONTH) / 3) as int
            fromCal.set(Calendar.MONTH, (quarterNumber * 3))
            fromCal.add(Calendar.MONTH, (offset * 3))
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.MONTH, 3)
        } else if (period == "year") {
            fromCal.set(Calendar.DAY_OF_YEAR, fromCal.getActualMinimum(Calendar.DAY_OF_YEAR))
            fromCal.add(Calendar.YEAR, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.YEAR, 1)
        } else {
            // default to day
            fromCal.add(Calendar.DAY_OF_YEAR, offset)
            thruCal = (Calendar) fromCal.clone()
            thruCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        ArrayList<Timestamp> rangeList = new ArrayList<>(2)
        rangeList.add(new Timestamp(fromCal.getTimeInMillis()))
        rangeList.add(new Timestamp(thruCal.getTimeInMillis()))
        return rangeList
    }

    @Override String getPeriodDescription(String period, String poffset, String pdate) {
        ArrayList<Timestamp> rangeList = getPeriodRange(period, poffset, pdate)
        StringBuilder desc = new StringBuilder()
        if (poffset == "0") desc.append(eci.getL10n().localize("This"))
        else if (poffset == "-1") desc.append(eci.getL10n().localize("Last"))
        else if (poffset == "1") desc.append(eci.getL10n().localize("Next"))
        else desc.append(poffset)
        desc.append(' ')

        if (period == "day") desc.append(eci.getL10n().localize("Day"))
        else if (period == "7d") desc.append('7 ').append(eci.getL10n().localize("Days"))
        else if (period == "30d") desc.append('30 ').append(eci.getL10n().localize("Days"))
        else if (period == "week") desc.append(eci.getL10n().localize("Week"))
        else if (period == "weeks") desc.append(eci.getL10n().localize("Weeks"))
        else if (period == "month") desc.append(eci.getL10n().localize("Month"))
        else if (period == "months") desc.append(eci.getL10n().localize("Months"))
        else if (period == "quarter") desc.append(eci.getL10n().localize("Quarter"))
        else if (period == "year") desc.append(eci.getL10n().localize("Year"))
        else if (period == "7r") desc.append("+/-7d")
        else if (period == "30r") desc.append("+/-30d")

        if (pdate) desc.append(" ").append(eci.getL10n().localize("from##period")).append(" ").append(pdate)

        desc.append(" (").append(eci.l10n.format(rangeList[0], 'yyyy-MM-dd')).append(' ')
                .append(eci.getL10n().localize("to##period")).append(' ')
        //todo
//                .append(eci.l10n.format(rangeList[1] - 1, 'yyyy-MM-dd')).append(')')

        return desc.toString()
    }

    @Override ArrayList<Timestamp> getPeriodRange(String baseName, Map<String, Object> inputFieldsMap) {
        if (inputFieldsMap.get(baseName + "_period")) {
            return getPeriodRange((String) inputFieldsMap.get(baseName + "_period"),
                    (String) inputFieldsMap.get(baseName + "_poffset"), (String) inputFieldsMap.get(baseName + "_pdate"))
        } else {
            ArrayList<Timestamp> rangeList = new ArrayList<>(2)
            rangeList.add(null); rangeList.add(null)

            Object fromValue = inputFieldsMap.get(baseName + "_from")
            if (fromValue && fromValue instanceof CharSequence) {
                if (fromValue.length() < 12)
                    rangeList.set(0, eci.l10nFacade.parseTimestamp(fromValue.toString() + " 00:00:00.000", "yyyy-MM-dd HH:mm:ss.SSS"))
                else
                    rangeList.set(0, eci.l10nFacade.parseTimestamp(fromValue.toString(), null))
            } else if (fromValue instanceof Timestamp) {
                rangeList.set(0, (Timestamp) fromValue)
            }
            Object thruValue = inputFieldsMap.get(baseName + "_thru")
            if (thruValue && thruValue instanceof CharSequence) {
                if (thruValue.length() < 12)
                    rangeList.set(1, eci.l10nFacade.parseTimestamp(thruValue.toString() + " 23:59:59.999", "yyyy-MM-dd HH:mm:ss.SSS"))
                else
                    rangeList.set(1, eci.l10nFacade.parseTimestamp(thruValue.toString(), null))
            } else if (thruValue instanceof Timestamp) {
                rangeList.set(1, (Timestamp) thruValue)
            }

            return rangeList
        }
    }

    @Override void setEffectiveTime(Timestamp effectiveTime) { this.effectiveTime = effectiveTime }

    @Override boolean loginUser(String username, String password) {
        if (username == null || username.isEmpty()) {
            eci.messageFacade.addError(eci.l10n.localize("No username specified"))
            return false
        }
        if (password == null || password.isEmpty()) {
            eci.messageFacade.addError(eci.l10n.localize("No password specified"))
            return false
        }

        //todo

        return true
    }

    /** For internal framework use only, does a login without authc. */
    @Override
    boolean internalLoginUser(String username) { return internalLoginUser(username, true) }
    @Override
    boolean internalLoginUser(String username, boolean saveHistory) {
        if (username == null || username.isEmpty()) {
            eci.message.addError(eci.l10n.localize("No username specified"))
            return false
        }

        //todo

        return true
    }


    @Override void logoutUser() {

        String userId = getUserId()

        // pop from user stack, also calls Shiro logout()
        popUser()

        // if there is a request and session invalidate and get new
        if (request != null) {
            HttpSession oldSession = request.getSession(false)
            if (oldSession != null) oldSession.invalidate()
            session = request.getSession()
        }

        // if userId set hasLoggedOut
        if (userId != null && !userId.isEmpty()) {
//            eci.serviceFacade.sync().name("update", "moqui.security.UserAccount")
//                    .parameters([userId:userId, hasLoggedOut:"Y"]).disableAuthz().call()
        }
    }

    @Override boolean loginUserKey(String loginKey) {
        if (!loginKey) {
            eci.message.addError(eci.l10n.localize("No login key specified"))
            return false
        }

        //todo

        return internalLoginUser(loginKey)
    }
    @Override String getLoginKey() {
        String userId = getUserId()
        if (!userId) throw new AuthenticationRequiredException("No active user, cannot get login key")

        // generate login key
        String loginKey = StringUtilities.getRandomString(40)

        // save hashed in UserLoginKey, calc expire and set from/thru dates
//        String hashedKey = eci.ecfi.getSimpleHash(loginKey, "", eci.ecfi.getLoginKeyHashType(), false)
//        int expireHours = eci.ecfi.getLoginKeyExpireHours()
//        Timestamp fromDate = getNowTimestamp()
//        long thruTime = fromDate.getTime() + (expireHours * 60*60*1000)
//        eci.serviceFacade.sync().name("create", "moqui.security.UserLoginKey")
//                .parameters([loginKey:hashedKey, userId:userId, fromDate:fromDate, thruDate:new Timestamp(thruTime)])
//                .disableAuthz().requireNewTransaction(true).call()
//
//        // clean out expired keys
//        eci.entity.find("moqui.security.UserLoginKey").condition("userId", userId)
//                .condition("thruDate", EntityCondition.LESS_THAN, fromDate).disableAuthz().deleteAll()

        return loginKey
    }

    @Override boolean loginAnonymousIfNoUser() {
        if (currentInfo.username == null && !currentInfo.loggedInAnonymous) {
            currentInfo.loggedInAnonymous = true
            return true
        } else {
            return false
        }
    }
    @Override
    void logoutAnonymousOnly() { currentInfo.loggedInAnonymous = false }
    boolean getLoggedInAnonymous() { return currentInfo.loggedInAnonymous }

    @Override boolean hasPermission(String userPermissionId) {
        return hasPermissionById(getUserId(), userPermissionId, getNowTimestamp(), eci) }

    static boolean hasPermission(String username, String userPermissionId, Timestamp whenTimestamp, ExecutionContext eci) {
       //todo
        return true
    }
    static boolean hasPermissionById(String userId, String userPermissionId, Timestamp whenTimestamp, ExecutionContext eci) {
        if (!userId) return false
        //todo
        return false
    }

    @Override boolean isInGroup(String userGroupId) {
        //todo
        return false
    }
    static boolean isInGroup(String username, String userGroupId, Timestamp whenTimestamp, ExecutionContext eci) {
        //todo
        return false
    }

    @Override Set<String> getUserGroupIdSet() {
        // first get the groups the user is in (cached), always add the "ALL_USERS" group to it
        if (!currentInfo.userId) return allUserGroupIdOnly
        if (currentInfo.internalUserGroupIdSet == null) currentInfo.internalUserGroupIdSet = getUserGroupIdSet(currentInfo.userId)
        return currentInfo.internalUserGroupIdSet
    }

    Set<String> getUserGroupIdSet(String userId) {
        Set<String> groupIdSet = new HashSet(allUserGroupIdOnly)
        if (userId) {
            //todo
        }
        return groupIdSet
    }

    ArrayList<Map<String, Object>> getArtifactTarpitCheckList(ArtifactExecutionInfo.ArtifactType artifactTypeEnum) {
        ArrayList<Map<String, Object>> checkList = (ArrayList<Map<String, Object>>) currentInfo.internalArtifactTarpitCheckListMap.get(artifactTypeEnum)
        if (checkList == null) {
            // get the list for each group separately to increase cache hits/efficiency
            checkList = new ArrayList<>()
            for (String userGroupId in getUserGroupIdSet()) {
                //todo
            }
            currentInfo.internalArtifactTarpitCheckListMap.put(artifactTypeEnum, checkList)
        }
        return checkList
    }

    @Override String getUserId() { return currentInfo.userId }
    @Override String getUsername() { return currentInfo.username }
    @Override Map getUserAccount() { return currentInfo.getUserAccount() }

    @Override String getVisitUserId() { return visitId ? getVisit().userId : null }
    @Override String getVisitId() { return visitId }
    @Override Map getVisit() {
        //todo
        return [:]
    }
    @Override String getVisitorId() {
        if (visitorIdInternal != null) return visitorIdInternal
       //todo
        return visitorIdInternal
    }
    @Override String getClientIp() { return clientIpInternal }

    // ========== UserInfo ==========


    UserInfo pushUser(String username) {
        if (currentInfo != null && currentInfo.username == username) return currentInfo

        if (currentInfo == null || currentInfo.isPopulated()) {
            // logger.info("Pushing UserInfo for ${username} to stack, was ${currentInfo.username}")
            UserInfo userInfo = new UserInfo(this, username)
            userInfoStack.addFirst(userInfo)
            currentInfo = userInfo
            return userInfo
        } else {
            currentInfo.setInfo(username)
            return currentInfo
        }
    }

    void popUser() {
        //todo
        userInfoStack.removeFirst()

        // always leave at least an empty UserInfo on the stack
        if (userInfoStack.size() == 0) userInfoStack.addFirst(new UserInfo(this, null))

        UserInfo newCurInfo = userInfoStack.getFirst()
        // logger.info("Popping UserInfo ${currentInfo.username}, new current is ${newCurInfo.username}")

        // whether previous user on stack or new one, set the currentInfo
        currentInfo = newCurInfo
    }

    static class UserInfo {
        final UserFacadeImpl ufi
        // keep a reference to a UserAccount for performance reasons, avoid repeated cached queries
        protected Map userAccount = (Map) null
        protected String username = (String) null
        protected String userId = (String) null
        Set<String> internalUserGroupIdSet = (Set<String>) null
        // these two are used by ArtifactExecutionFacadeImpl but are maintained here to be cleared when user changes, are based on current user's groups
        final EnumMap<ArtifactExecutionInfo.ArtifactType, ArrayList<Map<String, Object>>> internalArtifactTarpitCheckListMap =
                new EnumMap<>(ArtifactExecutionInfo.ArtifactType.class)
        ArrayList<ArtifactAuthzCheck> internalArtifactAuthzCheckList = (ArrayList<ArtifactAuthzCheck>) null

        Locale localeCache = (Locale) null
        TimeZone tzCache = (TimeZone) null
        String currencyUomId = (String) null

        /** This is set instead of adding _NA_ user as logged in to pass authc tests but not generally behave as if a user is logged in */
        boolean loggedInAnonymous = false

        protected Map<String, Object> userContext = (Map<String, Object>) null

        UserInfo(UserFacadeImpl ufi, String username) {
            this.ufi = ufi
            setInfo(username)
        }

        boolean isPopulated() { return (username != null && username.length() > 0) || loggedInAnonymous }

        void setInfo(String username) {
            // this shouldn't happen unless there is a bug in the framework
            if (isPopulated()) throw new IllegalStateException("Cannot set user info, UserInfo already populated")

            this.username = username

            def ua = null

            if (ua != null) {
                //todo
            } else {
                // set defaults if no user
                localeCache = ufi.request != null ? ufi.request.getLocale() : Locale.getDefault()
                tzCache = TimeZone.getDefault()
            }

            internalUserGroupIdSet = (Set<String>) null
            internalArtifactTarpitCheckListMap.clear()
            internalArtifactAuthzCheckList = (ArrayList<ArtifactAuthzCheck>) null
        }

        String getUsername() { return username }
        String getUserId() { return userId }
        Map getUserAccount() { return userAccount }

        Map<String, Object> getUserContext() {
            if (userContext == null) userContext = new HashMap<>()
            return userContext
        }
    }
}
