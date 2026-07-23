import '../../domain/model/account_profile.dart';
import '../../domain/model/auth_tokens.dart';
import '../../domain/model/login_session_response.dart';
import '../../domain/model/registration_result.dart';
import '../../domain/repository/auth_repository.dart';
import '../datasource/remote_auth_datasource.dart';

/// AuthRepositoryImpl — Concrete implementation of [AuthRepository].
///
/// Delegates to [RemoteAuthDataSource] and re-throws [AuthException] unchanged.
/// Future stories may add caching or offline support here.
class AuthRepositoryImpl implements AuthRepository {
  final RemoteAuthDataSource _remoteDataSource;

  const AuthRepositoryImpl(this._remoteDataSource);

  @override
  Future<RegistrationResult> register({
    required String phoneNumber,
    required String password,
  }) async {
    // AuthException from datasource propagates unchanged — no adaptation needed
    return _remoteDataSource.register(
      phoneNumber: phoneNumber,
      password: password,
    );
  }

  @override
  Future<LoginSessionResponse> login({
    required String phoneNumber,
    required String password,
  }) async {
    return _remoteDataSource.login(
      phoneNumber: phoneNumber,
      password: password,
    );
  }

  @override
  Future<AuthTokens> selectTenant({
    required String loginToken,
    required String tenantCode,
  }) async {
    return _remoteDataSource.selectTenant(
      loginToken: loginToken,
      tenantCode: tenantCode,
    );
  }

  @override
  Future<AuthTokens> refreshToken(String rawRefreshToken) async {
    return _remoteDataSource.refreshToken(rawRefreshToken);
  }

  @override
  Future<AuthTokens> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    return _remoteDataSource.changePassword(
      currentPassword: currentPassword,
      newPassword: newPassword,
    );
  }

  @override
  Future<AccountProfile> getProfile() async {
    return _remoteDataSource.getProfile();
  }

  @override
  Future<bool> forgotPassword({required String phoneNumber}) async {
    return _remoteDataSource.forgotPassword(phoneNumber: phoneNumber);
  }

  @override
  Future<void> resetPassword({
    required String phoneNumber,
    required String code,
    required String newPassword,
  }) async {
    return _remoteDataSource.resetPassword(
      phoneNumber: phoneNumber,
      code: code,
      newPassword: newPassword,
    );
  }
}
