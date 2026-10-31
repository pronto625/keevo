import 'dart:io';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:keevo_core/auth/jwt_claims.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/network/api_base_url.dart';
import 'package:keevo_core/network/log_redaction.dart';
import 'package:keevo_core/network/module_entitlement_exception.dart';
import 'package:keevo_core/network/retry_interceptor.dart';
import 'package:keevo_core/storage/secure_storage_provider.dart';
import 'package:keevo_core/widget/notice_throttle.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../../../core/auth/session_refresher.dart';
import '../../../../core/network/auth_interceptor.dart';
import '../../../../core/router/app_router.dart';
import '../../../../core/sync/sync_trigger_notifier.dart';
import '../../../settings/presentation/provider/account_status_provider.dart';
import '../../../stores/presentation/provider/active_store_provider.dart';
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
import 'session_claims_applier.dart';

part 'auth_provider.g.dart';

// ── Infrastructure providers ─────────────────────────────────────────────────

/// Base API URL — `--dart-define=API_BASE_URL=…`; localhost in debug/profile,
/// mandatory https:// in release (checked at startup, see [apiBaseUrl]).
final _apiBaseUrl = apiBaseUrl;

/// Secure storage — hardened singleton instance (AC1).
final flutterSecureStorageProvider = Provider<FlutterSecureStorage>((ref) {
  return buildSecureStorage();
});

/// Token storage provider — AC5: secure token persistence.
final tokenStorageProvider = Provider<TokenStorage>((ref) {
  return SecureTokenStorage(ref.watch(flutterSecureStorageProvider));
});

/// HttpClient shared by every Dio instance of the app.
///
/// HTTPS enforcement — invalid certificates are rejected in production; a
/// self-signed certificate is accepted only in debug mode for localhost dev
/// (Story 1.3 AC6).
HttpClient _createHttpClient() {
  final client = HttpClient();
  client.badCertificateCallback =
      (cert, host, port) => kDebugMode && host == 'localhost';
  return client;
}

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
    createHttpClient: _createHttpClient,
  );
  dio.interceptors.add(RetryOnConnectionClosedInterceptor(dio));
  ref.onDispose(dio.close);
  return dio;
});

/// The app's single session refresher, shared by the HTTP interceptor and the
/// splash so that only one refresh ever runs at a time (refresh tokens are
/// single-use). Every renewed token is applied to app state.
final sessionRefresherProvider = Provider<SessionRefresher>((ref) {
  return SessionRefresher(
    storage: ref.read(tokenStorageProvider),
    refreshDio: ref.read(_refreshDioProvider),
    onRenewed: (accessToken) =>
        ref.read(sessionClaimsApplierProvider).apply(accessToken),
  );
});

/// Shown when the platform super-admin account is used in the mobile app —
/// at login, or when such a session is found on the device at startup.
const kPlatformAdminAccountMessage =
    "Ce compte est réservé au tableau de bord d'administration Keevo. "
    'Connectez-vous avec un compte commerçant.';

/// English counterpart of [kPlatformAdminAccountMessage] (login page in EN).
const kPlatformAdminAccountMessageEn =
    'This account is reserved for the Keevo admin dashboard. '
    'Sign in with a merchant account.';

/// Shows the "session terminée" SnackBar (suspension, reactivation, password
/// change or deactivation revoke the session server-side) unless [throttle] already let one
/// through in its window (v1s-16-13: N concurrent SESSION_REVOKED → one).
@visibleForTesting
void showSuspendedSessionNotice(
    NoticeThrottle throttle, ScaffoldMessengerState? messenger) {
  // No messenger mounted yet (splash, route transition): do not use up the
  // window with nothing shown.
  if (messenger == null || !throttle.tryAcquire()) return;
  messenger.showSnackBar(
    const SnackBar(
      content: Text('Votre session a pris fin suite à un changement sur votre compte. Veuillez vous reconnecter.'),
    ),
  );
}

/// One "session terminée" SnackBar per 10 s window (v1s-16-13): concurrent
/// requests refused with SESSION_REVOKED each report it.
final suspendedSessionNoticeThrottleProvider =
    Provider<NoticeThrottle>((ref) => NoticeThrottle());

