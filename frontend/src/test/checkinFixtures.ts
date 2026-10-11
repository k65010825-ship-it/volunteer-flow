export const session = {
  sessionId: "70", activityId: "10", sessionType: "STATIC_CODE", status: "OPEN",
  opensAt: "2026-10-11T02:00:00", closesAt: "2026-10-11T02:01:00",
};
export const attendance = {
  registrationId: "30", registrationCycleId: "31", positionId: "20", positionName: "迎新",
  attendanceStatus: "PENDING", recordId: null, checkedInAt: null,
};
export const summary = {
  activityId: "10", activityStatus: "PUBLISHED", finalStatistics: false,
  confirmedCount: "3", checkedInCount: "1", noShowCount: "1", pendingCount: "1", checkinRate: 0.3333,
  positions: [{ positionId: "20", positionName: "迎新", confirmedCount: "3", checkedInCount: "1",
    noShowCount: "1", pendingCount: "1", checkinRate: 0.3333 }], correctionSession: session,
};
export const member = {
  registrationId: "30", organizationId: "1", activityId: "10", cycleId: "31", positionId: "20",
  cycleNumber: 1, userId: "40", realName: "小林", studentNumber: "2026001", contact: "123",
  status: "CONFIRMED", attendanceStatus: "NO_SHOW", waitlistSequence: null,
  submittedAt: "2026-10-01T02:00:00", reviewedBy: null, reviewedAt: null, reviewReason: null,
  answers: [], offer: null,
};
