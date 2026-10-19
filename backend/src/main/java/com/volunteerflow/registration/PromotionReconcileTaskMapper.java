package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PromotionReconcileTaskMapper extends BaseMapper<PromotionReconcileTask> {
  @Select("SELECT CURRENT_TIMESTAMP(6)")
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  LocalDateTime currentDatabaseTime();

  @Select("SELECT * FROM promotion_reconcile_task WHERE position_id=#{positionId} FOR UPDATE")
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  PromotionReconcileTask selectByPositionForUpdate(@Param("positionId") Long positionId);

  /** A request made during processing must not steal the worker's lease or lose its newer version. */
  @Insert("""
      INSERT INTO promotion_reconcile_task
        (position_id, organization_id, status, request_version, processed_version, next_run_at, attempt_count)
      VALUES (#{positionId}, #{organizationId}, 'PENDING', 1, 0, #{nextRunAt}, 0)
      ON DUPLICATE KEY UPDATE request_version=request_version+1,
        next_run_at=LEAST(next_run_at, VALUES(next_run_at)),
        status=CASE WHEN status='PROCESSING' THEN 'PROCESSING' ELSE 'PENDING' END
      """)
  int upsertRequest(@Param("organizationId") Long organizationId, @Param("positionId") Long positionId,
      @Param("nextRunAt") LocalDateTime nextRunAt);

  /** Select and claim execute together in a short transaction. */
  default List<PromotionReconcileTask> selectClaimableForUpdate(LocalDateTime now, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    List<PromotionReconcileTask> result = new ArrayList<>(selectDueForUpdate(now, limit));
    if (result.size() < limit) {
      result.addAll(selectExpiredLeasesForUpdate(now, limit - result.size()));
    }
    return result;
  }

  /** Internal query path: leading status participates in ordering to match the due index. */
  @Select("""
      SELECT * FROM promotion_reconcile_task FORCE INDEX (idx_promotion_reconcile_status_run_position)
      WHERE status IN ('PENDING', 'RETRY') AND next_run_at<=#{now}
      ORDER BY status, next_run_at, position_id LIMIT #{limit} FOR UPDATE SKIP LOCKED
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  List<PromotionReconcileTask> selectDueForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** Internal query path: only expired leases are in the indexed time range. */
  @Select("""
      SELECT * FROM promotion_reconcile_task FORCE INDEX (idx_promotion_reconcile_status_lease_position)
      WHERE status='PROCESSING' AND locked_until<=#{now}
      ORDER BY locked_until, position_id LIMIT #{limit} FOR UPDATE SKIP LOCKED
      """)
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  List<PromotionReconcileTask> selectExpiredLeasesForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

  @Update("""
      UPDATE promotion_reconcile_task
      SET status='PROCESSING', locked_by=#{workerId}, locked_until=#{lockedUntil}
      WHERE position_id=#{positionId} AND request_version=#{expectedRequestVersion}
        AND ((status IN ('PENDING', 'RETRY') AND next_run_at<=#{now})
          OR (status='PROCESSING' AND locked_until<=#{now}))
      """)
  int claim(@Param("positionId") Long positionId, @Param("expectedRequestVersion") long expectedRequestVersion,
      @Param("workerId") String workerId, @Param("lockedUntil") LocalDateTime lockedUntil,
      @Param("now") LocalDateTime now);

  @Update("""
      UPDATE promotion_reconcile_task
      SET processed_version=#{claimedVersion},
        status=CASE WHEN request_version=#{claimedVersion} THEN 'DONE' ELSE 'PENDING' END,
        next_run_at=#{now}, attempt_count=0, last_error=NULL, locked_by=NULL, locked_until=NULL
      WHERE position_id=#{positionId} AND status='PROCESSING' AND locked_by=#{workerId}
        AND processed_version<#{claimedVersion} AND request_version>=#{claimedVersion}
        AND locked_until>#{now}
      """)
  int markProcessed(@Param("positionId") Long positionId, @Param("claimedVersion") long claimedVersion,
      @Param("workerId") String workerId, @Param("now") LocalDateTime now);

  @Update("""
      UPDATE promotion_reconcile_task
      SET status='RETRY', attempt_count=#{attemptCount}, next_run_at=#{nextRunAt}, last_error=#{lastError},
        locked_by=NULL, locked_until=NULL
      WHERE position_id=#{positionId} AND status='PROCESSING' AND locked_by=#{workerId}
        AND processed_version<#{claimedVersion} AND request_version>=#{claimedVersion}
        AND locked_until>#{now}
      """)
  int markRetry(@Param("positionId") Long positionId, @Param("claimedVersion") long claimedVersion,
      @Param("workerId") String workerId, @Param("attemptCount") int attemptCount,
      @Param("nextRunAt") LocalDateTime nextRunAt, @Param("lastError") String lastError,
      @Param("now") LocalDateTime now);
}
