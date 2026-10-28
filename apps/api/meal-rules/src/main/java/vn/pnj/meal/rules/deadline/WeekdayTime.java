package vn.pnj.meal.rules.deadline;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A weekday and a time of day, Vietnam wall clock: the shape of the {@code registration.close} and
 * {@code menu.publishWarnAt} settings.
 */
public record WeekdayTime(DayOfWeek weekday, LocalTime time) {}
