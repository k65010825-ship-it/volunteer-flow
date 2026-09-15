package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityPositionMapper;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.RegistrationViews.AnswerView;
import com.volunteerflow.registration.RegistrationViews.ManagedOfferView;
import com.volunteerflow.registration.RegistrationViews.ManagedRegistrationPage;
import com.volunteerflow.registration.RegistrationViews.ManagedRegistrationView;
import com.volunteerflow.registration.RegistrationViews.OwnOfferView;
import com.volunteerflow.registration.RegistrationViews.OwnRegistrationView;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds privacy-safe registration views for members and authorized organization managers. */
@Service
@Profile("!test")
public class RegistrationQueryService {
  private final RegistrationMapper registrations;
  private final RegistrationCycleMapper cycles;
  private final RegistrationAnswerMapper answers;
  private final PromotionOfferMapper offers;
  private final ActivityPositionMapper positions;
  private final AppUserMapper users;
  private final OrganizationAuthorizationService authorization;
  private final ObjectMapper objectMapper;

  public RegistrationQueryService(
      RegistrationMapper registrations,
      RegistrationCycleMapper cycles,
      RegistrationAnswerMapper answers,
      PromotionOfferMapper offers,
      ActivityPositionMapper positions,
      AppUserMapper users,
      OrganizationAuthorizationService authorization,
      ObjectMapper objectMapper) {
    this.registrations = registrations;
    this.cycles = cycles;
    this.answers = answers;
    this.offers = offers;
    this.positions = positions;
    this.users = users;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public OwnRegistrationView getOwn(Long userId, Long registrationId) {
    Registration registration = registrations.selectOwnedById(registrationId, userId);
    if (registration == null) {
      throw registrationNotFound();
    }
    return ownView(registration);
  }

  @Transactional(readOnly = true)
  public List<OwnRegistrationView> listOwn(Long userId) {
    return registrations.selectOwnedByUser(userId).stream().map(this::ownView).toList();
  }

  @Transactional(readOnly = true)
  public ManagedRegistrationPage listForPosition(
      Long actorId, Long positionId, String status, int page, int size) {
    ActivityPosition position = positions.selectById(positionId);
    if (position == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Activity position was not found");
    }
    authorization.requirePermission(
        actorId, position.getOrganizationId(), "registration:review");
    long safePage = Math.max(1, page);
    long safeSize = Math.min(100, Math.max(1, size));
    String normalizedStatus = normalizeStatus(status);
    IPage<RegistrationCycle> cyclePage =
        cycles.selectForPosition(
            new Page<>(safePage, safeSize),
            position.getOrganizationId(),
            position.getId(),
            normalizedStatus);
    List<ManagedRegistrationView> items =
        cyclePage.getRecords().stream().map(this::managedView).toList();
    return new ManagedRegistrationPage(items, cyclePage.getTotal(), safePage, safeSize);
  }

  private OwnRegistrationView ownView(Registration registration) {
    RegistrationCycle cycle = cycles.selectActiveOrLatest(registration.getId());
    if (cycle == null || !registration.getUserId().equals(cycle.getUserId())) {
      throw registrationNotFound();
    }
    List<AnswerView> answerViews = answerViews(cycle.getId());
    PromotionOffer offer = offers.selectPendingByCycle(cycle.getId());
    Long waitlistCount =
        "WAITLISTED".equals(cycle.getStatus())
            ? cycles.countActiveWaitlisted(cycle.getPositionId())
            : 0L;
    Integer currentPosition = null;
    if ("WAITLISTED".equals(cycle.getStatus()) && cycle.getWaitlistSequence() != null) {
      currentPosition =
          Math.toIntExact(
              cycles.countActiveWaitlistBefore(
                      cycle.getPositionId(), cycle.getWaitlistSequence())
                  + 1);
    }
    return new OwnRegistrationView(
        registration.getId(),
        registration.getOrganizationId(),
        registration.getActivityId(),
        cycle.getId(),
        cycle.getPositionId(),
        cycle.getCycleNumber(),
        cycle.getStatus(),
        cycle.getSubmittedAt(),
        cycle.getWaitlistSequence(),
        currentPosition,
        waitlistCount,
        answerViews,
        ownOfferView(offer));
  }

  private ManagedRegistrationView managedView(RegistrationCycle cycle) {
    AppUser user = users.selectById(cycle.getUserId());
    if (user == null) {
      throw new IllegalStateException("Registration user was not found");
    }
    return new ManagedRegistrationView(
        cycle.getRegistrationId(),
        cycle.getOrganizationId(),
        cycle.getActivityId(),
        cycle.getId(),
        cycle.getPositionId(),
        cycle.getCycleNumber(),
        cycle.getUserId(),
        user.getRealName(),
        user.getStudentNumber(),
        user.getContact(),
        cycle.getStatus(),
        cycle.getWaitlistSequence(),
        cycle.getSubmittedAt(),
        cycle.getReviewedBy(),
        cycle.getReviewedAt(),
        cycle.getReviewReason(),
        answerViews(cycle.getId()),
        managedOfferView(offers.selectByCycle(cycle.getId())));
  }

  private List<AnswerView> answerViews(Long cycleId) {
    return answers.selectByCycle(cycleId).stream()
        .map(
            answer ->
                new AnswerView(
                    answer.getQuestionScope(), answer.getQuestionId(), parseAnswer(answer)))
        .toList();
  }

  private JsonNode parseAnswer(RegistrationAnswer answer) {
    try {
      return objectMapper.readTree(answer.getAnswerJson());
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Stored registration answer is invalid: " + answer.getId(), exception);
    }
  }

  private OwnOfferView ownOfferView(PromotionOffer offer) {
    return offer == null
        ? null
        : new OwnOfferView(
            offer.getId(), offer.getStatus(), offer.getExpiresAt(), offer.getReason());
  }

  private ManagedOfferView managedOfferView(PromotionOffer offer) {
    return offer == null
        ? null
        : new ManagedOfferView(
            offer.getId(),
            offer.getStatus(),
            offer.getExpiresAt(),
            offer.getRespondedAt(),
            offer.getCreatedBy(),
            offer.getReason());
  }

  private String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    return status.trim().toUpperCase(Locale.ROOT);
  }

  private BusinessException registrationNotFound() {
    return new BusinessException(
        HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND", "Registration was not found");
  }
}
