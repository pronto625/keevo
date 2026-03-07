import '../model/login_result.dart';
import '../repository/auth_repository.dart';
import '../repository/token_storage.dart';
import 'select_tenant_usecase.dart';

/// LoginUseCase — Application use case for user authentication (two-step flow, Story 1.7).
///
/// Step 1: POST /auth/login → gets loginToken + memberships.
/// Step 2a (single membership, AC8): auto-calls SelectTenantUseCase → returns [AuthenticatedResult].
/// Step 2b (multiple memberships, AC9): returns [NeedsTenantSelectionResult] → router shows picker.
///
/// AC4: account lockout → surfaces AuthException(ACCOUNT_LOCKED).
class LoginUseCase {
  final AuthRepository _repository;
  final TokenStorage _tokenStorage;

  const LoginUseCase(this._repository, this._tokenStorage);

  /// Execute login with [phoneNumber] and [password].
  ///
  /// Returns [LoginResult]:
  /// - [AuthenticatedResult]: single membership, tokens stored, ready to navigate.
  /// - [NeedsTenantSelectionResult]: multiple memberships, loginToken held in memory.
  ///
  /// Propagates [AuthException] from repository unchanged:
  /// - INVALID_CREDENTIALS: wrong phone or password
  /// - ACCOUNT_LOCKED: too many failed attempts
  Future<LoginResult> execute({
    required String phoneNumber,
    required String password,
  }) async {
    final session = await _repository.login(
      phoneNumber: phoneNumber.trim(),
      password: password,
    );

    if (session.memberships.length == 1) {
      // AC8: single membership — auto-select without showing picker
      final selectUseCase = SelectTenantUseCase(_repository, _tokenStorage);
      final tokens = await selectUseCase.execute(
        loginToken: session.loginToken,
        tenantCode: session.memberships.first.tenantCode,
      );
      return AuthenticatedResult(tokens);
    }

    // AC9: multiple memberships — surface picker, keep loginToken in memory only
    return NeedsTenantSelectionResult(session.loginToken, session.memberships);
  }
}
