package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityPositionMapper;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.RegistrationViews.ManagedRegistrationPage;
import com.volunteerflow.registration.RegistrationViews.OwnRegistrationView;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class RegistrationQueryServiceTest {
  private final RegistrationMapper registrationMapper = mock(RegistrationMapper.class);
  private final RegistrationCycleMapper cycleMapper = mock(RegistrationCycleMapper.class);
  private final RegistrationAnswerMapper answerMapper = mock(RegistrationAnswerMapper.class);
  private final PromotionOfferMapper offerMapper = mock(PromotionOfferMapper.class);
  private final ActivityPositionMapper positionMapper = mock(ActivityPositionMapper.class);
  private final AppUserMapper userMapper = mock(AppUserMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);

  private RegistrationQueryService service;

  @BeforeEach
  void setUp() {
    service =
        new RegistrationQueryService(
            registrationMapper,
            cycleMapper,
            answerMapper,
            offerMapper,
            positionMapper,
            userMapper,
            authorization,
            new ObjectMapper());
  }

  @Test
  void memberViewCalculatesPositionWithoutReturningOtherCandidates() {
    Registration registration = registration(100L, 21L);
    RegistrationCycle cycle = cycle(101L, 100L, 21L, "WAITLISTED", 8L);
    when(registrationMapper.selectOwnedById(100L, 21L)).thenReturn(registration);
    when(cycleMapper.selectActiveOrLatest(100L)).thenReturn(cycle);
    when(answerMapper.selectByCycle(101L)).thenReturn(List.of(answer(501L, 101L)));
    when(offerMapper.selectPendingByCycle(101L)).thenReturn(null);
    when(cycleMapper.countActiveWaitlistBefore(20L, 8L)).thenReturn(2L);
    when(cycleMapper.countActiveWaitlisted(20L)).thenReturn(6L);

    OwnRegistrationView view = service.getOwn(21L, 100L);

    assertThat(view.currentWaitlistPosition()).isEqualTo(3);
    assertThat(view.waitlistCount()).isEqualTo(6);
    assertThat(view.answers()).hasSize(1);
    assertThat(view.answers().get(0).answer().asText()).isEqualTo("Weekends");
    assertThat(view).hasNoNullFieldsOrPropertiesExcept("pendingOffer");
  }

  @Test
  void anotherMemberCannotReadRegistrationDetail() {
    when(registrationMapper.selectOwnedById(100L, 99L)).thenReturn(null);

    assertThatThrownBy(() -> service.getOwn(99L, 100L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).status())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void memberListUsesOnlyRegistrationsOwnedByThatMember() {
    Registration registration = registration(100L, 21L);
    when(registrationMapper.selectOwnedByUser(21L)).thenReturn(List.of(registration));
    when(cycleMapper.selectActiveOrLatest(100L))
        .thenReturn(cycle(101L, 100L, 21L, "CONFIRMED", null));
    when(answerMapper.selectByCycle(101L)).thenReturn(List.of());
    when(offerMapper.selectPendingByCycle(101L)).thenReturn(null);

    List<OwnRegistrationView> views = service.listOwn(21L);

    assertThat(views).extracting(OwnRegistrationView::registrationId).containsExactly(100L);
    verify(registrationMapper).selectOwnedByUser(21L);
  }

  @Test
  void unorderedReviewCandidateDoesNotExposeAQueuePosition() {
    Registration registration = registration(100L, 21L);
    RegistrationCycle cycle = cycle(101L, 100L, 21L, "WAITLISTED", null);
    when(registrationMapper.selectOwnedById(100L, 21L)).thenReturn(registration);
    when(cycleMapper.selectActiveOrLatest(100L)).thenReturn(cycle);
    when(answerMapper.selectByCycle(101L)).thenReturn(List.of());
    when(offerMapper.selectPendingByCycle(101L)).thenReturn(null);
    when(cycleMapper.countActiveWaitlisted(20L)).thenReturn(4L);

    OwnRegistrationView view = service.getOwn(21L, 100L);

    assertThat(view.waitlistSequence()).isNull();
    assertThat(view.currentWaitlistPosition()).isNull();
    assertThat(view.waitlistCount()).isEqualTo(4L);
  }

  @Test
  void memberViewIncludesOnlyTheMembersOwnPendingOfferMetadata() {
    Registration registration = registration(100L, 21L);
    RegistrationCycle cycle = cycle(101L, 100L, 21L, "WAITLISTED", 8L);
    PromotionOffer offer = offer(601L, 101L);
    when(registrationMapper.selectOwnedById(100L, 21L)).thenReturn(registration);
    when(cycleMapper.selectActiveOrLatest(100L)).thenReturn(cycle);
    when(answerMapper.selectByCycle(101L)).thenReturn(List.of());
    when(offerMapper.selectPendingByCycle(101L)).thenReturn(offer);
    when(cycleMapper.countActiveWaitlistBefore(20L, 8L)).thenReturn(0L);
    when(cycleMapper.countActiveWaitlisted(20L)).thenReturn(1L);

    OwnRegistrationView view = service.getOwn(21L, 100L);

    assertThat(view.pendingOffer())
        .isNotNull()
        .satisfies(
            pending -> {
              assertThat(pending.id()).isEqualTo(601L);
              assertThat(pending.status()).isEqualTo("PENDING");
              assertThat(pending.expiresAt()).isEqualTo(LocalDateTime.of(2026, 9, 16, 9, 0));
              assertThat(pending.reason()).isEqualTo("Capacity became available");
            });
  }

  @Test
  void adminListIncludesReviewProfileAnswersAndOfferMetadata() {
    ActivityPosition position = position(20L, 7L);
    RegistrationCycle cycle = cycle(101L, 100L, 21L, "PENDING_REVIEW", null);
    Page<RegistrationCycle> cyclePage = new Page<>(2, 25, 1);
    cyclePage.setRecords(List.of(cycle));
    AppUser user = user(21L);
    PromotionOffer offer = offer(601L, 101L);
    when(positionMapper.selectById(20L)).thenReturn(position);
    when(cycleMapper.selectForPosition(
            any(), eq(7L), eq(20L), eq("PENDING_REVIEW")))
        .thenReturn(cyclePage);
    when(userMapper.selectById(21L)).thenReturn(user);
    when(answerMapper.selectByCycle(101L)).thenReturn(List.of(answer(501L, 101L)));
    when(offerMapper.selectByCycle(101L)).thenReturn(offer);

    ManagedRegistrationPage result =
        service.listForPosition(9L, 20L, "PENDING_REVIEW", 2, 25);

    assertThat(result.total()).isEqualTo(1L);
    assertThat(result.page()).isEqualTo(2L);
    assertThat(result.size()).isEqualTo(25L);
    assertThat(result.items()).singleElement()
        .satisfies(
            view -> {
              assertThat(view.userId()).isEqualTo(21L);
              assertThat(view.realName()).isEqualTo("Lin Yue");
              assertThat(view.studentNumber()).isEqualTo("20260021");
              assertThat(view.contact()).isEqualTo("13800000021");
              assertThat(view.answers()).hasSize(1);
              assertThat(view.offer().id()).isEqualTo(601L);
              assertThat(view.status()).isEqualTo("PENDING_REVIEW");
              assertThat(view.reviewedBy()).isEqualTo(31L);
              assertThat(view.reviewReason()).isEqualTo("Good fit");
            });
    verify(authorization).requirePermission(9L, 7L, "registration:review");
  }

  @Test
  void adminPageClampsPageAndSizeToSupportedRange() {
    when(positionMapper.selectById(20L)).thenReturn(position(20L, 7L));
    Page<RegistrationCycle> emptyPage = new Page<>(1, 100, 0);
    when(cycleMapper.selectForPosition(any(), eq(7L), eq(20L), isNull()))
        .thenReturn(emptyPage);

    ManagedRegistrationPage result = service.listForPosition(9L, 20L, null, 0, 101);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<IPage<RegistrationCycle>> pageCaptor = ArgumentCaptor.forClass(IPage.class);
    verify(cycleMapper)
        .selectForPosition(
            pageCaptor.capture(), eq(7L), eq(20L), isNull());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(100L);
    assertThat(result.page()).isEqualTo(1L);
    assertThat(result.size()).isEqualTo(100L);
  }

  @Test
  void adminPageClampsZeroSizeToOne() {
    when(positionMapper.selectById(20L)).thenReturn(position(20L, 7L));
    Page<RegistrationCycle> emptyPage = new Page<>(1, 1, 0);
    when(cycleMapper.selectForPosition(any(), eq(7L), eq(20L), isNull()))
        .thenReturn(emptyPage);

    service.listForPosition(9L, 20L, null, 1, 0);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<IPage<RegistrationCycle>> pageCaptor = ArgumentCaptor.forClass(IPage.class);
    verify(cycleMapper)
        .selectForPosition(
            pageCaptor.capture(), eq(7L), eq(20L), isNull());
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(1L);
  }

  @Test
  void adminCannotListPositionFromAnOrganizationTheyCannotSee() {
    when(positionMapper.selectById(20L)).thenReturn(position(20L, 7L));
    BusinessException hidden =
        new BusinessException(
            HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization was not found");
    doThrow(hidden)
        .when(authorization)
        .requirePermission(9L, 7L, "registration:review");

    assertThatThrownBy(() -> service.listForPosition(9L, 20L, null, 1, 20))
        .isSameAs(hidden);
  }

  @Test
  void missingPositionIsHiddenAsNotFoundBeforeAuthorization() {
    when(positionMapper.selectById(404L)).thenReturn(null);

    assertThatThrownBy(() -> service.listForPosition(9L, 404L, null, 1, 20))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).status())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private Registration registration(Long id, Long userId) {
    Registration registration = new Registration();
    registration.setId(id);
    registration.setOrganizationId(7L);
    registration.setActivityId(10L);
    registration.setUserId(userId);
    registration.setLastCycleNumber(1);
    return registration;
  }

  private RegistrationCycle cycle(
      Long id, Long registrationId, Long userId, String status, Long waitlistSequence) {
    RegistrationCycle cycle = new RegistrationCycle();
    cycle.setId(id);
    cycle.setOrganizationId(7L);
    cycle.setRegistrationId(registrationId);
    cycle.setActivityId(10L);
    cycle.setPositionId(20L);
    cycle.setUserId(userId);
    cycle.setCycleNumber(1);
    cycle.setStatus(status);
    cycle.setWaitlistSequence(waitlistSequence);
    cycle.setSubmittedAt(LocalDateTime.of(2026, 9, 15, 9, 0));
    cycle.setReviewedBy(31L);
    cycle.setReviewedAt(LocalDateTime.of(2026, 9, 15, 10, 0));
    cycle.setReviewReason("Good fit");
    return cycle;
  }

  private RegistrationAnswer answer(Long id, Long cycleId) {
    RegistrationAnswer answer = new RegistrationAnswer();
    answer.setId(id);
    answer.setOrganizationId(7L);
    answer.setRegistrationCycleId(cycleId);
    answer.setQuestionScope("ACTIVITY");
    answer.setQuestionId(301L);
    answer.setAnswerJson("\"Weekends\"");
    return answer;
  }

  private ActivityPosition position(Long id, Long organizationId) {
    ActivityPosition position = new ActivityPosition();
    position.setId(id);
    position.setOrganizationId(organizationId);
    position.setActivityId(10L);
    position.setName("Guide");
    position.setRegistrationMode("REVIEW");
    position.setStatus("ACTIVE");
    return position;
  }

  private AppUser user(Long id) {
    AppUser user = new AppUser();
    user.setId(id);
    user.setRealName("Lin Yue");
    user.setStudentNumber("20260021");
    user.setContact("13800000021");
    return user;
  }

  private PromotionOffer offer(Long id, Long cycleId) {
    PromotionOffer offer = new PromotionOffer();
    offer.setId(id);
    offer.setRegistrationCycleId(cycleId);
    offer.setStatus("PENDING");
    offer.setExpiresAt(LocalDateTime.of(2026, 9, 16, 9, 0));
    offer.setCreatedBy(9L);
    offer.setReason("Capacity became available");
    return offer;
  }
}
