package com.volunteerflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class JacksonLongSerializationTest {
  @Test
  void serializesLongIdentifiersAsStringsWithoutChangingIntegers() throws Exception {
    Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
    new JacksonConfig().longAsStringCustomizer().customize(builder);
    ObjectMapper mapper = builder.build();

    assertThat(mapper.writeValueAsString(new Example(2030987654321098765L, 20)))
        .isEqualTo("{\"id\":\"2030987654321098765\",\"capacity\":20}");
  }

  private record Example(Long id, Integer capacity) {}
}
