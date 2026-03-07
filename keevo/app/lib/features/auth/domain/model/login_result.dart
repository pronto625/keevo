import 'auth_tokens.dart';
import 'membership_dto.dart';

/// LoginResult — sealed result type for the two-step login flow (Story 1.7).
///
/// [AuthenticatedResult]: single membership auto-selected → tokens stored,
/// navigation can proceed directly to /pos.
///
/// [NeedsTenantSelectionResult]: user has ≥2 memberships → router navigates
/// to [TenantPickerPage] where the user picks a tenant to complete login.
sealed class LoginResult {}

/// User is fully authenticated — tokens have been stored in secure storage.
final class AuthenticatedResult extends LoginResult {
  final AuthTokens tokens;

  AuthenticatedResult(this.tokens);
}

/// User must choose a tenant before tokens can be issued.
///
/// [loginToken] is ephemeral (5 min TTL) — stored in memory only, never
/// written to flutter_secure_storage.
/// [memberships] is the list displayed in [TenantPickerPage].
final class NeedsTenantSelectionResult extends LoginResult {
  final String loginToken;
  final List<MembershipDto> memberships;

  NeedsTenantSelectionResult(this.loginToken, this.memberships);
}
