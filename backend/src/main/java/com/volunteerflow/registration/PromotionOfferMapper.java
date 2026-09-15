package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PromotionOfferMapper extends BaseMapper<PromotionOffer> {
  @Select(
      """
      SELECT COUNT(*) FROM promotion_offer
      WHERE position_id=#{positionId} AND status='PENDING' AND expires_at > CURRENT_TIMESTAMP(6)
      """)
  long countActiveReservations(@Param("positionId") Long positionId);
}
