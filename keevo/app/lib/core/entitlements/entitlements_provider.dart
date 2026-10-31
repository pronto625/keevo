import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/auth/jwt_claims.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/entitlements/entitlements.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/presentation/provider/auth_provider.dart';

/// One answer of `GET /api/v1/tenants/me/modules`.
typedef ModulesAnswer = ({
  Map<String, String> states,
  Map<String, String> displayNames,
});

/// Story 15.1 — the tenant's module states, for every session role.
///
/// The value is available at once from what the device knows — the access
/// token's `modules` claim (Epic 16.3) or the last server answer persisted for
/// this account ([kEntitlementsSnapshotKey]) — then replaced by the answer of
/// `GET /api/v1/tenants/me/modules` (the backend is the source of truth),
/// which is persisted in turn. A failed request (offline, server error) keeps
/// the known states and is not cached: the next token / [refresh] /
/// [EntitlementsNotifier.ensureServerState] asks again.
///
/// Rebuilt after every token ([SessionClaimsApplier]): a renewed token is how
/// a module disabled server-side disappears from the app.
final entitlementsProvider =
    AsyncNotifierProvider<EntitlementsNotifier, Entitlements>(
        EntitlementsNotifier.new);

class EntitlementsNotifier extends AsyncNotifier<Entitlements> {
  /// Bumped on every build and dispose: an answer requested by an older build
  /// (previous token / account) is dropped.
  int _generation = 0;

  /// Bumped on every request: only the latest started request may apply its
  /// answer (an older one finishing last never overwrites a newer one).
  int _requestSeq = 0;
  Future<Entitlements>? _inFlight;

  @override
  Future<Entitlements> build() async {
    final generation = ++_generation;
    _inFlight = null;
    ref.onDispose(() => _generation++);
    final seed = await loadSeed();
    // After the seed is out (event queue, not microtask): confirm with the
    // server. Failures keep the seed. Signed out: nothing to ask (a 401
    // would end a session that is already over).
    if (await hasSession()) {
      Future(() {
        // Already asked by ensureServerState / refresh meanwhile: no 2nd call.
        if (generation == _generation &&
            _inFlight == null &&
            state.valueOrNull?.status != EntitlementsStatus.server) {
          _startFetch(generation).ignore();
        }
      });
    }
    return seed;
  }

  /// The states confirmed by the server in this session: the cached answer,
  /// the request in flight, or a new request. Throws when the server cannot be
  /// reached (nothing cached: the next call asks again). Signed out: the
  /// current state, nothing asked.
  Future<Entitlements> ensureServerState() async {
    final current = await future;
    if (current.status == EntitlementsStatus.server) return current;
    if (!await hasSession()) return current;
    return _inFlight ?? _startFetch(_generation);
  }

  /// Asks the server again (after a purchase / plan change), or joins the
  /// request already running. The known states stay when it fails. Signed
  /// out: nothing asked.
  Future<void> refresh() async {
    try {
      await future;
      if (!await hasSession()) return;
      await (_inFlight ?? _startFetch(_generation));
    } catch (e) {
      debugPrint('[Entitlements] refresh failed: $e');
    }
  }

  Future<Entitlements> _startFetch(int generation) {
    final fetch = _fetch(generation, ++_requestSeq);
    _inFlight = fetch;
    fetch.whenComplete(() {
      if (identical(_inFlight, fetch)) _inFlight = null;
    }).ignore();
    return fetch;
  }

  Future<Entitlements> _fetch(int generation, int seq) async {
    final answer = await fetchModules();
    final fresh = Entitlements(
      states: answer.states,
      status: EntitlementsStatus.server,
      fetchedAt: DateTime.now(),
      displayNames: answer.displayNames,
    );
    // Another token / account since the request: its own build fetches.
    // A newer request was started: its answer wins.
    if (generation != _generation || seq != _requestSeq) return fresh;
    try {
      await onServerAnswer(fresh);
    } catch (e) {
      // Not persisted: the server answer still applies to this session.
      debugPrint('[Entitlements] persisting the answer failed: $e');
    }
    if (generation == _generation && seq == _requestSeq) {
      state = AsyncData(fresh);
    }
    return fresh;
  }

