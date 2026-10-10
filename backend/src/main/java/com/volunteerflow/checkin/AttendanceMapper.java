package com.volunteerflow.checkin;

import com.volunteerflow.checkin.CheckinSessionService.SessionView;
import com.volunteerflow.checkin.CheckinViews.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Read-only SQL projections. Every caller authorizes the activity's organization first. */
@Mapper
public interface AttendanceMapper {
  @Select("""
      SELECT id AS session_id,activity_id,session_type,status,opens_at,closes_at
      FROM checkin_session
      WHERE organization_id=#{organizationId} AND activity_id=#{activityId} AND status='OPEN'
        AND opens_at <= #{now} AND closes_at > #{now}
      ORDER BY opens_at DESC,id DESC LIMIT 1
      """)
  @ConstructorArgs({
    @Arg(column = "session_id", javaType = Long.class),
    @Arg(column = "activity_id", javaType = Long.class),
    @Arg(column = "session_type", javaType = String.class),
    @Arg(column = "status", javaType = String.class),
    @Arg(column = "opens_at", javaType = LocalDateTime.class),
    @Arg(column = "closes_at", javaType = LocalDateTime.class)
  })
  SessionView selectCurrentSession(@Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId, @Param("now") LocalDateTime now);

  @Select("""
      SELECT c.registration_id,c.id AS registration_cycle_id,c.position_id,p.name AS position_name,
             c.attendance_status,r.id AS record_id,r.checked_in_at
      FROM registration_cycle c
      JOIN activity_position p ON p.id=c.position_id
        AND p.organization_id=c.organization_id AND p.activity_id=c.activity_id
      LEFT JOIN checkin_record r ON r.organization_id=c.organization_id
        AND r.activity_id=c.activity_id AND r.user_id=c.user_id
        AND r.registration_cycle_id=c.id AND r.status='VALID'
      WHERE c.organization_id=#{organizationId} AND c.activity_id=#{activityId}
        AND c.user_id=#{userId} AND c.status='CONFIRMED'
        AND NOT EXISTS (
          SELECT 1 FROM registration_cycle newer
          WHERE newer.registration_id=c.registration_id AND newer.cycle_number > c.cycle_number
        )
      ORDER BY c.cycle_number DESC,c.id DESC LIMIT 1
      """)
  @ConstructorArgs({
    @Arg(column = "registration_id", javaType = Long.class),
    @Arg(column = "registration_cycle_id", javaType = Long.class),
    @Arg(column = "position_id", javaType = Long.class),
    @Arg(column = "position_name", javaType = String.class),
    @Arg(column = "attendance_status", javaType = String.class),
    @Arg(column = "record_id", javaType = Long.class),
    @Arg(column = "checked_in_at", javaType = LocalDateTime.class)
  })
  OwnAttendance selectOwnAttendance(@Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId, @Param("userId") Long userId);

  @Select("""
      SELECT COUNT(c.id) AS confirmed_count,
             COALESCE(SUM(CASE WHEN c.attendance_status='CHECKED_IN' THEN 1 ELSE 0 END),0) AS checked_in_count,
             COALESCE(SUM(CASE WHEN c.attendance_status='NO_SHOW' THEN 1 ELSE 0 END),0) AS no_show_count,
             COALESCE(SUM(CASE WHEN c.attendance_status='PENDING' THEN 1 ELSE 0 END),0) AS pending_count
      FROM registration_cycle c
      WHERE c.organization_id=#{organizationId} AND c.activity_id=#{activityId} AND c.status='CONFIRMED'
        AND NOT EXISTS (
          SELECT 1 FROM registration_cycle newer
          WHERE newer.registration_id=c.registration_id AND newer.cycle_number > c.cycle_number
        )
      """)
  @ConstructorArgs({
    @Arg(column = "confirmed_count", javaType = Long.class),
    @Arg(column = "checked_in_count", javaType = Long.class),
    @Arg(column = "no_show_count", javaType = Long.class),
    @Arg(column = "pending_count", javaType = Long.class)
  })
  AttendanceCounts selectTotals(@Param("organizationId") Long organizationId, @Param("activityId") Long activityId);

  @Select("""
      SELECT p.id AS position_id,p.name AS position_name,COUNT(c.id) AS confirmed_count,
             COALESCE(SUM(CASE WHEN c.attendance_status='CHECKED_IN' THEN 1 ELSE 0 END),0) AS checked_in_count,
             COALESCE(SUM(CASE WHEN c.attendance_status='NO_SHOW' THEN 1 ELSE 0 END),0) AS no_show_count,
             COALESCE(SUM(CASE WHEN c.attendance_status='PENDING' THEN 1 ELSE 0 END),0) AS pending_count
      FROM activity_position p
      LEFT JOIN registration_cycle c ON c.position_id=p.id
        AND c.organization_id=p.organization_id AND c.activity_id=p.activity_id AND c.status='CONFIRMED'
        AND NOT EXISTS (
          SELECT 1 FROM registration_cycle newer
          WHERE newer.registration_id=c.registration_id AND newer.cycle_number > c.cycle_number
        )
      WHERE p.organization_id=#{organizationId} AND p.activity_id=#{activityId}
      GROUP BY p.id,p.name ORDER BY p.id
      """)
  @ConstructorArgs({
    @Arg(column = "position_id", javaType = Long.class),
    @Arg(column = "position_name", javaType = String.class),
    @Arg(column = "confirmed_count", javaType = Long.class),
    @Arg(column = "checked_in_count", javaType = Long.class),
    @Arg(column = "no_show_count", javaType = Long.class),
    @Arg(column = "pending_count", javaType = Long.class)
  })
  List<PositionCounts> selectPositions(@Param("organizationId") Long organizationId, @Param("activityId") Long activityId);

  /** Routing only. The mutation service re-reads under activity/session locks. */
  @Select("""
      SELECT s.activity_id,s.organization_id FROM checkin_session s
      JOIN activity a ON a.id=s.activity_id AND a.organization_id=s.organization_id
      WHERE s.id=#{sessionId}
      """)
  @ConstructorArgs({
    @Arg(column = "activity_id", javaType = Long.class),
    @Arg(column = "organization_id", javaType = Long.class)
  })
  SessionReference selectSessionReference(@Param("sessionId") Long sessionId);

  /** Select latest even when canceled, so no adapter can resurrect an earlier CONFIRMED cycle. */
  @Select("""
      SELECT c.id AS registration_cycle_id,c.status FROM registration_cycle c
      JOIN registration g ON g.id=c.registration_id
        AND g.organization_id=c.organization_id AND g.activity_id=c.activity_id AND g.user_id=c.user_id
      WHERE c.organization_id=#{organizationId} AND c.activity_id=#{activityId} AND c.registration_id=#{registrationId}
        AND NOT EXISTS (
          SELECT 1 FROM registration_cycle newer
          WHERE newer.registration_id=c.registration_id AND newer.cycle_number > c.cycle_number
        )
      ORDER BY c.cycle_number DESC,c.id DESC LIMIT 1
      """)
  @ConstructorArgs({
    @Arg(column = "registration_cycle_id", javaType = Long.class),
    @Arg(column = "status", javaType = String.class)
  })
  RegistrationTarget selectRegistrationTarget(@Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId, @Param("registrationId") Long registrationId);
}
