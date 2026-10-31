import 'dart:async' show DeferredLoadException;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/module/keevo_module_ui.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/deferred_widget.dart';

import '../../features/auth/presentation/provider/auth_provider.dart';
import '../entitlements/entitlements_provider.dart';
import 'extension_actions.dart';

/// Extension point of the POS checkout, filled after a completed sale
/// (declared by the `pos` manifest; Invoicing contributes « Facturer »).
const kPosCheckoutPostSaleActions = 'pos.checkout.post-sale-actions';

/// Order of a contribution sent without one: after every ordered one.
const kExtensionUnorderedLast = 1 << 30;

/// Story 15.3 — one contribution of an active module to an extension point,
/// as sent by `GET /api/v1/tenants/me/extensions/{pointId}`.
///
/// The server is the single source of the label, the order and the
/// permission filter; the app only maps [action] to a handler
/// ([extensionActionsProvider]). [id] and [action] are never shown.
@immutable
class ExtensionContribution {
  final String id;

  /// French label shown on the button.
  final String label;

  /// Position within the slot (ascending; [kExtensionUnorderedLast] when
  /// the server sent none).
  final int order;

  /// Client routing key of the action (e.g. `invoicing.open-sale-invoice`).
  final String action;

  const ExtensionContribution({
    required this.id,
    required this.label,
    required this.order,
    required this.action,
  });

  /// Null for an entry missing its id, label or action.
  static ExtensionContribution? fromJson(Object? json) {
    if (json is! Map) return null;
    final id = json['id'];
    final label = json['label'];
    final action = json['action'];
    final order = json['order'];
    if (id is! String || label is! String || action is! String) return null;
    if (id.trim().isEmpty || label.trim().isEmpty || action.trim().isEmpty) {
      return null;
    }
    return ExtensionContribution(
      id: id,
      label: label.trim(),
      order: order is num ? order.toInt() : kExtensionUnorderedLast,
      action: action,
    );
  }

  /// The `data` list of the endpoint, ordered by [order] then [id] (stable
  /// on ties); a repeated id keeps its first entry. Throws [FormatException]
  /// when it is not a list.
  static List<ExtensionContribution> parseList(Object? data) {
    if (data is! List) {
      throw const FormatException('Réponse du point d\'extension illisible');
    }
    final byId = <String, ExtensionContribution>{};
    for (final item in data) {
      final c = ExtensionContribution.fromJson(item);
      if (c != null) byId.putIfAbsent(c.id, () => c);
    }
    final list = byId.values.toList()
      ..sort((a, b) {
        final byOrder = a.order.compareTo(b.order);
        return byOrder != 0 ? byOrder : a.id.compareTo(b.id);
      });
    return List.unmodifiable(list);
  }

  @override
  bool operator ==(Object other) =>
      other is ExtensionContribution &&
      other.id == id &&
      other.label == label &&
      other.order == order &&
      other.action == action;

  @override
  int get hashCode => Object.hash(id, label, order, action);

  @override
  String toString() => 'ExtensionContribution($id, $order)';
}

/// Fetches the contributions of one extension point for the caller.
typedef ExtensionContributionsFetcher = Future<List<ExtensionContribution>>
    Function(String pointId);

/// `GET /api/v1/tenants/me/extensions/{pointId}` (overridden in tests).
final extensionContributionsFetcherProvider =
    Provider<ExtensionContributionsFetcher>((ref) {
  return (pointId) async {
    final resp = await ref.read(dioProvider).get<Map<String, dynamic>>(
        '/api/v1/tenants/me/extensions/${Uri.encodeComponent(pointId)}');
    return ExtensionContribution.parseList(resp.data?['data']);
  };
});

/// What the slot cache depends on in [Entitlements]: known-ness and every
/// module's state (an ENABLED → READ_ONLY change drops contributions the
/// server lists only for ENABLED modules) — not the fetch date nor the source.
@visibleForTesting
String extensionCacheKey(Entitlements? entitlements) {
  final e = entitlements ?? Entitlements.unknown;
  if (!e.isKnown) return '?';
  return (e.states.entries.map((m) => '${m.key}=${m.value}').toList()..sort())
      .join(',');
}

