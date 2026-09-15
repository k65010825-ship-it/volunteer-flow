package com.volunteerflow.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefreshSessionMapper extends BaseMapper<RefreshSession> {
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
