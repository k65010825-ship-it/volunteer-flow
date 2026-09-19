export type EntityId = string;
export interface CurrentUser {
  id: EntityId;
  username: string;
  realName: string;
  studentNumber: string;
  contact: string;
  platformRole: "PLATFORM_ADMIN" | "USER";
}
export interface Organization {
  id: EntityId;
  name: string;
  description?: string;
  status: string;
}
export interface Activity {
  id: EntityId;
  organizationId: EntityId;
  title: string;
  description: string;
  location: string;
  registrationStartAt: string;
  registrationEndAt: string;
  freeCancelDeadlineAt?: string;
  activityStartAt: string;
  activityEndAt: string;
  status: string;
}
export interface Position {
  id: EntityId;
  name: string;
  description: string;
  capacity: number;
  registrationMode: "FIRST_COME" | "REVIEW";
  promotionTimeoutMinutes: number;
  serviceStartAt?: string;
  serviceEndAt?: string;
  meetingLocation?: string;
  status: string;
}
export interface ActivitySummary {
  activity: Activity;
  positions: Position[];
}
export interface ActivityDetail {
  activity: Activity;
  positions: Position[];
}

export type QuestionType =
  | "TEXT"
  | "SINGLE_CHOICE"
  | "MULTIPLE_CHOICE"
  | "BOOLEAN";
export type QuestionScope = "ACTIVITY" | "POSITION";
export interface RegistrationQuestion {
  scope: QuestionScope;
  id: EntityId;
  type: QuestionType;
  title: string;
  required: boolean;
  options: string[];
  sortOrder: number;
}
export interface RegistrationForm {
  activity: Activity;
  position: Position;
  questions: RegistrationQuestion[];
}
export interface RegistrationAnswer {
  questionScope: QuestionScope;
  questionId: EntityId;
  answer: unknown;
}
export type RegistrationStatus =
  | "PENDING_REVIEW"
  | "CONFIRMED"
  | "WAITLISTED"
  | "REJECTED"
  | "CANCELED"
  | "LATE_CANCELED"
  | "PROMOTION_DECLINED"
  | "PROMOTION_EXPIRED";
export interface OwnOffer {
  id: EntityId;
  status: "PENDING" | "ACCEPTED" | "DECLINED" | "EXPIRED" | "CANCELED";
  expiresAt: string;
  reason: string | null;
}
export interface OwnRegistration {
  registrationId: EntityId;
  organizationId: EntityId;
  activityId: EntityId;
  cycleId: EntityId;
  positionId: EntityId;
  cycleNumber: number;
  status: RegistrationStatus;
  submittedAt: string;
  waitlistSequence: string | null;
  currentWaitlistPosition: number | null;
  waitlistCount: string | null;
  answers: RegistrationAnswer[];
  pendingOffer: OwnOffer | null;
}
export interface RegistrationState {
  registrationId: EntityId;
  cycleId: EntityId;
  status: RegistrationStatus;
}
export interface RegistrationResult extends RegistrationState {
  waitlistSequence: string | null;
  currentPosition: number | null;
}
