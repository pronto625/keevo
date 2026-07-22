import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/network/auth_interceptor.dart';
import '../../../../core/network/log_redaction.dart';
import '../../../../core/network/retry_interceptor.dart';
import '../../../../core/router/app_router.dart';
import '../../../../core/storage/secure_storage_provider.dart';
import '../../data/datasource/remote_auth_datasource.dart';
import '../../data/repository/auth_repository_impl.dart';
import '../../data/repository/secure_token_storage.dart';
import '../../domain/model/account_profile.dart';
import '../../domain/model/auth_tokens.dart';
import '../../domain/model/login_result.dart';
import '../../domain/model/registration_result.dart';
import '../../domain/repository/auth_repository.dart';
import '../../domain/repository/token_storage.dart';
import '../../domain/usecase/change_password_usecase.dart';
import '../../domain/usecase/login_usecase.dart';
import '../../domain/usecase/register_user_usecase.dart';
import '../../domain/usecase/select_tenant_usecase.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';

part 'auth_provider.g.dart';

/// Extract firstName from a JWT access token payload.
/// Returns null if not present or on any decode error.
String? _extractFirstNameFromJwt(String accessToken) {
  try {
    final parts = accessToken.split('.');
    if (parts.length != 3) return null;
    final payload = utf8.decode(
      base64Url.decode(base64Url.normalize(parts[1])),
    );
    final claims = jsonDecode(payload) as Map<String, dynamic>;
    return claims['firstName'] as String?;
  } catch (_) {
    return null;
  }
}

// ── Infrastructure providers ─────────────────────────────────────────────────

/// Base API URL — replace with environment-based config in Story 1.3.
const _apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  // defaultValue: 'http://10.0.3.2:4500', 
  defaultValue: 'https://localhost:4500',// local dev (Genymotion); prod: --dart-define=API_BASE_URL=https://<domain>
);

/// Secure storage — hardened singleton instance (AC1).
final flutterSecureStorageProvider = Provider<FlutterSecureStorage>((ref) {
  return buildSecureStorage();
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
  dio.httpClientAdapter = IOHttpClientAdapter(
    createHttpClient: () {
      final client = HttpClient();
      client.badCertificateCallback =
          (cert, host, port) => kDebugMode && host == 'localhost';
      return client;
    },
  );
  dio.interceptors.add(RetryOnConnectionClosedInterceptor(dio));
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

  // HTTPS enforcement — reject invalid certificates in production;
  // allow self-signed only in debug mode for localhost dev (Story 1.3 AC6).
  dio.httpClientAdapter = IOHttpClientAdapter(
    createHttpClient: () {
      final client = HttpClient();
      client.badCertificateCallback =
          (cert, host, port) => kDebugMode && host == 'localhost';
      return client;
    },
  );

  // Retry once on HTTP keep-alive connection-closed errors before giving up.
  dio.interceptors.add(RetryOnConnectionClosedInterceptor(dio));

  // AC3: JWT interceptor — injects token + handles 401 TOKEN_EXPIRED refresh
  dio.interceptors.add(AuthInterceptor(
    storage: ref.read(tokenStorageProvider),
    dio: dio,
    refreshDio: ref.read(_refreshDioProvider),
    onSessionExpired: () {
      // Clear tokens (already done in interceptor) + navigate to login
      appRouter.go('/auth/login');
    },
    onAccountSuspended: () {
      rootScaffoldMessengerKey.currentState?.showSnackBar(
        const SnackBar(
          content: Text(
            'Votre accès a été suspendu, veuillez vous reconnecter.',
          ),
        ),
      );
    },
  ));

  // LogInterceptor — debug-only; redacts Authorization header even in debug
  if (kDebugMode) {
    dio.interceptors.add(LogInterceptor(
      requestHeader: true,
      requestBody: true,
      responseHeader: false,
      responseBody: true,
      error: true,
      logPrint: (o) => debugPrint('[DIO] ${redactAuthorizationHeader(o.toString())}'),
    ));
  }

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
    // AC1 (HF-1): Persist role + phone after successful registration so
    // currentUserRoleProvider rebuilds immediately — no re-login required.
    state.whenData((result) {
      if (result != null) {
        final prefs = ref.read(sharedPreferencesProvider);
        prefs.setString(kUserRoleKey, 'OWNER'); // Registration always creates OWNER
        prefs.setString(kUserPhoneKey, phoneNumber);
        ref.read(activeStoreIdProvider.notifier).setActiveStore(null);
        ref.invalidate(currentUserRoleProvider);
        ref.invalidate(currentUserPhoneProvider);
        ref.invalidate(tenantStatusClaimProvider);
      }
    });
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
        // Story 7.1 AC2: persist firstName from JWT for dashboard greeting.
        // Always overwrite (even with null) so a previous user's name is never shown.
        final firstName = _extractFirstNameFromJwt(loginResult.tokens.accessToken);
        if (firstName != null && firstName.isNotEmpty) {
          prefs.setString('user_first_name', firstName);
        } else {
          prefs.remove('user_first_name');
        }
        // EMPLOYEE: set active store from JWT so POS/Reports use the assigned store.
        // OWNER: clear active store so "all stores" is the default.
        ref.read(activeStoreIdProvider.notifier).setActiveStore(
          loginResult.tokens.role == 'EMPLOYEE' ? loginResult.tokens.storeId : null,
        );
        ref.invalidate(currentUserRoleProvider);
        ref.invalidate(currentUserPhoneProvider);
        ref.invalidate(tenantStatusClaimProvider);
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
      final prefs = ref.read(sharedPreferencesProvider);
      prefs.setString(kUserRoleKey, tokens.role);
      prefs.setBool(kPasswordChangeRequiredKey, tokens.passwordChangeRequired);
      // Story 7.1 AC2: persist firstName from JWT for dashboard greeting.
      // Always overwrite (even with null) so a previous user's name is never shown.
      final firstName = _extractFirstNameFromJwt(tokens.accessToken);
      if (firstName != null && firstName.isNotEmpty) {
        prefs.setString('user_first_name', firstName);
      } else {
        prefs.remove('user_first_name');
      }
      // EMPLOYEE: set active store from JWT so POS/Reports use the assigned store.
      // OWNER: clear active store so "all stores" is the default.
      ref.read(activeStoreIdProvider.notifier).setActiveStore(
        tokens.role == 'EMPLOYEE' ? tokens.storeId : null,
      );
      ref.invalidate(currentUserRoleProvider);
      ref.invalidate(currentUserPhoneProvider);
      ref.invalidate(tenantStatusClaimProvider);
    });
    state = result;
  }

  /// Reset state to initial.
  void reset() => state = const AsyncData(null);
}

// ── Account Profile FutureProvider (Story 8.6 AC3) ───────────────────────────

/// [accountProfileProvider] loads the authenticated user's profile from the backend.
///
/// Uses [FutureProvider.autoDispose] so the provider resets when [AccountPage]
/// is closed — prevents stale data if the user navigates away and returns.
final accountProfileProvider = FutureProvider.autoDispose<AccountProfile>((ref) {
  return ref.watch(authRepositoryProvider).getProfile();
});

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
      ref.read(sharedPreferencesProvider).setBool(kPasswordChangeRequiredKey, false);
    });
    state = result;
  }
}


