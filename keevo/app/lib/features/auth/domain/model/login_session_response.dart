import 'membership_dto.dart';

/// LoginSessionResponse — intermediate parse result from POST /auth/login.
///
/// Contains a short-lived loginToken (5 min) and the user's tenant memberships.
/// The datasource returns this; [LoginUseCase] decides whether to auto-select
/// (single membership) or surface the picker (multiple memberships).
class LoginSessionResponse {
  final String loginToken;
  final List<MembershipDto> memberships;

  const LoginSessionResponse({
    required this.loginToken,
    required this.memberships,
  });
}
