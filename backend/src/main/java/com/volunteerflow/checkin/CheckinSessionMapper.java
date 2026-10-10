package com.volunteerflow.checkin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CheckinSessionMapper extends BaseMapper<CheckinSession> {
  /** Digest bytes become their database representation only at this persistence boundary. */
  default int insertWithDigest(CheckinSession session, byte[] digest) {
    if (digest == null || digest.length != 32) {
      throw new IllegalArgumentException("A SHA-256 digest is required");
    }
    session.setStaticCodeHash(HexFormat.of().formatHex(digest));
    return insert(session);
  }

  /** The caller must already hold the parent activity row lock. */
  @Select("""
      SELECT * FROM checkin_session
      WHERE activity_id=#{activityId} AND status='OPEN' AND closes_at > #{now}
      ORDER BY opens_at DESC,id DESC LIMIT 1 FOR UPDATE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  CheckinSession selectEffectiveOpenForUpdate(
      @Param("activityId") Long activityId, @Param("now") LocalDateTime now);

  @Select("""
      SELECT * FROM checkin_session
      WHERE activity_id=#{activityId} AND status='OPEN'
        AND opens_at <= #{now} AND closes_at > #{now}
      ORDER BY opens_at DESC,id DESC LIMIT 1
      """)
  CheckinSession selectEffectiveOpen(
      @Param("activityId") Long activityId, @Param("now") LocalDateTime now);

  /** Activity lock precedes even these implicit UPDATE row locks. */
  @Update("""
      UPDATE checkin_session SET status='EXPIRED', version=version+1
      WHERE activity_id=#{activityId} AND status='OPEN' AND closes_at <= #{now}
      """)
  int expireOpen(@Param("activityId") Long activityId, @Param("now") LocalDateTime now);

  @Select("""
      SELECT * FROM checkin_session
      WHERE id=#{sessionId} AND activity_id=#{activityId} FOR UPDATE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  CheckinSession selectByIdForUpdate(
      @Param("sessionId") Long sessionId, @Param("activityId") Long activityId);

  @Update("""
      UPDATE checkin_session
      SET status=#{status},
          closes_at=CASE WHEN #{status}='CLOSED' THEN #{now} ELSE closes_at END,
          version=version+1
      WHERE id=#{sessionId} AND activity_id=#{activityId} AND status='OPEN'
      """)
  int closeOpen(
      @Param("sessionId") Long sessionId,
      @Param("activityId") Long activityId,
      @Param("status") String status,
      @Param("now") LocalDateTime now);

  @Select("""
      SELECT DISTINCT user_id FROM registration_cycle
      WHERE organization_id=#{organizationId} AND activity_id=#{activityId}
        AND status='CONFIRMED'
      ORDER BY user_id
      """)
  List<Long> selectConfirmedUserIds(
      @Param("organizationId") Long organizationId, @Param("activityId") Long activityId);
}
