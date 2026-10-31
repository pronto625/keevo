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

/// Stored subscription status values sent with the module states (Story 15.6).
const kSubscriptionTrial = 'TRIAL';
const kSubscriptionActive = 'ACTIVE';
const kSubscriptionExpired = 'EXPIRED';
const kSubscriptionDisabled = 'DISABLED';
const kSubscriptionAvailable = 'AVAILABLE';

/// Story 15.6 — what the server says about one module beyond its state: the
/// stored subscription [status] (null: never subscribed), whether it is part of
/// the free offer, its manifest [category] and the subscription dates (owner
/// only; null for an employee or when there is none). Immutable.
@immutable
class ModuleDetails {
  /// `TRIAL` | `ACTIVE` | `EXPIRED` | `DISABLED` | `AVAILABLE`, null when the
  /// tenant never had a subscription row for it.
  final String? status;
  final bool free;

  /// `core` | `business` | `premium` (null when not sent).
  final String? category;
  final DateTime? expiresAt;
  final DateTime? graceUntil;

  const ModuleDetails({
    this.status,
    this.free = false,
    this.category,
    this.expiresAt,
    this.graceUntil,
  });

  /// Part of the offer at no cost: free in the manifest, or the core.
  bool get isIncluded => free || category == 'core';

  /// The tenant had (or has) a subscription for it.
  bool get wasSubscribed => status != null && status != kSubscriptionAvailable;

  Map<String, dynamic> toJson() => {
        if (status != null) 'status': status,
        'free': free,
        if (category != null) 'category': category,
        if (expiresAt != null)
          'expiresAt': expiresAt!.toUtc().toIso8601String(),
        if (graceUntil != null)
          'graceUntil': graceUntil!.toUtc().toIso8601String(),
      };

  /// From a snapshot entry or an API item; null when [json] is not a map.
  static ModuleDetails? fromJson(Object? json) {
    if (json is! Map) return null;
    final status = json['status'];
    final free = json['free'];
    final category = json['category'];
    return ModuleDetails(
      status: status is String && status.isNotEmpty ? status : null,
      free: free == true,
      category: category is String && category.isNotEmpty ? category : null,
      expiresAt: _date(json['expiresAt']),
      graceUntil: _date(json['graceUntil']),
    );
  }

  static DateTime? _date(Object? raw) =>
      raw is String ? DateTime.tryParse(raw)?.toUtc() : null;

  @override
  bool operator ==(Object other) =>
      other is ModuleDetails &&
      other.status == status &&
      other.free == free &&
      other.category == category &&
      other.expiresAt == expiresAt &&
      other.graceUntil == graceUntil;

  @override
  int get hashCode =>
      Object.hash(status, free, category, expiresAt, graceUntil);

  @override
  String toString() => 'ModuleDetails($status, free: $free, $category)';
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

  /// Story 15.6 — module id → subscription details (server answer, persisted
  /// with the snapshot). Empty for a claim seed or an older snapshot.
  final Map<String, ModuleDetails> details;

  /// When the server answered (server / cached), or when the token carrying
  /// the claim was issued (claim). Null when unknown.
  final DateTime? fetchedAt;

  const Entitlements._(this.states, this.status, this.fetchedAt,
      this.displayNames, this.details);

  Entitlements({
    required Map<String, String> states,
    required this.status,
    this.fetchedAt,
    Map<String, String> displayNames = const {},
    Map<String, ModuleDetails> details = const {},
  })  : states = Map.unmodifiable(states),
        displayNames = Map.unmodifiable(displayNames),
        details = Map.unmodifiable(details);

  /// Nothing known yet.
  static const unknown = Entitlements._(<String, String>{},
      EntitlementsStatus.unknown, null, <String, String>{}, <String, ModuleDetails>{});

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
      displayNames: displayNames,
      details: details);

  /// Snapshot form (states + fetch date; the status is not stored: a snapshot
  /// read back is always [EntitlementsStatus.cached]).
  Map<String, dynamic> toJson() => {
        'states': states,
        if (displayNames.isNotEmpty) 'displayNames': displayNames,
        if (details.isNotEmpty)
          'details': {
            for (final e in details.entries) e.key: e.value.toJson(),
          },
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
    final details = <String, ModuleDetails>{};
    final rawDetails = json['details'];
    if (rawDetails is Map) {
      for (final entry in rawDetails.entries) {
        final parsed = ModuleDetails.fromJson(entry.value);
        if (entry.key is String && parsed != null) {
          details[entry.key as String] = parsed;
        }
      }
    }
    final at = json['fetchedAt'];
    return Entitlements(
      states: states,
      status: EntitlementsStatus.cached,
      fetchedAt: at is String ? DateTime.tryParse(at) : null,
      displayNames: names,
      details: details,
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

  /// Story 15.6 — module id → [ModuleDetails] of the same `data` list
  /// (`status`, `free`, `category`, `expiresAt`, `graceUntil`). Call after
  /// [parseApiStates].
  static Map<String, ModuleDetails> parseApiDetails(Object? data) {
    final details = <String, ModuleDetails>{};
    if (data is! List) return details;
    for (final item in data) {
      if (item is Map) {
        final id = item['moduleId'];
        final parsed = ModuleDetails.fromJson(item);
        if (id is String && parsed != null) details[id] = parsed;
      }
    }
    return details;
  }

  /// [details] of [moduleId], null when the server never sent them.
  ModuleDetails? detailsOf(String moduleId) => details[moduleId];

  @override
  bool operator ==(Object other) =>
      other is Entitlements &&
      other.status == status &&
      other.fetchedAt == fetchedAt &&
      mapEquals(other.states, states) &&
      mapEquals(other.displayNames, displayNames) &&
      mapEquals(other.details, details);

  @override
  int get hashCode => Object.hash(
      status,
      fetchedAt,
      Object.hashAllUnordered(states.entries.map((e) => '${e.key}=${e.value}')),
      Object.hashAllUnordered(
          displayNames.entries.map((e) => '${e.key}=${e.value}')),
      Object.hashAllUnordered(
          details.entries.map((e) => Object.hash(e.key, e.value))));

  @override
  String toString() => 'Entitlements($status, $states)';
}
