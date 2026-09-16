package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PromotionOfferMapper extends BaseMapper<PromotionOffer> {
  @Select("SELECT CURRENT_TIMESTAMP(6)")
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  LocalDateTime currentDatabaseTime();

  @Select("SELECT * FROM promotion_offer WHERE id=#{offerId} FOR UPDATE")
  PromotionOffer selectByIdForUpdate(@Param("offerId") Long offerId);

  @Select("SELECT * FROM promotion_offer WHERE registration_cycle_id=#{cycleId} FOR UPDATE")
  PromotionOffer selectByCycleForUpdate(@Param("cycleId") Long cycleId);

  @Select(
      """
      SELECT COUNT(*) FROM promotion_offer
      WHERE position_id=#{positionId} AND status='PENDING' AND expires_at > CURRENT_TIMESTAMP(6)
      """)
  long countActiveReservations(@Param("positionId") Long positionId);

  @Select(
      """
      SELECT * FROM promotion_offer
      WHERE registration_cycle_id=#{cycleId}
        AND status='PENDING' AND expires_at > CURRENT_TIMESTAMP(6)
      LIMIT 1
      """)
  PromotionOffer selectPendingByCycle(@Param("cycleId") Long cycleId);

  @Select(
      """
      SELECT * FROM promotion_offer
      WHERE registration_cycle_id=#{cycleId}
      LIMIT 1
      """)
  PromotionOffer selectByCycle(@Param("cycleId") Long cycleId);

  @Select(
      """
      <script>
      SELECT * FROM promotion_offer
      WHERE registration_cycle_id IN
      <foreach collection="cycleIds" item="cycleId"
               open="(" separator="," close=")">#{cycleId}</foreach>
        AND status='PENDING' AND expires_at > CURRENT_TIMESTAMP(6)
      ORDER BY registration_cycle_id
      </script>
      """)
  List<PromotionOffer> selectPendingByCycles(@Param("cycleIds") List<Long> cycleIds);

  @Select(
      """
      <script>
      SELECT * FROM promotion_offer
      WHERE registration_cycle_id IN
      <foreach collection="cycleIds" item="cycleId"
               open="(" separator="," close=")">#{cycleId}</foreach>
      ORDER BY registration_cycle_id
      </script>
      """)
  List<PromotionOffer> selectByCycles(@Param("cycleIds") List<Long> cycleIds);
}
