package com.volunteerflow.infrastructure.event;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConsumedEventMapper extends BaseMapper<ConsumedEvent> {
  /** Returns 1 on insertion and 0 for an already consumed event. Supply a valid entity. */
  @Insert("""
      INSERT IGNORE INTO consumed_event (id, consumer_name, event_id, processed_at)
      VALUES (#{id}, #{consumerName}, #{eventId}, #{processedAt})
      """)
  int insertIfAbsent(ConsumedEvent event);
}
