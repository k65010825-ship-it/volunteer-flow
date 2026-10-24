package com.volunteerflow.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefreshSessionMapper extends BaseMapper<RefreshSession> {
  @Update(
      "UPDATE refresh_session SET revoked_at=#{now} WHERE user_id=#{userId} AND revoked_at IS NULL")
  int revokeAllForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

  @Select(
      """
      SELECT id, user_id, token_hash, device_name, expires_at, last_used_at, revoked_at,
             create_time, update_time
      FROM refresh_session
      WHERE token_hash = #{tokenHash}
      LIMIT 1
      FOR UPDATE
      """)
  RefreshSession selectByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
