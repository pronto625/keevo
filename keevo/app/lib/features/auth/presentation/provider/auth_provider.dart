import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/network/auth_interceptor.dart';
import '../../../../core/router/app_router.dart';
import '../../../../core/services/api_service.dart';
import '../../data/datasource/remote_auth_datasource.dart';
import '../../data/repository/auth_repository_impl.dart';
import '../../data/repository/secure_token_storage.dart';
import '../../domain/model/auth_tokens.dart';
import '../../domain/model/login_result.dart';
import '../../domain/model/registration_result.dart';
import '../../domain/repository/auth_repository.dart';
import '../../domain/repository/token_storage.dart';
import '../../domain/usecase/change_password_usecase.dart';
import '../../domain/usecase/login_usecase.dart';
import '../../domain/usecase/register_user_usecase.dart';
import '../../domain/usecase/select_tenant_usecase.dart';

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

/// Select tenant use case provider (Story 1.7 — two-step login step 2).
final selectTenantUseCaseProvider = Provider<SelectTenantUseCase>((ref) {
  return SelectTenantUseCase(
    ref.watch(authRepositoryProvider),
    ref.watch(tokenStorageProvider),
  );
});

/// Change password use case provider (Story 3.5 — AC4).
final changePasswordUseCaseProvider = Provider<ChangePasswordUseCase>((ref) {
  return ChangePasswordUseCase(
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

/// [Login] manages the async login lifecycle (Story 1.7 — two-step flow).
///
/// State: [AsyncValue<LoginResult?>]
/// - Initial / reset: AsyncData(null)
/// - Loading: AsyncLoading()
/// - Single membership: AsyncData(AuthenticatedResult(tokens))
/// - Multi membership: AsyncData(NeedsTenantSelectionResult(...))
/// - Error: AsyncError(AuthException, stackTrace)
@riverpod
class Login extends _$Login {
  @override
  FutureOr<LoginResult?> build() => null;

  Future<void> login({
    required String phoneNumber,
    required String password,
  }) async {
    state = const AsyncLoading();
    final result = await AsyncValue.guard(
      () => ref.read(loginUseCaseProvider).execute(
            phoneNumber: phoneNumber,
            password: password,
          ),
    );
    // Persist role + phone; invalidate providers so UI rebuilds immediately.
    result.whenData((loginResult) {
      if (loginResult is AuthenticatedResult) {
        final prefs = ref.read(sharedPreferencesProvider);
        prefs.setString(kUserRoleKey, loginResult.tokens.role);
        prefs.setString(kUserPhoneKey, phoneNumber);
        prefs.setBool(kPasswordChangeRequiredKey, loginResult.tokens.passwordChangeRequired);
        ref.invalidate(currentUserRoleProvider);
        ref.invalidate(currentUserPhoneProvider);
      }
    });
    state = result;
  }

  /// Reset state to initial (e.g. after navigation or error dismissal).
  void reset() => state = const AsyncData(null);
}

// ── SelectTenant AsyncNotifier (Riverpod code-gen) ───────────────────────────

/// [SelectTenant] manages the tenant selection step (AC9, Story 1.7).
///
/// State: [AsyncValue<AuthTokens?>]
/// - Initial / reset: AsyncData(null)
/// - Loading: AsyncLoading()
/// - Success: AsyncData(AuthTokens(...))
/// - Error: AsyncError(AuthException, stackTrace)
@riverpod
class SelectTenant extends _$SelectTenant {
  @override
  FutureOr<AuthTokens?> build() => null;

  Future<void> select({
    required String loginToken,
    required String tenantCode,
  }) async {
    state = const AsyncLoading();
    final result = await AsyncValue.guard(
      () => ref.read(selectTenantUseCaseProvider).execute(
            loginToken: loginToken,
            tenantCode: tenantCode,
          ),
    );
    // Persist role; invalidate provider so SettingsPage role-guard rebuilds.
    result.whenData((tokens) {
      if (tokens != null) {
        final prefs = ref.read(sharedPreferencesProvider);
        prefs.setString(kUserRoleKey, tokens.role);
        prefs.setBool(kPasswordChangeRequiredKey, tokens.passwordChangeRequired);
        ref.invalidate(currentUserRoleProvider);
        ref.invalidate(currentUserPhoneProvider);
      }
    });
    state = result;
  }

  /// Reset state to initial.
  void reset() => state = const AsyncData(null);
}

// ── ChangePassword AsyncNotifier (Riverpod code-gen) ─────────────────────────

/// [ChangePassword] manages the async password change lifecycle (Story 3.5, AC4).
///
/// State: [AsyncValue<AuthTokens?>]
/// - Initial / reset: AsyncData(null)
/// - Loading: AsyncLoading()
/// - Success: AsyncData(AuthTokens(...))
/// - Error: AsyncError(exception, stackTrace)
@riverpod
class ChangePassword extends _$ChangePassword {
  @override
  FutureOr<AuthTokens?> build() => null;

  Future<void> change({
    required String currentPassword,
    required String newPassword,
  }) async {
    state = const AsyncLoading();
    final result = await AsyncValue.guard(
      () => ref.read(changePasswordUseCaseProvider).execute(
            currentPassword: currentPassword,
            newPassword: newPassword,
          ),
    );
    // Clear the password change required flag on success
    result.whenData((tokens) {
      if (tokens != null) {
        ref.read(sharedPreferencesProvider).setBool(kPasswordChangeRequiredKey, false);
      }
    });
    state = result;
  }
}


