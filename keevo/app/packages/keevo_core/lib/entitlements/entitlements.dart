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

  /// Story 15.2 — module id → French display name (server answer, persisted
  /// with the snapshot). Empty for a claim seed or an older snapshot.
  final Map<String, String> displayNames;

  /// When the server answered (server / cached), or when the token carrying
  /// the claim was issued (claim). Null when unknown.
  final DateTime? fetchedAt;

  const Entitlements._(
      this.states, this.status, this.fetchedAt, this.displayNames);

  Entitlements({
    required Map<String, String> states,
    required this.status,
    this.fetchedAt,
    Map<String, String> displayNames = const {},
  })  : states = Map.unmodifiable(states),
        displayNames = Map.unmodifiable(displayNames);

  /// Nothing known yet.
  static const unknown = Entitlements._(
      <String, String>{}, EntitlementsStatus.unknown, null, <String, String>{});

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

  /// French name of [moduleId] as the server sent it, null when unknown.
  String? displayNameOf(String moduleId) {
    final name = displayNames[moduleId]?.trim();
    return name == null || name.isEmpty ? null : name;
  }

  Entitlements withStatus(EntitlementsStatus status) => Entitlements(
      states: states,
      status: status,
      fetchedAt: fetchedAt,
      displayNames: displayNames);

  /// Snapshot form (states + fetch date; the status is not stored: a snapshot
  /// read back is always [EntitlementsStatus.cached]).
  Map<String, dynamic> toJson() => {
        'states': states,
        if (displayNames.isNotEmpty) 'displayNames': displayNames,
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
    final names = <String, String>{};
    final rawNames = json['displayNames'];
    if (rawNames is Map) {
      for (final entry in rawNames.entries) {
        if (entry.key is String && entry.value is String) {
          names[entry.key as String] = entry.value as String;
        }
      }
    }
    final at = json['fetchedAt'];
    return Entitlements(
      states: states,
      status: EntitlementsStatus.cached,
      fetchedAt: at is String ? DateTime.tryParse(at) : null,
      displayNames: names,
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

  /// Story 15.2 — module id → `displayName` of the same `data` list (entries
  /// without a non-blank name skipped). Call after [parseApiStates].
  static Map<String, String> parseApiDisplayNames(Object? data) {
    final names = <String, String>{};
    if (data is! List) return names;
    for (final item in data) {
      if (item is Map) {
        final id = item['moduleId'];
        final name = item['displayName'];
        if (id is String && name is String && name.trim().isNotEmpty) {
          names[id] = name.trim();
        }
      }
    }
    return names;
  }

  @override
  bool operator ==(Object other) =>
      other is Entitlements &&
      other.status == status &&
      other.fetchedAt == fetchedAt &&
      mapEquals(other.states, states) &&
      mapEquals(other.displayNames, displayNames);

  @override
  int get hashCode => Object.hash(
      status,
      fetchedAt,
      Object.hashAllUnordered(states.entries.map((e) => '${e.key}=${e.value}')),
      Object.hashAllUnordered(
          displayNames.entries.map((e) => '${e.key}=${e.value}')));

  @override
  String toString() => 'Entitlements($status, $states)';
}