  /// What the device knows before the server answers: the token claim, or
  /// the snapshot of this account when it is newer than the token.
  @protected
  Future<Entitlements> loadSeed() async {
    final prefs = ref.read(sharedPreferencesProvider);
    final owner = prefs.getString(kLocalDataOwnerKey);
    final snapshot = owner == null ? null : readSnapshot(prefs, owner);
    JwtClaims? claims;
    try {
      claims =
          JwtClaims.tryParse(await ref.read(tokenStorageProvider).getToken());
    } catch (e) {
      debugPrint('[Entitlements] token read failed: $e');
    }
    final claimed = claims?.modules;
    if (claimed != null) {
      final issuedAt = claims!.issuedAt;
      final snapshotAt = snapshot?.fetchedAt;
      final snapshotIsNewer = snapshotAt != null &&
          issuedAt != null &&
          snapshotAt.isAfter(issuedAt);
      if (!snapshotIsNewer) {
        return Entitlements.fromClaim(claimed, issuedAt: issuedAt);
      }
    }
    return snapshot ?? Entitlements.unknown;
  }

  /// An access token is stored (signed in).
  @protected
  Future<bool> hasSession() async {
    try {
      return await ref.read(tokenStorageProvider).getToken() != null;
    } catch (_) {
      return false;
    }
  }

  /// `GET /api/v1/tenants/me/modules` → module id → state, and module id →
  /// French display name (Story 15.2, « module non activé » screen).
  @protected
  Future<ModulesAnswer> fetchModules() async {
    final resp = await ref
        .read(dioProvider)
        .get<Map<String, dynamic>>('/api/v1/tenants/me/modules');
    final data = resp.data?['data'];
    return (
      states: Entitlements.parseApiStates(data),
      displayNames: Entitlements.parseApiDisplayNames(data),
    );
  }

  /// Persists the answer for this account and, when needed, asks the sync for
  /// one full pull ([kFullPullRequestedKey], consumed by the next pull): a
  /// module that became readable again (re-enabled, or newly subscribed)
  /// since the previous snapshot of the same account — the delta pull skipped
  /// it while it was DISABLED but advanced the cursor — or no previous
  /// snapshot while a pull cursor exists (upgrade from 2.0.7: no baseline).
  /// Local pending operations are untouched.
  @protected
  Future<void> onServerAnswer(Entitlements fresh) async {
    final prefs = ref.read(sharedPreferencesProvider);
    final owner = prefs.getString(kLocalDataOwnerKey);
    if (owner == null) return;
    final previous = readSnapshot(prefs, owner);
    final bool fullPull;
    if (previous == null) {
      // Throws on an unreadable storage: nothing persisted, retried next answer.
      fullPull = await ref
              .read(flutterSecureStorageProvider)
              .read(key: kLastSyncTimestampKey) !=
          null;
    } else {
      fullPull =
          fresh.readableModules.difference(previous.readableModules).isNotEmpty;
    }
    if (fullPull) {
      await prefs.setInt(
          kFullPullRequestedKey, DateTime.now().microsecondsSinceEpoch);
    }
    await prefs.setString(kEntitlementsSnapshotKey,
        jsonEncode({'owner': owner, ...fresh.toJson()}));
  }

  /// The persisted snapshot of [owner], null when none (or another account's).
  static Entitlements? readSnapshot(SharedPreferences prefs, String owner) {
    final raw = prefs.getString(kEntitlementsSnapshotKey);
    if (raw == null) return null;
    try {
      final json = jsonDecode(raw);
      if (json is! Map || json['owner'] != owner) return null;
      return Entitlements.fromJson(json);
    } catch (_) {
      return null;
    }
  }
}
