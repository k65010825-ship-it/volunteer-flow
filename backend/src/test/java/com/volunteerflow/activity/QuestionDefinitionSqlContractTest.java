package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionDefinitionSqlContractTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void definitionReadsScopeOrganizationAndParentsAndPreserveDisplayOrder(boolean position) {
    Class<?> mapper = position ? ActivityPositionQuestionMapper.class : ActivityQuestionMapper.class;
    var config = new MybatisConfiguration();
    config.addMapper(mapper);
    var bound = config.getMappedStatement(mapper.getName()
        + (position ? ".selectByPosition" : ".selectByActivity")).getBoundSql(
            Map.of("organizationId", 100L, "activityId", 10L, "positionId", 20L));
    String sql = bound.getSql().replaceAll("\\s+", " ").trim();
    assertThat(sql).contains("organization_id=?", "activity_id=?")
        .endsWith("ORDER BY sort_order,id").doesNotContain("FOR UPDATE", "answer");
    if (position) {
      assertThat(sql).contains("position_id=?");
      assertThat(bound.getParameterMappings()).extracting(p -> p.getProperty())
          .containsExactly("organizationId", "activityId", "positionId");
    } else {
      assertThat(bound.getParameterMappings()).extracting(p -> p.getProperty())
          .containsExactly("organizationId", "activityId");
    }
  }
}
