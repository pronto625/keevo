import 'package:dio/dio.dart';

import '../services/api_service.dart';

/// Why the server refused a module feature (13.10b).
enum ModuleRefusalReason {
  /// 403 `MODULE_NOT_ENTITLED`: the feature is not part of the tenant's offer.
  notEntitled,

  /// 403 `MODULE_READ_ONLY`: subscription expired, the module is read-only
  /// during the grace period.
  readOnly,
}

/// A module entitlement refusal: 403 `MODULE_NOT_ENTITLED` or
/// `MODULE_READ_ONLY` (backend gating, 13.10a).
///
/// Neither an auth problem nor a network one: it never ends the session,
/// renews the token or retries the request. [message] is the French text
/// shown to the user (`appErrorMessage` reads it); the screens add a
/// « Voir mes modules » action opening the owner's « Mes modules » screen
/// (Story 15.6).
class ModuleEntitlementException implements Exception {
  final ModuleRefusalReason reason;

  const ModuleEntitlementException(this.reason);

  static const notEntitledMessage =
      'Cette fonctionnalité fait partie de l\'offre Premium';
  static const readOnlyMessage =
      'Abonnement expiré : ce module est en lecture seule';

  /// Label of the upgrade action next to the message (Story 15.6: the owner
  /// sees his modules and asks support; no self-service purchase in V1).
  static const upgradeLabel = 'Voir mes modules';

  /// Route of the owner's « Mes modules » screen (Story 15.6).
  static const upgradeRoute = '/settings/modules';

  static const notEntitledCode = 'MODULE_NOT_ENTITLED';
  static const readOnlyCode = 'MODULE_READ_ONLY';

  /// The server code of this refusal.
  String get code => switch (reason) {
        ModuleRefusalReason.notEntitled => notEntitledCode,
        ModuleRefusalReason.readOnly => readOnlyCode,
      };

  String get message => switch (reason) {
        ModuleRefusalReason.notEntitled => notEntitledMessage,
        ModuleRefusalReason.readOnly => readOnlyMessage,
      };

  /// The refusal matching a server `domainCode`, null for any other code.
  static ModuleEntitlementException? forDomainCode(String? domainCode) =>
      switch (domainCode) {
        notEntitledCode =>
          const ModuleEntitlementException(ModuleRefusalReason.notEntitled),
        readOnlyCode =>
          const ModuleEntitlementException(ModuleRefusalReason.readOnly),
        _ => null,
      };

  /// The refusal carried by an HTTP answer: a 403 whose body `domainCode` is
  /// one of the two codes. Any other status (or 403 code) → null.
  static ModuleEntitlementException? fromResponse(
      int? statusCode, Object? body) {
    if (statusCode != 403 || body is! Map) return null;
    final code = body['domainCode'];
    return forDomainCode(code is String ? code : null);
  }

  /// The refusal behind any error the app may hold: this exception itself, a
  /// [DioException] (tagged by [ModuleEntitlementInterceptor] or not), an
  /// [ApiException], a feature exception carrying the server `domainCode`
  /// (ReportException, ExportException…), or a text state equal to one of
  /// the two messages.
  static ModuleEntitlementException? of(Object? error) {
    if (error is ModuleEntitlementException) return error;
    if (error is DioException) {
      final inner = error.error;
      if (inner is ModuleEntitlementException) return inner;
      return fromResponse(error.response?.statusCode, error.response?.data);
    }
    if (error is ApiException) {
      return error.statusCode == 403 ? forDomainCode(error.domainCode) : null;
    }
    final featureCode = _domainCodeOf(error);
    if (featureCode != null) return forDomainCode(featureCode);
    if (error is String) {
      final text = error.trim();
      if (text == notEntitledMessage) {
        return const ModuleEntitlementException(
            ModuleRefusalReason.notEntitled);
      }
      if (text == readOnlyMessage) {
        return const ModuleEntitlementException(ModuleRefusalReason.readOnly);
      }
    }
    return null;
  }

  /// The `domainCode` String getter of a feature exception, when it has one.
  static String? _domainCodeOf(Object? error) {
    if (error is! Exception) return null;
    try {
      final dynamic e = error;
      final Object? code = e.domainCode;
      return code is String ? code : null;
    } catch (_) {
      return null;
    }
  }

  @override
  bool operator ==(Object other) =>
      other is ModuleEntitlementException && other.reason == reason;

  @override
  int get hashCode => reason.hashCode;

  @override
  String toString() => message;
}

/// Tags a 403 `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY` answer with a typed
/// [ModuleEntitlementException] (`DioException.error`). The response is kept
/// (callers reading the body still work) and the error is passed on as is:
/// no logout, no token renewal, no retry.
class ModuleEntitlementInterceptor extends Interceptor {
  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    final refusal = ModuleEntitlementException.fromResponse(
        err.response?.statusCode, err.response?.data);
    if (refusal == null || err.error is ModuleEntitlementException) {
      handler.next(err);
      return;
    }
    handler.next(err.copyWith(error: refusal));
  }
}
