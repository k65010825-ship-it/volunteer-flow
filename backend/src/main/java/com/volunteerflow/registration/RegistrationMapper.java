package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistrationMapper extends BaseMapper<Registration> {
  @Select(
      "SELECT * FROM registration WHERE activity_id=#{activityId} AND user_id=#{userId} LIMIT 1")
  Registration selectByActivityAndUser(
      @Param("activityId") Long activityId, @Param("userId") Long userId);

  @Select("SELECT * FROM registration WHERE id=#{registrationId} AND user_id=#{userId} LIMIT 1")
  Registration selectOwnedById(
      @Param("registrationId") Long registrationId, @Param("userId") Long userId);

  @Select(
      "SELECT * FROM registration WHERE user_id=#{userId} ORDER BY update_time DESC, id DESC")
  List<Registration> selectOwnedByUser(@Param("userId") Long userId);
}
