package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RbacRoleMapper extends BaseMapper<RbacRole> {
  @Select(
      "SELECT * FROM rbac_role WHERE organization_id=#{orgId} AND built_in_type=#{type} AND"
          + " status='ACTIVE' LIMIT 1")
  RbacRole selectBuiltIn(@Param("orgId") Long orgId, @Param("type") String type);

  @Select(
      "SELECT * FROM rbac_role WHERE organization_id=#{orgId} AND status='ACTIVE' ORDER BY"
          + " protected_role DESC,name")
  List<RbacRole> selectActiveByOrganization(@Param("orgId") Long orgId);
}