/// Dio HTTP client — singleton for connection pooling.
/// Includes AuthInterceptor for automatic token injection + refresh (AC3).
final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: _apiBaseUrl,
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 10),
    headers: const {'Content-Type': 'application/json'},
  ));

  // HTTPS enforcement: see [_createHttpClient].
  dio.httpClientAdapter = IOHttpClientAdapter(
    createHttpClient: _createHttpClient,
  );

  // Retry once on HTTP keep-alive connection-closed errors before giving up.
  dio.interceptors.add(RetryOnConnectionClosedInterceptor(dio));

  final suspendedNotice = ref.read(suspendedSessionNoticeThrottleProvider);

  // AC3: JWT interceptor — injects token + handles 401 TOKEN_EXPIRED refresh
  dio.interceptors.add(AuthInterceptor(
    storage: ref.read(tokenStorageProvider),
    dio: dio,
    refreshDio: ref.read(_refreshDioProvider),
    refresher: ref.read(sessionRefresherProvider),
    onSessionExpired: () {
      // Clear tokens (already done in interceptor) + navigate to login
      appRouter.go('/auth/login');
    },
    onAccountSuspended: () => showSuspendedSessionNotice(
        suspendedNotice, rootScaffoldMessengerKey.currentState),
  ));

  // 13.10b: 403 MODULE_NOT_ENTITLED / MODULE_READ_ONLY → typed
  // ModuleEntitlementException (no logout, no renewal, no retry).
  dio.interceptors.add(ModuleEntitlementInterceptor());

  // LogInterceptor — debug-only; redacts the Authorization header and the
  // secrets of bodies / query strings (passwords, codes, tokens) even in debug
  if (kDebugMode) {
    dio.interceptors.add(LogInterceptor(
      requestHeader: true,
      requestBody: true,
      responseHeader: false,
      responseBody: true,
      error: true,
      logPrint: (o) => debugPrint('[DIO] ${redactLogLine(o.toString())}'),
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
    final registered = await AsyncValue.guard(
      () => ref.read(registerUserUseCaseProvider).execute(
            phoneNumber: phoneNumber,
            password: password,
          ),
    );
    // AC1 (HF-1): Persist role + phone after successful registration so
    // currentUserRoleProvider rebuilds immediately — no re-login required.
    // The success state is only published once the session is applied.
    final result = registered.valueOrNull;
    if (result == null) {
      state = registered;
      return;
    }
    await _applySession(result, phoneNumber);
  }

  RegistrationResult? _pending;
  String? _pendingPhone;

  /// The user accepted losing the previous account's unsynced operations.
  Future<void> discardPreviousAccountDataAndContinue() async {
    final result = _pending;
    final phone = _pendingPhone;
    if (result == null || phone == null) return;
    state = const AsyncLoading();
    await _applySession(result, phone, discardPreviousAccountData: true);
  }

  /// The user kept the previous account's operations: the new session ends.
  Future<void> cancelPendingSignIn() async {
    _pending = null;
    await ref.read(sessionClaimsApplierProvider).abandonSignIn();
    state = const AsyncData(null);
  }

  Future<void> _applySession(
    RegistrationResult result,
    String phoneNumber, {
    bool discardPreviousAccountData = false,
  }) async {
    try {
      await ref.read(sessionClaimsApplierProvider).apply(result.token,
          newSession: true,
          discardPreviousAccountData: discardPreviousAccountData);
      final prefs = ref.read(sharedPreferencesProvider);
      // The server's role claim wins; registration creates an OWNER, so that
      // is only the fallback for a token without the claim.
      await prefs.setString(
          kUserRoleKey, JwtClaims.tryParse(result.token)?.role ?? 'OWNER');
      await prefs.setString(kUserPhoneKey, phoneNumber);
      ref.read(activeStoreIdProvider.notifier).setActiveStore(null);
      ref.invalidate(currentUserRoleProvider);
      ref.invalidate(currentUserPhoneProvider);
      ref.invalidate(tenantStatusClaimProvider);
      _pending = null;
      state = AsyncData(result);
    } catch (e, st) {
      if (e is PreviousAccountDataException) {
        _pending = result;
        _pendingPhone = phoneNumber;
      }
      state = AsyncError(e, st);
    }
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
    // Persist phone + token claims (role, store, flags) through the same path
    // as every other token, so UI rebuilds immediately.
    final loginResult = result.valueOrNull;
    if (loginResult is AuthenticatedResult) {
      await _applySession(loginResult, phoneNumber);
      return;
    }
    state = result;
  }

  AuthenticatedResult? _pending;
  String? _pendingPhone;

  /// The user accepted losing the previous account's unsynced operations.
  Future<void> discardPreviousAccountDataAndContinue() async {
    final result = _pending;
    final phone = _pendingPhone;
    if (result == null || phone == null) return;
    state = const AsyncLoading();
    await _applySession(result, phone, discardPreviousAccountData: true);
  }

  /// The user kept the previous account's operations: the new session ends.
  Future<void> cancelPendingSignIn() async {
    _pending = null;
    await ref.read(sessionClaimsApplierProvider).abandonSignIn();
    state = const AsyncData(null);
  }

  Future<void> _applySession(
    AuthenticatedResult result,
    String phoneNumber, {
    bool discardPreviousAccountData = false,
  }) async {
    try {
      await ref.read(sessionClaimsApplierProvider).apply(
          result.tokens.accessToken,
          newSession: true,
          discardPreviousAccountData: discardPreviousAccountData);
      await ref.read(sharedPreferencesProvider).setString(kUserPhoneKey, phoneNumber);
      ref.invalidate(currentUserPhoneProvider);

      // Story 16.8 AC2: pull latest server state after fresh login.
      ref.read(syncTriggerNotifierProvider.notifier).onAppStartup();
      _pending = null;
      state = AsyncData(result);
    } catch (e, st) {
      // Never leave the button spinning: a storage or DB failure, or another
      // account's unsynced operations (the page asks the user), end in error.
      if (e is PreviousAccountDataException) {
        _pending = result;
        _pendingPhone = phoneNumber;
      }
      state = AsyncError(e, st);
    }
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
    final tokens = result.valueOrNull;
    if (tokens != null) {
      await _applySession(tokens);
      return;
    }
    state = result;
  }

  AuthTokens? _pending;

  /// The user accepted losing the previous account's unsynced operations.
  Future<void> discardPreviousAccountDataAndContinue() async {
    final tokens = _pending;
    if (tokens == null) return;
    state = const AsyncLoading();
    await _applySession(tokens, discardPreviousAccountData: true);
  }

  /// The user kept the previous account's operations: the new session ends.
  Future<void> cancelPendingSignIn() async {
    _pending = null;
    await ref.read(sessionClaimsApplierProvider).abandonSignIn();
    state = const AsyncData(null);
  }

  Future<void> _applySession(AuthTokens tokens,
      {bool discardPreviousAccountData = false}) async {
    try {
      await ref.read(sessionClaimsApplierProvider).apply(tokens.accessToken,
          newSession: true,
          discardPreviousAccountData: discardPreviousAccountData);

      // Story 16.8 AC2: pull latest server state after tenant selection.
      ref.read(syncTriggerNotifierProvider.notifier).onAppStartup();
      _pending = null;
      state = AsyncData(tokens);
    } catch (e, st) {
      if (e is PreviousAccountDataException) _pending = tokens;
      state = AsyncError(e, st);
    }
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
    final tokens = result.valueOrNull;
    if (tokens != null) {
      try {
        await ref.read(sharedPreferencesProvider).setBool(kPasswordChangeRequiredKey, false);
        await ref.read(sessionClaimsApplierProvider).apply(tokens.accessToken);
      } catch (e, st) {
        // Never leave the button spinning on a storage failure.
        state = AsyncError(e, st);
        return;
      }
    }
    state = result;
  }
}

// ── ForgotPassword AsyncNotifier (Riverpod code-gen) — Story 14.12 ────────────

/// [ForgotPassword] manages the forgot-password async lifecycle.
///
/// State: [AsyncValue<bool?>]
/// - Initial / reset: AsyncData(null)
/// - Loading: AsyncLoading()
/// - Success: AsyncData(true) (anti-enumeration — always true)
/// - Error: AsyncError(exception, stackTrace)
@riverpod
class ForgotPassword extends _$ForgotPassword {
  @override
  FutureOr<bool?> build() => null;

  Future<void> requestReset({required String phoneNumber}) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(authRepositoryProvider).forgotPassword(
            phoneNumber: phoneNumber,
          ),
    );
  }
}

// ── ResetPassword AsyncNotifier (Riverpod code-gen) — Story 14.12 ─────────────

/// [ResetPassword] manages the reset-password async lifecycle.
///
/// State: [AsyncValue<void>]
/// - Initial / reset: AsyncData(null)
/// - Loading: AsyncLoading()
/// - Error: AsyncError(AuthException, stackTrace)
@riverpod
class ResetPassword extends _$ResetPassword {
  @override
  FutureOr<void> build() => null;

  Future<void> reset({
    required String phoneNumber,
    required String code,
    required String newPassword,
  }) async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(
      () => ref.read(authRepositoryProvider).resetPassword(
            phoneNumber: phoneNumber,
            code: code,
            newPassword: newPassword,
          ),
    );
  }
}


