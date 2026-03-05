import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/network/auth_interceptor.dart';
import '../../../../core/router/app_router.dart';
import '../../data/datasource/remote_auth_datasource.dart';
import '../../data/repository/auth_repository_impl.dart';
import '../../data/repository/secure_token_storage.dart';
import '../../domain/model/auth_tokens.dart';
import '../../domain/model/registration_result.dart';
import '../../domain/repository/auth_repository.dart';
import '../../domain/repository/token_storage.dart';
import '../../domain/usecase/login_usecase.dart';
import '../../domain/usecase/register_user_usecase.dart';

part 'auth_provider.g.dart';

// ── Infrastructure providers ─────────────────────────────────────────────────

/// Base API URL — replace with environment-based config in Story 1.3.
const _apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://10.0.3.2:8080', // Genymotion emulator → localhost (AVD: 10.0.2.2)
);

/// Secure storage — singleton instance (AC5).
final flutterSecureStorageProvider = Provider<FlutterSecureStorage>((ref) {
  return const FlutterSecureStorage();
});

/// Token storage provider — AC5: secure token persistence.
final tokenStorageProvider = Provider<TokenStorage>((ref) {
  return SecureTokenStorage(ref.watch(flutterSecureStorageProvider));
});

/// Separate Dio instance used ONLY for refresh token calls.
/// Bypasses AuthInterceptor to prevent infinite refresh loops.
final _refreshDioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: _apiBaseUrl,
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 10),
    headers: const {'Content-Type': 'application/json'},
  ));
  ref.onDispose(dio.close);
  return dio;
});

/// Dio HTTP client — singleton for connection pooling.
/// Includes AuthInterceptor for automatic token injection + refresh (AC3).
final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: _apiBaseUrl,
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 10),
    headers: const {'Content-Type': 'application/json'},
  ));

  // AC3: JWT interceptor — injects token + handles 401 TOKEN_EXPIRED refresh
  dio.interceptors.add(AuthInterceptor(
    storage: ref.read(tokenStorageProvider),
    dio: dio,
    refreshDio: ref.read(_refreshDioProvider),
    onSessionExpired: () {
      // Clear tokens (already done in interceptor) + navigate to login
      appRouter.go('/auth/login');
    },
  ));

  dio.interceptors.add(LogInterceptor(
    requestHeader: true,
    requestBody: true,
    responseHeader: false,
    responseBody: true,
    error: true,
    logPrint: (o) => debugPrint('[DIO] $o'),
  ));

  ref.onDispose(dio.close);
  return dio;
});

/// Remote data source provider.
final remoteAuthDataSourceProvider = Provider<RemoteAuthDataSource>((ref) {
  return RemoteAuthDataSource(dio: ref.watch(dioProvider));
});

/// Repository provider (typed as interface — dependency inversion).
final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return AuthRepositoryImpl(ref.watch(remoteAuthDataSourceProvider));
});

/// Register use case provider.
final registerUserUseCaseProvider = Provider<RegisterUserUseCase>((ref) {
  return RegisterUserUseCase(
    ref.watch(authRepositoryProvider),
    ref.watch(tokenStorageProvider),
  );
});

/// Login use case provider.
final loginUseCaseProvider = Provider<LoginUseCase>((ref) {
  return LoginUseCase(
    ref.watch(authRepositoryProvider),
    ref.watch(tokenStorageProvider),
  );
});

// ── Registration AsyncNotifier (Riverpod code-gen) ───────────────────────────

/// [Registration] manages the async registration lifecycle.
///
/// State: [AsyncValue<RegistrationResult?>]
/// - Initial / reset: AsyncData(null)
/// - Loading: AsyncLoading()
/// - Success: AsyncData(RegistrationResult(...))
/// - Error: AsyncError(exception, stackTrace)
@riverpod
class Registration extends _$Registration {
  @override
  FutureOr<RegistrationResult?> build() => null;

  Future<void> register({
    required String phoneNumber,
    required String password,
  }) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(registerUserUseCaseProvider).execute(
            phoneNumber: phoneNumber,
            password: password,
          ),
    );
  }
}

// ── Login AsyncNotifier (Riverpod code-gen) ───────────────────────────────────

/// [Login] manages the async login lifecycle.
///
/// State: [AsyncValue<AuthTokens?>]
/// - Initial / reset: AsyncData(null)
/// - Loading: AsyncLoading()
/// - Success: AsyncData(AuthTokens(...))
/// - Error: AsyncError(AuthException, stackTrace)
@riverpod
class Login extends _$Login {
  @override
  FutureOr<AuthTokens?> build() => null;

  Future<void> login({
    required String phoneNumber,
    required String password,
  }) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(loginUseCaseProvider).execute(
            phoneNumber: phoneNumber,
            password: password,
          ),
    );
  }

  /// Reset state to initial (e.g. after navigation or error dismissal).
  void reset() => state = const AsyncData(null);
}

