export type EntityId = string
export interface CurrentUser { id:EntityId; username:string; realName:string; studentNumber:string; contact:string; platformRole:'PLATFORM_ADMIN'|'USER' }
export interface Organization { id:EntityId; name:string; description?:string; status:string }
export interface Activity { id:EntityId; organizationId:EntityId; title:string; description:string; location:string; registrationStartAt:string; registrationEndAt:string; freeCancelDeadlineAt?:string; activityStartAt:string; activityEndAt:string; status:string }
export interface Position { id:EntityId; name:string; description:string; capacity:number; registrationMode:'FIRST_COME'|'REVIEW'; promotionTimeoutMinutes:number; serviceStartAt?:string; serviceEndAt?:string; meetingLocation?:string }
export interface ActivitySummary { activity:Activity; positions:Position[] }
export interface ActivityDetail { activity:Activity; positions:Position[] }
