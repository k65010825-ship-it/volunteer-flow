package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RbacPermissionMapper extends BaseMapper<RbacPermission> {
  @Select("SELECT * FROM rbac_permission WHERE status='ACTIVE' ORDER BY id")
  List<RbacPermission> selectAllActive();

  @Select(
      "<script>SELECT * FROM rbac_permission WHERE status='ACTIVE' AND code IN <foreach"
          + " collection='codes' item='c' open='(' separator=',' close=')'>#{c}</foreach></script>")
  List<RbacPermission> selectByCodes(@Param("codes") java.util.Collection<String> codes);
}
