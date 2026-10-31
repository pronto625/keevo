import 'dart:async';
import 'dart:io' show IOException;

import 'package:dio/dio.dart';
import 'package:drift/native.dart' show SqliteException;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PlatformException;

import '../network/module_entitlement_exception.dart';
import '../sync/account_read_only_exception.dart';
import 'entitlement_upgrade.dart';
import 'soft_icon_badge.dart';

/// Generic French message when nothing readable can be shown.
const kAppGenericErrorMessage =
    'Une erreur inattendue est survenue. Réessayez plus tard.';

/// Technical data a user must never read (v1s-16-20): a UUID, a raw
/// SNAKE_CASE code, JSON, an exception class / stack noise or an HTTP code.
final _technical = RegExp(
    r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
    r'|\b[A-Z]+(_[A-Z0-9]+)+\b'
    r'|[{\[]"'
    r'|Exception|Error\b|\bnull\b|Bad state|status code|#0 '
    r"|Instance of '"
    // HTTP reason phrases of a framework error body ({error: 'Not Found'}).
    r'|^(Not Found|Bad Request|Forbidden|Unauthorized|Conflict'
    r'|Method Not Allowed|Internal Server Error|Service Unavailable'
    r'|Bad Gateway|Gateway Timeout)$');

/// [value] when it is a String (an API body field may be a Map / List).
String? _text(Object? value) => value is String ? value : null;

/// The `message` getter of an exception, when it has a String one.
String? _messageOf(Object error) {
  try {
    final dynamic e = error;
    final Object? m = e.message;
    return m is String ? m : null;
  } catch (_) {
    return null;
  }
}

/// [message] when it is readable French for the user, else `null`.
String? _readable(String? message) {
  final m = message?.trim();
  if (m == null || m.isEmpty || _technical.hasMatch(m)) return null;
  return m;
}

/// Extracts a human-readable error message from any exception type.
///
/// Priority:
///  1. DioException: parses the API body (`error`, `message`, `domainCode`)
///  2. Regular Exception: strips internal stack noise
///  3. Fallback generic message
///
/// v1s-16-20: never returns an id, a raw code, an HTTP status code or an
/// exception text — such messages fall back to a generic French message.
String appErrorMessage(Object error) {
  // 13.10b: module entitlement refusal (403 MODULE_NOT_ENTITLED /
  // MODULE_READ_ONLY), whatever wraps it.
  final refusal = ModuleEntitlementException.of(error);
  if (refusal != null) return refusal.message;
  if (error is DioException) {
    final body = error.response?.data;
    if (body is Map) {
      final domainCode = _text(body['domainCode']);
      if (domainCode != null) return _domainMessage(domainCode, body);
      final msg =
          _readable(_text(body['error'])) ?? _readable(_text(body['message']));
      if (msg != null) return msg;
    }
    if (error.type == DioExceptionType.connectionTimeout ||
        error.type == DioExceptionType.receiveTimeout ||
        error.type == DioExceptionType.sendTimeout) {
      return 'La requête a expiré. Veuillez vérifier votre connexion.';
    }
    if (error.type == DioExceptionType.connectionError ||
        error.error is IOException) {
      return 'Impossible de se connecter au serveur. Vérifiez votre réseau.';
    }
    return 'Le serveur n\'a pas pu traiter la demande. Réessayez plus tard.';
  }
  if (error is Exception) {
    // App domain exceptions (ContactException, ProductException…) carry a
    // French `message`: show it when readable.
    // Dart core exceptions (FormatException…) carry English text: skipped.
    // Platform / IO / SQLite errors carry English technical text: skipped.
    final own = error is FormatException ||
            error is TimeoutException ||
            error is IOException ||
            error is SqliteException ||
            error is PlatformException
        ? null
        : _readable(_messageOf(error));
    if (own != null) return own;
    final raw = error.toString();
    // `FormatException: …` and the like are technical as a whole.
    if (!raw.startsWith('Exception: ') && _technical.hasMatch(raw)) {
      return kAppGenericErrorMessage;
    }
    return _readable(raw.replaceFirst('Exception: ', '')) ??
        kAppGenericErrorMessage;
  }
  // A notifier that stores its error as text (e.g. StockState.error).
  if (error is String) return _readable(error) ?? kAppGenericErrorMessage;
  return kAppGenericErrorMessage;
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
    case 'ACCOUNT_SUSPENDED':
    case 'ACCOUNT_DELETION_PENDING':
      return AccountReadOnlyException.forDomainCode(code)!.message;
    case ModuleEntitlementException.notEntitledCode:
    case ModuleEntitlementException.readOnlyCode:
      return ModuleEntitlementException.forDomainCode(code)!.message;
    default:
      return _readable(_text(body['error'])) ??
          _readable(_text(body['message'])) ??
          kAppGenericErrorMessage;
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
class AppErrorWidget extends StatefulWidget {
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
  State<AppErrorWidget> createState() => _AppErrorWidgetState();
}

class _AppErrorWidgetState extends State<AppErrorWidget> {
  // A provider refreshed from an error keeps its AsyncError until the new
  // result arrives (`skipLoadingOnRefresh`), so the tap needs its own
  // feedback. Cleared when a new error arrives; the button stays tappable.
  bool _retrying = false;

  @override
  void didUpdateWidget(AppErrorWidget old) {
    super.didUpdateWidget(old);
    if (!identical(old.error, widget.error)) _retrying = false;
  }

  void _retry() {
    setState(() => _retrying = true);
    widget.onRetry();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    // 13.10b: a module refusal is not a failure — premium badge, its own
    // title and the « Passer à Premium » action next to « Réessayer ».
    final refusal = ModuleEntitlementException.of(widget.error);
    return Center(
      child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SoftIconBadge(
                icon: refusal != null
                    ? Icons.workspace_premium_rounded
                    : Icons.error_outline_rounded,
                color: refusal != null ? cs.primary : cs.error,
                size: 72,
              ),
              const SizedBox(height: 16),
              // Only the explanation is announced; the button stays a
              // normal focusable control.
              Semantics(
                liveRegion: true,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                        refusal != null
                            ? _refusalTitle(refusal)
                            : widget.title ?? 'Un problème est survenu',
                        style: tt.titleMedium,
                        textAlign: TextAlign.center),
                    const SizedBox(height: 8),
                    Text(
                        refusal?.message ??
                            widget.message ??
                            appErrorMessage(widget.error),
                        style:
                            tt.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                        textAlign: TextAlign.center),
                  ],
                ),
              ),
              const SizedBox(height: 24),
              if (refusal != null) ...[
                const PremiumUpgradeButton(),
                const SizedBox(height: 8),
              ],
              FilledButton.tonalIcon(
                onPressed: _retry,
                icon: Icon(_retrying
                    ? Icons.hourglass_top_rounded
                    : Icons.refresh_rounded),
                label: Text(_retrying ? 'Nouvel essai…' : 'Réessayer'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Title of the full-page state for a module refusal (the page's own title,
/// e.g. « Impossible de charger … », would read as a failure).
String _refusalTitle(ModuleEntitlementException refusal) =>
    switch (refusal.reason) {
      ModuleRefusalReason.notEntitled => 'Fonctionnalité non incluse',
      ModuleRefusalReason.readOnly => 'Abonnement expiré',
    };

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
          if (ModuleEntitlementException.of(error) != null)
            const PremiumUpgradeButton(compact: true),
        ],
      ),
    );
  }
}
