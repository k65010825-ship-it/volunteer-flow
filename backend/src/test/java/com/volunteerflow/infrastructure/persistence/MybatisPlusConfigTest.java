package com.volunteerflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

class MybatisPlusConfigTest {

  @Test
  void configuresMysqlPaginationInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

    assertThat(interceptor.getInterceptors())
        .hasSize(1)
        .first()
        .isInstanceOf(PaginationInnerInterceptor.class);
  }
}
