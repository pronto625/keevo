import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

/// Extracts a human-readable error message from any exception type.
///
/// Priority:
///  1. DioException: parses the API body (`error`, `message`, `domainCode`)
///  2. Regular Exception: strips internal stack noise
///  3. Fallback generic message
String appErrorMessage(Object error) {
  if (error is DioException) {
    final body = error.response?.data;
    if (body is Map) {
      final domainCode = body['domainCode'] as String?;
      if (domainCode != null) return _domainMessage(domainCode, body);
      final msg = body['error'] as String? ?? body['message'] as String?;
      if (msg != null && msg.isNotEmpty) return msg;
    }
    if (error.type == DioExceptionType.connectionTimeout ||
        error.type == DioExceptionType.receiveTimeout ||
        error.type == DioExceptionType.sendTimeout) {
      return 'La requête a expiré. Veuillez vérifier votre connexion.';
    }
    if (error.type == DioExceptionType.connectionError) {
      return 'Impossible de se connecter au serveur. Vérifiez votre réseau.';
    }
    return 'Erreur réseau (${error.response?.statusCode ?? 'inconnu'})';
  }
  if (error is Exception) {
    final msg = error.toString().replaceFirst('Exception: ', '');
    // Never expose UUIDs or stack traces to the user.
    if (RegExp(r'[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}').hasMatch(msg)) {
      return 'Une erreur inattendue est survenue. Réessayez plus tard.';
    }
    if (msg.isNotEmpty) return msg;
  }
  return 'Une erreur inattendue est survenue. Réessayez plus tard.';
}

/// Maps backend domain codes to French user-facing messages.
String _domainMessage(String code, Map<dynamic, dynamic> body) {
  switch (code) {
    case 'PRODUCT_NOT_FOUND':
      return 'Produit introuvable ou supprimé.';
    case 'INSUFFICIENT_STOCK':
      final available = (body['details'] as Map?)?['available'] ?? 0;
      return 'Stock insuffisant — disponible\u00a0: $available unité(s)';
    case 'TENANT_NOT_FOUND':
      return 'Compte introuvable. Reconnectez-vous.';
    case 'UNAUTHORIZED':
    case 'ACCESS_DENIED':
      return 'Accès non autorisé.';
    case 'SALE_ALREADY_VALIDATED':
      return 'Cette vente a déjà été validée.';
    case 'SALE_ALREADY_CANCELLED':
      return 'Cette vente a déjà été annulée.';
    default:
      final msg = body['error'] as String? ?? body['message'] as String?;
      return msg != null && msg.isNotEmpty
          ? msg
          : 'Erreur ($code). Réessayez plus tard.';
  }
}

// ── Full-page / section error widget ─────────────────────────────────────────

/// A centered error state with icon, message, and optional retry button.
///
/// Use inside `SliverFillRemaining`, `Expanded`, or `Center` contexts.
///
/// ```dart
/// error: (e, _) => SliverFillRemaining(child: AppErrorWidget(error: e)),
/// error: (e, _) => Center(child: AppErrorWidget(error: e, onRetry: () => ref.invalidate(myProvider))),
/// ```
class AppErrorWidget extends StatelessWidget {
  final Object error;
  final VoidCallback? onRetry;

  const AppErrorWidget({super.key, required this.error, this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline_rounded, size: 56, color: cs.error),
            const SizedBox(height: 16),
            Text('Un problème est survenu',
                style: tt.titleMedium, textAlign: TextAlign.center),
            const SizedBox(height: 8),
            Text(appErrorMessage(error),
                style: tt.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                textAlign: TextAlign.center),
            if (onRetry != null) ...[
              const SizedBox(height: 24),
              OutlinedButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('Réessayer'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

// ── Compact inline error (for bottom-sheets / small containers) ───────────────

/// Compact error row — suitable for inside ListTiles, bottom sheets, cards.
class AppErrorInline extends StatelessWidget {
  final Object error;

  const AppErrorInline({super.key, required this.error});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.all(12),
      child: Row(
        children: [
          Icon(Icons.warning_amber_rounded, color: cs.error, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              appErrorMessage(error),
              style: TextStyle(color: cs.error, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}
