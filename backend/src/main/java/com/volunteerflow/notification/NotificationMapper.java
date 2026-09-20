package com.volunteerflow.notification;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
  @Insert(
      """
      INSERT INTO notification (
        id, organization_id, user_id, notification_type, title, content,
        business_type, business_id, deduplication_key
      ) VALUES (
        #{id}, #{organizationId}, #{userId}, #{notificationType}, #{title}, #{content},
        #{businessType}, #{businessId}, #{deduplicationKey}
      )
      ON DUPLICATE KEY UPDATE id=id
      """)
  int insertIdempotent(Notification notification);

  @Insert(
      """
      <script>
      INSERT INTO notification (
        id, organization_id, user_id, notification_type, title, content,
        business_type, business_id, deduplication_key
      ) VALUES
      <foreach collection="notifications" item="notification" separator=",">
        (
          #{notification.id}, #{notification.organizationId}, #{notification.userId},
          #{notification.notificationType}, #{notification.title}, #{notification.content},
          #{notification.businessType}, #{notification.businessId},
          #{notification.deduplicationKey}
        )
      </foreach>
      ON DUPLICATE KEY UPDATE id=id
      </script>
      """)
  int insertManyIdempotent(@Param("notifications") List<Notification> notifications);

  @Select(
      """
      <script>
      SELECT * FROM notification
      WHERE user_id=#{userId}
      <if test="cursorTime != null and cursorId != null">
        AND (
          create_time &lt; #{cursorTime}
          OR (create_time = #{cursorTime} AND id &lt; #{cursorId})
        )
      </if>
      ORDER BY create_time DESC, id DESC
      LIMIT #{limit}
      </script>
      """)
  List<Notification> selectPage(
      @Param("userId") Long userId,
      @Param("cursorTime") LocalDateTime cursorTime,
      @Param("cursorId") Long cursorId,
      @Param("limit") int limit);

  @Select(
      """
      SELECT COUNT(*) FROM notification
      WHERE user_id=#{userId} AND read_at IS NULL
      """)
  long countUnread(@Param("userId") Long userId);

  @Update(
      """
      UPDATE notification
      SET read_at=CURRENT_TIMESTAMP(6), update_time=CURRENT_TIMESTAMP(6)
      WHERE id=#{notificationId} AND user_id=#{userId} AND read_at IS NULL
      """)
  int markRead(
      @Param("userId") Long userId, @Param("notificationId") Long notificationId);

  @Select(
      """
      SELECT COUNT(*) > 0 FROM notification
      WHERE id=#{notificationId} AND user_id=#{userId}
      """)
  boolean existsForUser(
      @Param("userId") Long userId, @Param("notificationId") Long notificationId);

  @Update(
      """
      UPDATE notification
      SET read_at=CURRENT_TIMESTAMP(6), update_time=CURRENT_TIMESTAMP(6)
      WHERE user_id=#{userId} AND read_at IS NULL
      """)
  int markAllRead(@Param("userId") Long userId);
}
