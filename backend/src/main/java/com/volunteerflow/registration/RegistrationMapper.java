package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistrationMapper extends BaseMapper<Registration> {
  @Select(
      "SELECT * FROM registration WHERE activity_id=#{activityId} AND user_id=#{userId} LIMIT 1")
  Registration selectByActivityAndUser(
      @Param("activityId") Long activityId, @Param("userId") Long userId);
}
