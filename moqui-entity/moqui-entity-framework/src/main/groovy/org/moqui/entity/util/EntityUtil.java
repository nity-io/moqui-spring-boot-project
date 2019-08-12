package org.moqui.entity.util;

import org.moqui.MoquiEntity;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.*;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.util.BaseUtilDateTime;
import org.moqui.util.ObjectUtilities;
import org.moqui.util.UtilDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.*;

public class EntityUtil {

    private static final Logger log = LoggerFactory.getLogger(EntityUtil.class);

    /**
     * <p>
     * 返回用主键查询到的数据，没有主键会报错
     *
     * @param entityName
     * @param fields
     * @return
     * @throws EntityException
     */
    public static EntityValue findOne(String entityName, Map<String, Object> fields) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).one();
    }

    public static EntityValue findOne(String entityName, EntityCondition condition) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(condition).one();
    }

    public static EntityList findList(String entityName, EntityCondition condition) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(condition).list();
    }

    public static EntityList findList(String entityName, EntityCondition condition, int offset, int pageSize) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(condition).limit(pageSize).offset(offset).list();
    }

    public static EntityList findList(String entityName, Map<String, Object> fields, int offset, int pageSize) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).limit(pageSize).offset(offset).list();
    }

    public static EntityList findList(String entityName, Map<String, Object> fields) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).list();
    }

    public static EntityList findList(String entityName, List<String> fieldsToSelect, Map<String, Object> fields) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).selectFields(fieldsToSelect).condition(fields).list();
    }

    public static EntityList findList(String entityName, List<String> fieldsToSelect, EntityCondition condition) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).selectFields(fieldsToSelect).condition(condition).list();
    }

    public static EntityList findListSort(String entityName, EntityCondition condition, List<String> orderBy) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(condition).orderBy(orderBy).list();
    }

    public static EntityList findListSort(String entityName, Map<String, Object> fields, List<String> orderBy) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).orderBy(orderBy).list();
    }

    public static EntityList findListSort(String entityName, List<String> fieldsToSelect, EntityCondition condition, List<String> orderBy) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(condition).selectFields(fieldsToSelect).orderBy(orderBy).list();
    }

    public static EntityList findListSort(String entityName, List<String> fieldsToSelect, Map<String, Object> fields, List<String> orderBy) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).selectFields(fieldsToSelect).orderBy(orderBy).list();
    }

    public static EntityList findListCache(String entityName, EntityCondition condition) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(condition).useCache(true).list();
    }

    public static EntityList findListCache(String entityName, Map<String, Object> fields) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).useCache(true).list();
    }

    public static EntityList findListCache(String entityName, Collection<String> fieldsToSelect, Map<String, Object> fields) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).selectFields(fieldsToSelect).useCache(true).list();
    }

    public static EntityList findListSortCache(String entityName, EntityCondition condition, List<String> orderBy) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(condition).orderBy(orderBy).useCache(true).list();
    }

    public static EntityList findListSortCache(String entityName, Map<String, Object> fields, List<String> orderBy) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).orderBy(orderBy).useCache(true).list();
    }

    public static EntityValue getOnly(String entityName, EntityCondition condition) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        EntityList entityList = entity.find(entityName).condition(condition).list();
        return getOnly(entityList);
    }

    public static EntityValue getOnly(String entityName, List<String> fieldsToSelect, EntityCondition condition) throws EntityException {
        EntityList list = findList(entityName, fieldsToSelect, condition);
        return getOnly(list);
    }

    public static EntityValue getOnly(String entityName, Map<String, Object> fields) throws EntityException {
        EntityList list = findList(entityName, fields);
        return getOnly(list);
    }

    public static EntityValue getOnly(String entityName, List<String> fieldsToSelect, Map<String, Object> fields) throws EntityException {
        EntityList list = findList(entityName, fieldsToSelect, fields);
        return getOnly(list);
    }

    public static EntityValue getOnlyCache(String entityName, EntityCondition condition) throws EntityException {
        EntityList list = findListCache(entityName, condition);
        return getOnly(list);
    }

    public static EntityValue getOnlyCache(String entityName, Map<String, Object> fields) throws EntityException {
        EntityList list = findListCache(entityName, fields);
        return getOnly(list);
    }

    public static EntityValue getFirst(String entityName, Map<String, Object> fields) throws EntityException {
        return findList(entityName, fields).getFirst();
    }

    public static EntityValue getFirst(String entityName, EntityCondition condition) throws EntityException {
        return findList(entityName, condition).getFirst();
    }

    public static EntityValue getFirst(String entityName, List<String> fieldsToSelect, Map<String, Object> fields) throws EntityException {
        return findList(entityName, fieldsToSelect, fields).getFirst();
    }

    public static EntityValue getFirst(String entityName, List<String> fieldsToSelect, EntityCondition condition) throws EntityException {
        return findList(entityName, fieldsToSelect, condition).getFirst();
    }

    public static EntityValue getFirstCache(String entityName, Map<String, Object> fields) throws EntityException {
        return findListCache(entityName, fields).getFirst();
    }

    public static EntityValue getFirstCache(String entityName, EntityCondition condition) throws EntityException {
        return findListCache(entityName, condition).getFirst();
    }

    public static EntityValue getFirstSort(String entityName, Map<String, Object> fields, List<String> orderBy) throws EntityException {
        return findListSort(entityName, fields, orderBy).getFirst();
    }

    public static EntityValue getFirstSort(String entityName, EntityCondition condition, List<String> orderBy) throws EntityException {
        return findListSort(entityName, condition, orderBy).getFirst();
    }

    public static EntityValue getFirstSort(String entityName, List<String> fieldsToSelect, Map<String, Object> fields, List<String> orderBy) throws EntityException {
        return findListSort(entityName, fieldsToSelect, fields, orderBy).getFirst();
    }

    public static EntityValue getFirstSort(String entityName, List<String> fieldsToSelect, EntityCondition condition, List<String> orderBy) throws EntityException {
        return findListSort(entityName, fieldsToSelect, condition, orderBy).getFirst();
    }

    public static EntityValue getFirstSortCache(String entityName, Map<String, Object> fields, List<String> orderBy) throws EntityException {
        return findListSortCache(entityName, fields, orderBy).getFirst();
    }

    public static EntityValue getFirstSortCache(String entityName, EntityCondition condition, List<String> orderBy) throws EntityException {
        return findListSortCache(entityName, condition, orderBy).getFirst();
    }

    public static EntityList findAll(String entityName, List<String> fieldsToSelect) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).selectFields(fieldsToSelect).list();
    }

    public static EntityList findAllSort(String entityName, List<String> fieldsToSelect, List<String> orderBy) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).selectFields(fieldsToSelect).orderBy(orderBy).list();
    }

    public static long findCount(String entityName, Map<String, Object> fields) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(fields).count();
    }

    public static long findCount(String entityName, EntityCondition entityCondition) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(entityCondition).count();
    }

    private static EntityValue getOnly(EntityList entityList) {
        if (!ObjectUtilities.isEmpty(entityList)) {
            if (entityList.size() == 1) {
                return entityList.getFirst();
            } else {
                throw new IllegalArgumentException("Passed List had more than one value.");
            }
        } else {
            return null;
        }
    }

    public static EntityList topList(String entityName, EntityCondition condition, List<String> fieldsToSelect, List<String> orderBy, int count) throws EntityException {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        return entity.find(entityName).condition(condition).selectFields(fieldsToSelect).orderBy(orderBy).limit(count).offset(0).list();
    }

    public static EntityCondition getFilterByTodayExpr(String dateFieldName) {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();
        Timestamp now = BaseUtilDateTime.nowTimestamp();
        Timestamp todayStart = BaseUtilDateTime.getDayStart(now);
        Timestamp nextDayStart = BaseUtilDateTime.getNextDayStart(now);

        List<EntityCondition> entityConditionList = new ArrayList<>();

        entityConditionList.add(entity.getConditionFactory().makeCondition(dateFieldName, EntityCondition.GREATER_THAN_EQUAL_TO, todayStart));
        entityConditionList.add(entity.getConditionFactory().makeCondition(dateFieldName, EntityCondition.LESS_THAN, nextDayStart));

        EntityCondition entityCondition = entity.getConditionFactory().makeCondition(entityConditionList);

        return entityCondition;
    }

    public static EntityCondition getFilterByYesterdayExpr(String dateFieldName) {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();
        Timestamp yesterday = BaseUtilDateTime.addDaysToTimestamp(BaseUtilDateTime.nowTimestamp(), -1);
        Timestamp yesterdayStart = BaseUtilDateTime.getDayStart(yesterday);
        Timestamp nextDayStart = BaseUtilDateTime.getNextDayStart(yesterday);

        List<EntityCondition> entityConditionList = new ArrayList<>();

        entityConditionList.add(entity.getConditionFactory().makeCondition(dateFieldName, EntityCondition.GREATER_THAN_EQUAL_TO, yesterdayStart));
        entityConditionList.add(entity.getConditionFactory().makeCondition(dateFieldName, EntityCondition.LESS_THAN, nextDayStart));

        EntityCondition entityCondition = entity.getConditionFactory().makeCondition(entityConditionList);

        return entityCondition;
    }

    public static EntityCondition getFilterByDateExpr() {
        EntityFacade entity = MoquiEntity.getEntity();
        Timestamp compareStamp = UtilDateTime.nowTimestamp();
        return entity.getConditionFactory().makeConditionDate("fromDate", "thruDate", compareStamp);
    }

    public static EntityCondition getFilterByDateExpr(String fromDateName, String thruDateName) {
        EntityFacade entity = MoquiEntity.getEntity();
        Timestamp compareStamp = UtilDateTime.nowTimestamp();
        return entity.getConditionFactory().makeConditionDate(fromDateName, thruDateName, compareStamp);
    }

    public static EntityCondition getFilterByDateExpr(Date moment) {
        EntityFacade entity = MoquiEntity.getEntity();
        Timestamp compareStamp = new Timestamp(moment.getTime());
        return entity.getConditionFactory().makeConditionDate("fromDate", "thruDate", compareStamp);
    }

    public static EntityCondition getFilterByDateExpr(Timestamp compareStamp) {
        EntityFacade entity = MoquiEntity.getEntity();
        return entity.getConditionFactory().makeConditionDate("fromDate", "thruDate", compareStamp);
    }

    public static EntityCondition getFilterByDateExpr(Timestamp compareStamp, String fromDateName, String thruDateName) {
        EntityFacade entity = MoquiEntity.getEntity();
        return entity.getConditionFactory().makeConditionDate(fromDateName, thruDateName, compareStamp);
    }

    public static EntityList filterByCondition(EntityList entityList, EntityCondition condition) {
        return entityList.cloneList().filterByCondition(condition, true);
    }

    public static EntityList filterOutByCondition(EntityList entityList, EntityCondition condition) {
        return entityList.cloneList().filterByCondition(condition, false);
    }

    public static long removeByAnd(String entityName, Map<String, Object> fields) {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        long count = entity.find(entityName).condition(fields).deleteAll();
        return count;
    }

    /**
     * 获取ID序列
     * @param entityName
     * @return
     */
    public static String sequencedIdPrimary(String entityName) {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();
        EntityDefinition ed = entity.getEntityDefinition(entityName);

        String sequenceValue = entity.sequencedIdPrimaryEd(ed);

        return sequenceValue;
    }

    /**
     * 根据条件删除
     *
     * @param entityName
     * @param condition
     * @return
     */
    public static long deleteAll(String entityName, EntityCondition condition) {
        ExecutionContext ec = MoquiEntity.getExecutionContext();
        EntityFacade entity = ec.getEntity();

        long count = entity.find(entityName).condition(condition).deleteAll();

        return count;
    }
}