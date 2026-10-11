import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import 'soft_icon_badge.dart';

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

/// The single full-page / section error state (UI-3 Soft): a diluted error
/// badge, a title, a human French message and a mandatory "Réessayer" button.
///
/// [onRetry] is required — a screen-level error must never be a dead end.
/// It should invalidate (or refresh) the exact provider, with the same family
/// args, whose `AsyncValue` is in error. [title] and [message] override the
/// defaults ('Un problème est survenu' / [appErrorMessage]).
///
/// Safe inside `SliverFillRemaining`, `Expanded` or `Center`: the content
/// scrolls instead of overflowing in short slots.
///
/// ```dart
/// error: (e, _) => SliverFillRemaining(
///   child: AppErrorWidget(error: e, onRetry: () => ref.invalidate(myProvider)),
/// ),
/// ```
class AppErrorWidget extends StatelessWidget {
  final Object error;
  final VoidCallback onRetry;
  final String? title;
  final String? message;

  const AppErrorWidget({
    super.key,
    required this.error,
    required this.onRetry,
    this.title,
    this.message,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    return Center(
      child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Semantics(
            liveRegion: true,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SoftIconBadge(
                  icon: Icons.error_outline_rounded,
                  color: cs.error,
                  size: 72,
                ),
                const SizedBox(height: 16),
                Text(title ?? 'Un problème est survenu',
                    style: tt.titleMedium, textAlign: TextAlign.center),
                const SizedBox(height: 8),
                Text(message ?? appErrorMessage(error),
                    style: tt.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                    textAlign: TextAlign.center),
                const SizedBox(height: 24),
                FilledButton.tonalIcon(
                  onPressed: onRetry,
                  icon: const Icon(Icons.refresh_rounded),
                  label: const Text('Réessayer'),
                ),
              ],
            ),
          ),
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
