package org.moqui.util;

import org.apache.commons.lang3.time.DateUtils;
import org.joda.time.*;
import org.moqui.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class UtilDateTime extends BaseUtilDateTime {

    public static final String module = UtilDateTime.class.getName();

    public static final Set<String> PARSE_PATTERNS = new HashSet<>();

    public static final String DATE_NUMBER_FORMAT = "yyyyMMdd";
    public static final String DATE_TIME_NUMBER_FORMAT = "yyyyMMddHHmmss";
    private static Logger logger = LoggerFactory.getLogger(module);

    static {

        PARSE_PATTERNS.add("yyyyMMdd");
        PARSE_PATTERNS.add("yyyy.MM.dd");
        PARSE_PATTERNS.add("yyyy/MM/dd");
        PARSE_PATTERNS.add("yyyy-MM-dd");
        PARSE_PATTERNS.add("yyyy-MM-dd HH:mm:ss.SSS");
        PARSE_PATTERNS.add("yyyy-MM-dd HH:mm:ss");
        PARSE_PATTERNS.add("yyyyMMddHHmmss");
        PARSE_PATTERNS.add("yyyy-MM");
        PARSE_PATTERNS.add("yyyyMM");
    }

    public static Timestamp parseTimestamp(String str) {
        Date newDate = parseDate(str);

        if (newDate != null) {
            return new Timestamp(newDate.getTime());
        } else {
            return null;
        }
    }

    public static Timestamp parseTimestamp(String str, String dateFromat) {
        Date newDate = parseDate(str, dateFromat);

        if (newDate != null) {
            return new Timestamp(newDate.getTime());
        } else {
            return null;
        }
    }

    public static Date parseDate(String str) {
        try {
            return DateUtils.parseDate(str, PARSE_PATTERNS.toArray(new String[PARSE_PATTERNS.size()]));
        } catch (ParseException e) {
            logger.error(module, e);
            throw new BaseException("无法解析日期：" + str, e);
        }
    }

    public static Date parseDate(String str, String dateFromat) {
        Set<String> patterns = new HashSet<>();
        patterns.add(dateFromat);
        patterns.addAll(PARSE_PATTERNS);
        try {
            return DateUtils.parseDate(str, patterns.toArray(new String[patterns.size()]));
        } catch (ParseException e) {
            logger.error(module, e);
            throw new BaseException("无法解析日期：" + str, e);
        }
    }

    public static java.sql.Date parseSqlDate(String str, String format) {
        return new java.sql.Date(parseDate(str, format).getTime());
    }

    public static java.sql.Date nowSqlDate() {
        return parseSqlDate(UtilDateTime.nowDate2String(), DATE_FORMAT);
    }


    private static Timestamp praseDateTime2Timestamp(DateTime dateTime) {
        return new Timestamp(dateTime.getMillis());
    }

    /**
     * 获得日期相加N个月之后的时间
     */
    public static Timestamp getMonthRangeTimestamp(Object date, int monthsLater) {
        DateTime dateTime = new DateTime(date);
        DateTime plusMonthsDateTime = dateTime.plusMonths(monthsLater).withTime(0, 0, 0, 0);

        return praseDateTime2Timestamp(plusMonthsDateTime);
    }

    /**
     * 获得日期相加N个月之后的时间(精确到时分秒)
     */
    public static Timestamp addMonthRangeTimestamp(Object date, int monthsLater) {
        DateTime dateTime = new DateTime(date);
        DateTime plusMonthsDateTime = dateTime.plusMonths(monthsLater);

        return praseDateTime2Timestamp(plusMonthsDateTime);
    }

    /**
     * 获得日期相加N个月之后的时间
     */
    public static DateTime getMonthRangeDateTime(Object date, int monthsLater) {
        DateTime dateTime = new DateTime(date);
        DateTime plusMonthsDateTime = dateTime.plusMonths(monthsLater).withTime(0, 0, 0, 0);

        return plusMonthsDateTime;
    }

    /**
     * 获得日期相加月份之后的第一天开始时间
     */
    public static Timestamp getMonthRangeStart(Object date, int monthsLater) {
        DateTime plusMonthsDateTime = getMonthRangeDateTime(date, monthsLater);

        return new Timestamp(plusMonthsDateTime.dayOfMonth().withMinimumValue().withTime(0, 0, 0, 0).getMillis());
    }

    /**
     * 获得日期相加月份之后的最后一天结束时间
     */
    public static Timestamp getMonthRangeEnd(Object date, int monthsLater) {
        DateTime dateTime = new DateTime(date);
        DateTime plusMonthsDateTime = dateTime.plusMonths(monthsLater).dayOfMonth().withMaximumValue().withTime(23, 59, 59, 999);

        return praseDateTime2Timestamp(plusMonthsDateTime);
    }

    public static String dateString(Date date) {
        SimpleDateFormat df = new SimpleDateFormat(DATE_FORMAT);
        return df.format(date);
    }

    public static String dateString(Date date, String format) {
        SimpleDateFormat df = new SimpleDateFormat(format);
        return df.format(date);
    }

    public static String dateTimeString(Date date) {
        SimpleDateFormat df = new SimpleDateFormat(DATE_FORMAT + " " + TIME_FORMAT);
        return df.format(date);
    }

    public static String formatDate2String(Object date, String formatString) {
        DateTime dateTime = new DateTime(date);

        return dateTime.toString(formatString);
    }

    public static Date getNowDateStart() {
        return getDateStart(nowDate());
    }

    public static Date getNowDateEnd() {
        return getDateEnd(nowDate());
    }

    public static String getTodayStartString() {
        DateTime dateTime = new DateTime(nowDate());
        DateTime newDateTime = dateTime.withTime(0, 0, 0, 0);

        return newDateTime.toString("yyyy-MM-dd HH:mm:ss");
    }

    public static Date getDateStart(Date date) {
        DateTime dateTime = new DateTime(date);
        DateTime newDateTime = dateTime.withTime(0, 0, 0, 0);

        return newDateTime.toDate();
    }

    public static Date getDateEnd(Date date) {
        DateTime dateTime = new DateTime(date);
        DateTime newDateTime = dateTime.withTime(23, 59, 59, 999);

        return newDateTime.toDate();
    }

    /**
     * 获取两个日期区间每一天的日期
     *
     * @return List
     */
    public static List<String> getEveryDayDateFromStartDateAndEndDate(Object startDate, Object endDate, String format) throws ParseException {
        List<String> dateTimeList = new ArrayList<String>();

        DateTime start = new DateTime(startDate);
        DateTime end = new DateTime(endDate);
        int daysInterval = Days.daysBetween(start, end).getDays();

        for (int i = 0; i <= daysInterval; i++) {
            DateTime nextDateTime = start.plusDays(i);
            dateTimeList.add(nextDateTime.toString(format));
        }

        return dateTimeList;
    }

    /**
     * 获取两个日期区间相差的天数
     *
     * @return List
     */
    public static int getDaysIntervalFromStartDateToEndDate(Object startDate, Object endDate) throws ParseException {
        DateTime start = new DateTime(startDate);
        DateTime end = new DateTime(endDate);
        int daysInterval = Days.daysBetween(start, end).getDays();

        return daysInterval;
    }

    public static String formatDate2String(Timestamp stamp, String format) {
        SimpleDateFormat df = new SimpleDateFormat(format);
        return df.format(stamp);
    }

    public static String nowDate2String() {
        return nowDateString(DATE_FORMAT);
    }

    /**
     * 获取两个日期区间相差的月份数
     */
    public static int getMonthInterval(Object startDateTime, Object endDateTime) {
        DateTime start = new DateTime(startDateTime);
        DateTime end = new DateTime(endDateTime);

        return Months.monthsBetween(start, end).getMonths();
    }

    /**
     * 获取两个日期区间相差的分钟数
     */
    public static int getMinutesInterval(Object startDateTime, Object endDateTime) {
        DateTime start = new DateTime(startDateTime);
        DateTime end = new DateTime(endDateTime);

        return Minutes.minutesBetween(start, end).getMinutes();
    }

    /**
     * 获取两个日期区间相差的小时数
     */
    public static int getHoursInterval(Object startDateTime, Object endDateTime) {
        DateTime start = new DateTime(startDateTime);
        DateTime end = new DateTime(endDateTime);

        return Hours.hoursBetween(start, end).getHours();
    }

    /**
     * 时间计算（传入的时间加上分钟数，返回相加之后的时间）
     */
    public static Timestamp addMinuteToTimestamp(Object dateTime, int minutes) {
        DateTime dateTimeObj = new DateTime(dateTime);
        DateTime dateTimePlusObj = dateTimeObj.plusMinutes(minutes);

        Timestamp timestamp = new Timestamp(dateTimePlusObj.getMillis());

        return timestamp;
    }

    /**
     * 开始时间是否在结束时间之前
     */
    public static boolean isBefore(Object startDateTime, Object endDateTime) {
        DateTime start = new DateTime(startDateTime);
        DateTime end = new DateTime(endDateTime);

        return start.isBefore(end.getMillis());
    }


    public static boolean isTimeActive(Timestamp startTimeStamp, Timestamp endTimeStamp, Timestamp moment) {
        if (ObjectUtilities.isEmpty(startTimeStamp) && ObjectUtilities.isEmpty(endTimeStamp)){
            return true;
        }

        Timestamp fromDate = null;
        Timestamp thruDate = null;

        String date = formatDate2String(moment, DATE_FORMAT);

        if (!ObjectUtilities.isEmpty(startTimeStamp)) {
            String time = formatDate2String(startTimeStamp, TIME_FORMAT);
            String dateTime = date + " " + time;
            fromDate = UtilDateTime.parseTimestamp(dateTime);
        }

        if (!ObjectUtilities.isEmpty(endTimeStamp)) {
            String time = formatDate2String(endTimeStamp, TIME_FORMAT);
            String dateTime = date + " " + time;
            thruDate = UtilDateTime.parseTimestamp(dateTime);
        }

        if ((thruDate == null || thruDate.after(moment)) && (fromDate == null || fromDate.before(moment) || fromDate.equals(moment))) {
            return true;
        } else {
            return false;
        }
    }

    public static int getIntervalInDaysOverride(Timestamp from, Timestamp thru) {
        String fromDate = formatDate2String(from, DATE_FORMAT);
        String thruDate = formatDate2String(thru, DATE_FORMAT);

        int intervalInDays = getIntervalInDays(UtilDateTime.parseTimestamp(fromDate), UtilDateTime.parseTimestamp(thruDate));

        return intervalInDays;
    }

    //获取某月第一天的日期
    public static String getFirstDateOfMonth(Object date) {
        LocalDate now = new LocalDate(date);
        LocalDate firstDayOfMonth = now.dayOfMonth().withMinimumValue();
        return firstDayOfMonth.toString();
    }

    //获取某月最后一天的日期
    public static String getLastDateOfMonth(Object date) {
        LocalDate now = new LocalDate(date);
        LocalDate firstDayOfMonth = now.dayOfMonth().withMaximumValue();
        return firstDayOfMonth.toString();
    }

    /**
     * 时间计算（传入的时间加上天数，返回相加之后的时间）
     */
    public static Timestamp addDayToTimestamp(Object dateTime, int day) {
        DateTime dateTimeObj = new DateTime(dateTime);
        DateTime dateTimePlusObj = dateTimeObj.plusDays(day);

        Timestamp timestamp = new Timestamp(dateTimePlusObj.getMillis());

        return timestamp;
    }


    //每个月第一天时间
    public static List<String> getFirstDayDateEveryMonth(Timestamp date) {
        List<String> dateTimeList = new ArrayList<String>();
        Timestamp yearStart = getYearStart(date);
        DateTime dateTime = new DateTime(yearStart);

        for (int i = 0; i <= 12; i++) {
            DateTime nextDateTime = dateTime.plusMonths(i);
            dateTimeList.add(nextDateTime.toString(DATE_FORMAT));
        }

        return dateTimeList;
    }
}