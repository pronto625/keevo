import '../../../../core/network/module_entitlement_exception.dart';

/// ExportException — every export failure, mapped to a French message in one
/// place (v1s-18-4).
class ExportException implements Exception {
  final String domainCode;
  final String message;
  final int? statusCode;

  const ExportException({
    required this.domainCode,
    required this.message,
    this.statusCode,
  });

  static const network = ExportException(
    domainCode: 'NETWORK_ERROR',
    message: 'Impossible de joindre le serveur',
  );

  static const offline = ExportException(
    domainCode: 'NETWORK_ERROR',
    message: 'Hors ligne — Mes exports nécessite une connexion',
  );

  static const storage = ExportException(
    domainCode: 'STORAGE_ERROR',
    message: 'Impossible d\'enregistrer le fichier sur l\'appareil '
        '(espace insuffisant ?)',
  );

  /// On-device export (v1s-18-5): the file could not be generated.
  static const localFailed = ExportException(
    domainCode: 'LOCAL_EXPORT_FAILED',
    message: 'Impossible de générer le fichier',
  );

  static const unexpected = ExportException(
    domainCode: 'UNKNOWN',
    message: 'L\'export a échoué. Réessayez.',
  );

  /// Maps an HTTP status and decoded error body (`{error, domainCode}`).
  /// A `null` status means no response reached the app.
  factory ExportException.fromResponse(int? statusCode, Object? body) {
    if (statusCode == null) return network;
    final code = (body is Map ? body['domainCode'] as String? : null) ??
        _defaultCode(statusCode);
    final serverMessage = body is Map && body['error'] is String
        ? (body['error'] as String).trim()
        : null;
    final server =
        serverMessage == null || serverMessage.isEmpty ? null : serverMessage;

    final message = switch (code) {
      'PLAN_FEATURE_REQUIRED' => 'Cette option est réservée au plan Premium',
      // 13.10b: module gating (13.10a) — XLSX / PDF / premium datasets.
      ModuleEntitlementException.notEntitledCode =>
        ModuleEntitlementException.notEntitledMessage,
      ModuleEntitlementException.readOnlyCode =>
        ModuleEntitlementException.readOnlyMessage,
      'RATE_LIMIT_EXCEEDED' ||
      'EXPORT_QUEUE_FULL' =>
        'Trop d\'exports en cours, réessayez dans quelques minutes',
      'EXPORT_EXPIRED' =>
        'Ce fichier a expiré (conservé 24 h). Relancez l\'export.',
      'EXPORT_NOT_READY' => 'Cet export n\'est pas encore prêt',
      'EXPORT_NOT_FOUND' => 'Export introuvable',
      'EXPORT_FORMAT_UNSUPPORTED' ||
      'VALIDATION_ERROR' ||
      'EXPORT_DATASET_UNKNOWN' =>
        server ?? 'Demande d\'export invalide',
      'EXPORT_PDF_TOO_LARGE' => server ??
          'Ce PDF est trop volumineux : réduisez la période ou exportez en Excel',
      'UNAUTHORIZED' => 'Session expirée, reconnectez-vous',
      _ => switch (statusCode) {
          429 => 'Trop d\'exports en cours, réessayez dans quelques minutes',
          403 => server ?? 'Accès refusé',
          _ => server ?? 'L\'export a échoué. Réessayez.',
        },
    };
    return ExportException(
        domainCode: code, message: message, statusCode: statusCode);
  }

  static String _defaultCode(int status) => switch (status) {
        401 => 'UNAUTHORIZED',
        404 => 'EXPORT_NOT_FOUND',
        409 => 'EXPORT_NOT_READY',
        410 => 'EXPORT_EXPIRED',
        429 => 'RATE_LIMIT_EXCEEDED',
        _ => 'UNKNOWN',
      };

  /// The upgrade sheet opens: V1 `PLAN_FEATURE_REQUIRED` or, since 13.10a,
  /// `MODULE_NOT_ENTITLED` (both accepted).
  bool get isPlanGate =>
      domainCode == 'PLAN_FEATURE_REQUIRED' ||
      domainCode == ModuleEntitlementException.notEntitledCode;

  bool get isExpired => domainCode == 'EXPORT_EXPIRED' || statusCode == 410;

  bool get isNetwork => domainCode == 'NETWORK_ERROR';

  bool get isStorage => domainCode == 'STORAGE_ERROR';

  @override
  String toString() => 'ExportException($domainCode, $statusCode): $message';
}
