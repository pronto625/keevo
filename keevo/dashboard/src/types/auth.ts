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
