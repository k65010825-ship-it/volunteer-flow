package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistrationAnswerMapper extends BaseMapper<RegistrationAnswer> {
  @Select(
      """
      SELECT * FROM registration_answer
      WHERE registration_cycle_id=#{cycleId}
      ORDER BY question_scope, question_id, id
      """)
  List<RegistrationAnswer> selectByCycle(@Param("cycleId") Long cycleId);

  @Select(
      """
      <script>
      SELECT * FROM registration_answer
      WHERE registration_cycle_id IN
      <foreach collection="cycleIds" item="cycleId"
               open="(" separator="," close=")">#{cycleId}</foreach>
      ORDER BY registration_cycle_id, question_scope, question_id, id
      </script>
      """)
  List<RegistrationAnswer> selectByCycles(@Param("cycleIds") List<Long> cycleIds);
}
