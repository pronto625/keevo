import '../../domain/model/auth_tokens.dart';
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
  Future<AuthTokens> login({
    required String phoneNumber,
    required String password,
  }) async {
    return _remoteDataSource.login(
      phoneNumber: phoneNumber,
      password: password,
    );
  }

  @override
  Future<AuthTokens> refreshToken(String rawRefreshToken) async {
    return _remoteDataSource.refreshToken(rawRefreshToken);
  }
}
