package com.volunteerflow.infrastructure.event;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
  /** Select and claim must share a short transaction; send to Kafka only after committing. */
  default List<OutboxEvent> selectClaimableForUpdate(LocalDateTime now, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    List<OutboxEvent> result = new ArrayList<>(selectDueForUpdate(now, limit));
    if (result.size() < limit) {
      result.addAll(selectExpiredLeasesForUpdate(now, limit - result.size()));
    }
    return result;
  }

  /** Internal query path: leading status participates in ordering to match the due index. */
  @Select("""
      SELECT * FROM outbox_event FORCE INDEX (idx_outbox_event_status_retry_id)
      WHERE status IN ('PENDING', 'RETRY') AND (next_retry_at IS NULL OR next_retry_at<=#{now})
      ORDER BY status, next_retry_at, id LIMIT #{limit} FOR UPDATE SKIP LOCKED
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  List<OutboxEvent> selectDueForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** Internal query path: only expired leases are in the indexed time range. */
  @Select("""
      SELECT * FROM outbox_event FORCE INDEX (idx_outbox_event_status_lease_id)
      WHERE status='PROCESSING' AND locked_until<=#{now}
      ORDER BY locked_until, id LIMIT #{limit} FOR UPDATE SKIP LOCKED
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  List<OutboxEvent> selectExpiredLeasesForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

  @Update("""
      <script>
      UPDATE outbox_event SET status='PROCESSING', locked_by=#{workerId}, locked_until=#{lockedUntil}
      WHERE
      <choose>
        <when test="ids != null and !ids.isEmpty()">
          id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </when>
        <otherwise>1=0</otherwise>
      </choose>
      AND ((status IN ('PENDING', 'RETRY') AND (next_retry_at IS NULL OR next_retry_at&lt;=#{now}))
        OR (status='PROCESSING' AND locked_until&lt;=#{now}))
      </script>
      """)
  int claim(@Param("ids") List<Long> ids, @Param("workerId") String workerId,
      @Param("lockedUntil") LocalDateTime lockedUntil, @Param("now") LocalDateTime now);

  @Update("""
      UPDATE outbox_event SET status='PUBLISHED', published_partition=#{partition},
        published_offset=#{offset}, published_at=#{publishedAt}, last_error=NULL,
        locked_by=NULL, locked_until=NULL
      WHERE id=#{id} AND status='PROCESSING' AND locked_by=#{workerId}
      """)
  int markPublished(@Param("id") Long id, @Param("workerId") String workerId,
      @Param("partition") int partition, @Param("offset") long offset,
      @Param("publishedAt") LocalDateTime publishedAt);

  @Update("""
      UPDATE outbox_event SET status='RETRY', retry_count=#{retryCount}, next_retry_at=#{nextRetryAt},
        last_error=#{lastError}, locked_by=NULL, locked_until=NULL
      WHERE id=#{id} AND status='PROCESSING' AND locked_by=#{workerId}
      """)
  int markRetry(@Param("id") Long id, @Param("workerId") String workerId,
      @Param("retryCount") int retryCount, @Param("nextRetryAt") LocalDateTime nextRetryAt,
      @Param("lastError") String lastError);

  @Update("""
      UPDATE outbox_event SET status='DEAD', last_error=#{lastError}, locked_by=NULL, locked_until=NULL
      WHERE id=#{id} AND status='PROCESSING' AND locked_by=#{workerId}
      """)
  int markDead(@Param("id") Long id, @Param("workerId") String workerId, @Param("lastError") String lastError);

  @Select("""
      <script>
      SELECT id FROM outbox_event
      WHERE status='PUBLISHED' AND published_at&lt;#{cutoff}
      <if test="cursorTime != null and cursorId != null">
        AND (published_at&gt;#{cursorTime} OR (published_at=#{cursorTime} AND id&gt;#{cursorId}))
      </if>
      ORDER BY published_at, id LIMIT #{limit}
      </script>
      """)
  List<Long> selectPublishedIdsBefore(@Param("cutoff") LocalDateTime cutoff,
      @Param("cursorTime") LocalDateTime cursorTime, @Param("cursorId") Long cursorId,
      @Param("limit") int limit);

  @Delete("""
      <script>
      DELETE FROM outbox_event WHERE status='PUBLISHED' AND
      <choose>
        <when test="ids != null and !ids.isEmpty()">
          id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </when>
        <otherwise>1=0</otherwise>
      </choose>
      </script>
      """)
  int deletePublishedByIds(@Param("ids") List<Long> ids);
}
