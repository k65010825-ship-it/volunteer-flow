import type {
  ActivityDetail,
  OwnRegistration,
  QuestionType,
  RegistrationQuestion,
} from "../api/types";

export const activityDetail: ActivityDetail = {
  activity: {
    id: "10",
    organizationId: "1",
    title: "开学典礼",
    description: "服务新同学",
    location: "礼堂",
    registrationStartAt: "2026-09-01T08:00:00",
    registrationEndAt: "2026-09-30T08:00:00",
    activityStartAt: "2026-10-01T08:00:00",
    activityEndAt: "2026-10-01T12:00:00",
    status: "PUBLISHED",
  },
  positions: [
    {
      id: "20",
      name: "礼仪",
      description: "迎接来宾",
      capacity: 20,
      registrationMode: "FIRST_COME",
      promotionTimeoutMinutes: 30,
      status: "ACTIVE",
    },
    {
      id: "21",
      name: "讲解",
      description: "介绍校园",
      capacity: 5,
      registrationMode: "REVIEW",
      promotionTimeoutMinutes: 30,
      status: "ACTIVE",
    },
  ],
};

export function question(
  type: QuestionType = "SINGLE_CHOICE",
  options = ["可以", "不可以"],
): RegistrationQuestion {
  return {
    scope: "ACTIVITY",
    id: "1",
    type,
    title: "可参加培训吗",
    required: true,
    options,
    sortOrder: 1,
  };
}

export function registration(
  overrides: Partial<OwnRegistration> = {},
): OwnRegistration {
  return {
    registrationId: "30",
    organizationId: "1",
    activityId: "10",
    cycleId: "40",
    positionId: "20",
    cycleNumber: 1,
    status: "WAITLISTED",
    submittedAt: "2026-09-18T08:00:00",
    waitlistSequence: "5",
    currentWaitlistPosition: 2,
    waitlistCount: "8",
    answers: [],
    pendingOffer: null,
    ...overrides,
  };
}
