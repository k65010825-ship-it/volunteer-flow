package com.volunteerflow.checkin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CheckinRecordMapper extends BaseMapper<CheckinRecord> {
  /** The parent activity/session, membership and effective cycle are locked first. */
  @Select("SELECT * FROM checkin_record WHERE activity_id=#{activityId} AND user_id=#{userId} FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  CheckinRecord selectByActivityAndUserForUpdate(
      @Param("activityId") Long activityId, @Param("userId") Long userId);
}
