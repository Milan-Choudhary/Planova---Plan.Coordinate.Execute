// Mirrors the backend contract in API_CONTRACTS.md. If the backend DTOs change, change this file
// AND the contract document in the same commit.

export type Role = 'ADMIN' | 'ORGANIZER' | 'TEAM_MEMBER' | 'VIEWER';
export type UserStatus = 'ACTIVE' | 'DISABLED';

export const ROLES: readonly Role[] = ['ADMIN', 'ORGANIZER', 'TEAM_MEMBER', 'VIEWER'];

export interface User {
  id: string;
  email: string;
  name: string;
  role: Role;
  status: UserStatus;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresInSeconds: number;
  user: User;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface CreateUserRequest {
  email: string;
  name: string;
  role: Role;
  password: string;
}

export interface UpdateUserRequest {
  role?: Role;
  status?: UserStatus;
}

// ---------------------------------------------------------------- planning / incidents (Phases 3-5)
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type IncidentStatus = 'OPEN' | 'ASSIGNED' | 'ACKNOWLEDGED' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
export type IncidentAction = 'ACKNOWLEDGE' | 'REJECT' | 'START' | 'RESOLVE' | 'ASSIGN' | 'CLOSE' | 'REOPEN';
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';
export type PlanStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

export interface Team {
  id: string;
  name: string;
  description?: string;
  memberCount: number;
}

export interface TeamMember {
  userId: string;
  name: string;
  email: string;
  userRole: Role;
  teamRole: 'LEAD' | 'RESPONDER' | 'MEMBER';
  available: boolean;
  escalationOrder?: number;
}

export interface TeamDetail {
  id: string;
  name: string;
  description?: string;
  members: TeamMember[];
}

export interface Plan {
  id: string;
  title: string;
  description?: string;
  ownerId: string;
  ownerName: string;
  teamId?: string;
  teamName?: string;
  startDate?: string;
  endDate?: string;
  status: PlanStatus;
  visibility: 'PRIVATE' | 'TEAM';
  version: number;
  myPlanRole?: 'OWNER' | 'EDITOR' | 'VIEWER';
  canEdit: boolean;
  canManage: boolean;
  eventCount: number;
  taskCount: number;
  openTaskCount: number;
  openIncidentCount: number;
}

export interface PlanMember {
  userId: string;
  name: string;
  email: string;
  userRole: Role;
  planRole: 'OWNER' | 'EDITOR' | 'VIEWER';
}

export interface PlanEvent {
  id: string;
  planId: string;
  title: string;
  description?: string;
  startAt: string;
  endAt: string;
  location?: string;
  category?: string;
}

export interface Task {
  id: string;
  planId: string;
  planTitle: string;
  eventId?: string;
  title: string;
  description?: string;
  assigneeId?: string;
  assigneeName?: string;
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  status: TaskStatus;
  deadline?: string;
  overdue: boolean;
  version: number;
}

export interface Incident {
  id: string;
  planId: string;
  planTitle: string;
  eventId?: string;
  title: string;
  description: string;
  severity: Severity;
  status: IncidentStatus;
  reporterId: string;
  reporterName: string;
  assigneeId?: string;
  assigneeName?: string;
  ackDeadline?: string;
  ackOverdue: boolean;
  escalationLevel: number;
  createdAt: string;
  updatedAt: string;
  resolutionNote?: string;
  version: number;
  allowedActions: IncidentAction[];
}

export interface AssignmentEntry {
  sequence: number;
  assigneeId: string;
  assigneeName: string;
  reason: 'AUTO' | 'ESCALATION' | 'MANUAL';
  status: string;
  assignedByName?: string;
  assignedAt: string;
  ackDeadline?: string;
  acknowledgedAt?: string;
  endedAt?: string;
}

export interface AuditEntry {
  id: number;
  actorId?: string;
  actorName?: string;
  action: string;
  entityType: string;
  entityId?: string;
  planId?: string;
  createdAt: string;
}

export interface IncidentHistory {
  assignments: AssignmentEntry[];
  audit: AuditEntry[];
}

export interface AppNotification {
  id: string;
  incidentId?: string;
  type: string;
  title: string;
  body?: string;
  status: string;
  createdAt: string;
  readAt?: string;
}

export interface DashboardSummary {
  plans: number;
  openIncidents: number;
  myAssignedIncidents: number;
  overdueAcknowledgements: number;
  myOpenTasks: number;
  myOverdueTasks: number;
  upcomingEvents: number;
  unreadNotifications: number;
}
