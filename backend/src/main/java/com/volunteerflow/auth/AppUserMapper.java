package com.volunteerflow.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
  @Select("SELECT * FROM app_user WHERE id=#{id} FOR UPDATE")
  AppUser selectByIdForUpdate(@Param("id") Long id);

  @Select("SELECT * FROM app_user WHERE username=#{username} LIMIT 1 FOR UPDATE")
  AppUser selectByUsernameForUpdate(@Param("username") String username);

  @Update("UPDATE app_user SET real_name=#{realName}, contact=#{contact} WHERE id=#{id}")
  int updateProfile(
      @Param("id") Long id, @Param("realName") String realName, @Param("contact") String contact);

  @Update(
      "UPDATE app_user SET password_hash=#{passwordHash}, version=version+1 WHERE id=#{id} AND"
          + " version=#{version}")
  int updatePassword(
      @Param("id") Long id,
      @Param("passwordHash") String passwordHash,
      @Param("version") int version);
}
