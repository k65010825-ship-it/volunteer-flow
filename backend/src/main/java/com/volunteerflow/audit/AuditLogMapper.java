package com.volunteerflow.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.volunteerflow.audit.AuditLogViews.Filter;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
  /** Every filter is bound, and the tenant predicate is mandatory even without filters. */
  @Select("""
      <script>
      SELECT id, organization_id, actor_user_id, action, resource_type, resource_id,
             before_json, after_json, create_time
      FROM audit_log
      WHERE organization_id=#{organizationId}
      <if test="filter.actorUserId != null">AND actor_user_id=#{filter.actorUserId}</if>
      <if test="filter.action != null">AND action=#{filter.action}</if>
      <if test="filter.resourceType != null">AND resource_type=#{filter.resourceType}</if>
      <if test="filter.resourceId != null">AND resource_id=#{filter.resourceId}</if>
      <if test="filter.from != null">AND create_time &gt;= #{filter.from}</if>
      <if test="filter.to != null">AND create_time &lt; #{filter.to}</if>
      ORDER BY create_time DESC, id DESC
      </script>
      """)
  IPage<AuditLog> selectForOrganization(
      IPage<AuditLog> page,
      @Param("organizationId") Long organizationId,
      @Param("filter") Filter filter);
}
