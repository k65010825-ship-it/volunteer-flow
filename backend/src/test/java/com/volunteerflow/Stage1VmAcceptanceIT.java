package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.activity.ActivityService;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.organization.CreateInviteRequest;
import com.volunteerflow.organization.InviteCode;
import com.volunteerflow.organization.Organization;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class Stage1VmAcceptanceIT {
  @Autowired private AppUserMapper users;
  @Autowired private OrganizationService organizations;
  @Autowired private ActivityService activities;

  @Test
  void completesStageOneBusinessLoopAgainstConfiguredMysql() {
    long suffix = System.nanoTime();
    AppUser platformAdmin = user("accept-admin-" + suffix, "A-" + suffix, "PLATFORM_ADMIN");
    AppUser owner = user("accept-owner-" + suffix, "O-" + suffix, "USER");
    AppUser member = user("accept-member-" + suffix, "M-" + suffix, "USER");

    Organization organization =
        organizations.create(
            platformAdmin.getId(),
            platformAdmin.getPlatformRole(),
            new OrganizationService.CreateOrganizationRequest("阶段一事务验收", "自动回滚", owner.getId()));
    InviteCode invite =
        organizations.createInvite(
            owner.getId(),
            organization.getId(),
            new CreateInviteRequest(2, Instant.now().plus(1, ChronoUnit.DAYS)));
    OrganizationMember membership = organizations.joinByInvite(member.getId(), invite.code());

    LocalDateTime base = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
    var activity =
        activities.create(
            owner.getId(),
            organization.getId(),
            new ActivityService.ActivityRequest(
                "新生开学典礼",
                "阶段一多岗位活动",
                "图书馆前广场",
                base,
                base.plusHours(2),
                base.plusDays(1),
                base.plusDays(2),
                base.plusDays(2).plusHours(4)));
    activities.addPosition(
        owner.getId(),
        activity.getId(),
        new ActivityService.PositionRequest(
            "礼仪岗", "负责接待引导", 20, "FIRST_COME", 120, null, null, null));
    activities.addPosition(
        owner.getId(),
        activity.getId(),
        new ActivityService.PositionRequest("讲解岗", "负责校园讲解", 15, "REVIEW", 120, null, null, null));
    activities.publish(owner.getId(), activity.getId());

    var visible = activities.list(member.getId(), organization.getId());
    var detail = activities.detail(member.getId(), activity.getId());
    assertThat(membership.getRoleId()).isNotNull();
    assertThat(visible).hasSize(1);
    assertThat(detail.positions()).extracting("name").containsExactly("礼仪岗", "讲解岗");
  }

  private AppUser user(String username, String studentNumber, String platformRole) {
    AppUser user = new AppUser();
    user.setId(IdWorker.getId());
    user.setUsername(username);
    user.setPasswordHash("acceptance-test-not-used");
    user.setRealName(username);
    user.setStudentNumber(studentNumber);
    user.setContact("acceptance-local");
    user.setStatus("ACTIVE");
    user.setPlatformRole(platformRole);
    user.setVersion(0);
    users.insert(user);
    return user;
  }
}