/// Story 15.3 — contributions of an extension point, cached for the session.
///
/// A success is kept for the session (one request per point); the cache is
/// rebuilt when a module state of [entitlementsProvider] changes, and
/// dropped on sign-out / account switch (invalidated by `SignOutService` and
/// `SessionClaimsApplier`). A failure (offline, 404 of an unknown point on an
/// older backend, 5xx) is not cached: the slot shows nothing while it stays
/// on screen, and its next render (or the next read) asks again.
final extensionContributionsProvider = FutureProvider.autoDispose
    .family<List<ExtensionContribution>, String>((ref, pointId) async {
  // Kept for the session once it succeeded; released on failure.
  final keep = ref.keepAlive();
  ref.watch(entitlementsProvider
      .select((value) => extensionCacheKey(value.value)));
  final fetch = ref.watch(extensionContributionsFetcherProvider);
  if (!ref.read(entitlementsProvider).hasValue) {
    // Module states still loading (app start): their first value changes
    // the key and rebuilds this provider — ask once, after it.
    var replaced = false;
    ref.onDispose(() => replaced = true);
    try {
      await ref.read(entitlementsProvider.future);
    } catch (_) {
      // Unreadable states: the backend still filters the contributions.
    }
    if (replaced) return const [];
  }
  try {
    return await fetch(pointId);
  } catch (_) {
    keep.close();
    rethrow;
  }
});

/// The contributions this app can run (action key registered), in order.
List<ExtensionContribution> renderableContributions(
  List<ExtensionContribution> contributions,
  Map<String, ExtensionActionHandler> actions,
) =>
    [
      for (final c in contributions)
        if (actions.containsKey(c.action)) c,
    ];

/// Contributions [ExtensionSlot] shows for [pointId] right now: none while
/// loading or after a failure. Rebuilds the caller when they change.
List<ExtensionContribution> visibleExtensionContributions(
    WidgetRef ref, String pointId) {
  final contributions =
      ref.watch(extensionContributionsProvider(pointId)).value;
  if (contributions == null) return const [];
  return renderableContributions(
      contributions, ref.watch(extensionActionsProvider));
}

/// Story 15.3 — a UI slot other modules fill (UX-DR3): one button per
/// contribution of the active modules to [pointId], ordered by the server's
/// `order`. Nothing at all while loading, on failure, when no module
/// contributes, or for an action this app version does not know.
class ExtensionSlot extends ConsumerStatefulWidget {
  final String pointId;

  /// Passed to the action handler (e.g. `{'saleId': …}`).
  final Map<String, Object?> args;

  /// Style of every button (e.g. light buttons on a coloured page).
  final ButtonStyle? style;

  const ExtensionSlot({
    super.key,
    required this.pointId,
    this.args = const {},
    this.style,
  });

  @override
  ConsumerState<ExtensionSlot> createState() => _ExtensionSlotState();
}

class _ExtensionSlotState extends ConsumerState<ExtensionSlot> {
  /// An action is running: a second tap is ignored (no stacked sheets).
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final contributions = visibleExtensionContributions(ref, widget.pointId);
    if (contributions.isEmpty) return const SizedBox.shrink();
    final actions = ref.watch(extensionActionsProvider);
    return Wrap(
      alignment: WrapAlignment.center,
      spacing: 12,
      runSpacing: 8,
      children: [
        for (final c in contributions)
          OutlinedButton(
            key: ValueKey('extension-${c.id}'),
            style: widget.style,
            onPressed: () => _run(actions[c.action]!),
            child: Text(c.label),
          ),
      ],
    );
  }

  Future<void> _run(ExtensionActionHandler handler) async {
    if (_busy) return;
    _busy = true;
    try {
      await handler(context, ref, widget.args);
    } catch (e) {
      // The handlers show their own errors; what is left (e.g. the module
      // code failing to load) gets the French generic feedback.
      debugPrint('[ExtensionSlot] ${widget.pointId} action failed: $e');
      if (mounted) {
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(
            content: Text(e is DeferredLoadException
                ? kDeferredLoadFailedMessage
                : appErrorMessage(e))));
      }
    } finally {
      _busy = false;
    }
  }
}
