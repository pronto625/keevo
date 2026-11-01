export interface LoginSessionResponse {
  loginToken: string;
  memberships: MembershipDto[];
}

export interface MembershipDto {
  tenantCode: string;
  tenantName: string;
  role: string;
  schemaName: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  userId: string;
  tenantId: string;
  role: string;
  expiresIn: number;
  storeId: string | null;
  passwordChangeRequired: boolean;
}

/** Story 16.11 — `GET /api/v1/auth/profile`: the session's identity (the token carries none). */
export interface UserProfile {
  userId: string;
  phoneNumber: string;
  role: string;
  firstName: string | null;
  lastName: string | null;
  storeId: string | null;
  storeName: string | null;
  passwordChangeRequired: boolean;
  tenantStatus: string | null;
  onboardingCompleted: boolean;
}
