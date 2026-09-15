package com.volunteerflow.organization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrganizationInviteMapper extends BaseMapper<OrganizationInvite> {
  /** Locks one invitation while its remaining uses are checked and consumed. */
  @Select("SELECT * FROM organization_invite WHERE code_hash=#{hash} LIMIT 1 FOR UPDATE")
  OrganizationInvite selectByCodeHashForUpdate(@Param("hash") String hash);
}
