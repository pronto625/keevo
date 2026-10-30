import 'package:flutter/foundation.dart';

/// Module state values sent by `GET /api/v1/tenants/me/modules`.
const kModuleEnabled = 'ENABLED';

/// Subscription ended, 7-day grace running: reads pass, writes are refused.
const kModuleReadOnly = 'READ_ONLY';
const kModuleDisabled = 'DISABLED';

/// Module id of the multi-store stock overview and transfers.
const kMultiStoreModuleId = 'multi-store';

/// Where the current [Entitlements] come from.
enum EntitlementsStatus {
  /// Nothing known yet (first launch offline, no `modules` claim): callers keep
  /// their free / default path instead of treating the tenant as having no
  /// module at all.
  unknown,

  /// Seeded from the access token's `modules` claim (Epic 16.3), not yet
  /// confirmed by the server.
  claim,

  /// Last server answer persisted on the device (restart, offline).
  cached,

  /// Answer of `GET /api/v1/tenants/me/modules` received in this session.
  server,
}

/// Story 15.1 — the tenant's module states (module id → `ENABLED` |
/// `READ_ONLY` | `DISABLED`). Immutable.
@immutable
class Entitlements {
  final Map<String, String> states;
  final EntitlementsStatus status;

  /// When the server answered (server / cached), or when the token carrying
  /// the claim was issued (claim). Null when unknown.
  final DateTime? fetchedAt;

  const Entitlements._(this.states, this.status, this.fetchedAt);

  Entitlements({
    required Map<String, String> states,
    required this.status,
    this.fetchedAt,
  }) : states = Map.unmodifiable(states);

  /// Nothing known yet.
  static const unknown =
      Entitlements._(<String, String>{}, EntitlementsStatus.unknown, null);

  /// From the token's `modules` claim: the listed modules are usable.
  factory Entitlements.fromClaim(List<String> modules, {DateTime? issuedAt}) =>
      Entitlements(
        states: {for (final id in modules) id: kModuleEnabled},
        status: EntitlementsStatus.claim,
        fetchedAt: issuedAt,
      );

  bool get isKnown => status != EntitlementsStatus.unknown;

  /// Fully usable, writes included.
  bool isActive(String moduleId) => states[moduleId] == kModuleEnabled;

  /// Its screens can be read: ENABLED, or READ_ONLY during the grace period
  /// (reads still pass the server gate).
  bool isReadable(String moduleId) {
    final state = states[moduleId];
    return state == kModuleEnabled || state == kModuleReadOnly;
  }

  /// Ids of the readable modules.
  Set<String> get readableModules => {
        for (final id in states.keys)
          if (isReadable(id)) id
      };

  Entitlements withStatus(EntitlementsStatus status) =>
      Entitlements(states: states, status: status, fetchedAt: fetchedAt);

  /// Snapshot form (states + fetch date; the status is not stored: a snapshot
  /// read back is always [EntitlementsStatus.cached]).
  Map<String, dynamic> toJson() => {
        'states': states,
        if (fetchedAt != null)
          'fetchedAt': fetchedAt!.toUtc().toIso8601String(),
      };

  /// Null for anything that is not a snapshot written by [toJson].
  static Entitlements? fromJson(Object? json) {
    if (json is! Map) return null;
    final raw = json['states'];
    if (raw is! Map) return null;
    final states = <String, String>{};
    for (final entry in raw.entries) {
      if (entry.key is String && entry.value is String) {
        states[entry.key as String] = entry.value as String;
      }
    }
    final at = json['fetchedAt'];
    return Entitlements(
      states: states,
      status: EntitlementsStatus.cached,
      fetchedAt: at is String ? DateTime.tryParse(at) : null,
    );
  }

  /// Parses the `data` list of `GET /api/v1/tenants/me/modules`.
  ///
  /// Throws [FormatException] when it is not a list: a malformed answer must
  /// not replace the known states with an empty map.
  static Map<String, String> parseApiStates(Object? data) {
    if (data is! List) {
      throw const FormatException('Réponse des modules illisible');
    }
    final states = <String, String>{};
    for (final item in data) {
      if (item is Map) {
        final id = item['moduleId'];
        final state = item['state'];
        if (id is String && state is String) states[id] = state;
      }
    }
    return states;
  }

  @override
  bool operator ==(Object other) =>
      other is Entitlements &&
      other.status == status &&
      other.fetchedAt == fetchedAt &&
      mapEquals(other.states, states);

  @override
  int get hashCode => Object.hash(
      status,
      fetchedAt,
      Object.hashAllUnordered(
          states.entries.map((e) => '${e.key}=${e.value}')));

  @override
  String toString() => 'Entitlements($status, $states)';
}
