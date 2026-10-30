import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/auth/jwt_claims.dart';
import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/entitlements/entitlements.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo/features/inventory/presentation/page/local_low_stock_page.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../helpers/fake_entitlements.dart';

// Story 15.1 — entitlementsProvider: one row per I/O matrix scenario.

const _owner = 'u1@kv_a';
const _cursor = '1700000000000';

/// Answers GET /api/v1/tenants/me/modules with [states] (null → offline).
class _Adapter implements HttpClientAdapter {
  Map<String, String>? states;
  Completer<void>? gate;
  final List<String> paths = [];

  _Adapter(this.states);

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    paths.add(options.path);
    final g = gate;
    if (g != null) await g.future;
    final s = states;
    if (s == null) {
      throw DioException.connectionError(
          requestOptions: options, reason: 'hors ligne');
    }
    return ResponseBody.fromString(
        jsonEncode({
          'data': [
            for (final e in s.entries)
              {
                'moduleId': e.key,
                'displayName': 'Module',
                'state': e.value,
                'expiresAt': null,
                'graceUntil': null,
              }
          ]
        }),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType]
        });
  }

  @override
  void close({bool force = false}) {}
}

String _jwt(Map<String, dynamic> claims) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
  return '${part({'alg': 'RS256'})}.'
      '${part({'exp': now + 3600, 'iat': now, ...claims})}.sig';
}

String _token({String role = 'OWNER', List<String>? modules}) => _jwt({
      'sub': 'u1',
      'tenantId': 'kv_a',
      'role': role,
      if (modules != null) 'modules': modules,
    });

String _snapshot(Map<String, String> states,
        {String owner = _owner, DateTime? at}) =>
    jsonEncode({
      'owner': owner,
      'states': states,
      'fetchedAt': (at ?? DateTime.now()).toUtc().toIso8601String(),
    });

class _FailingPersist extends FakeEntitlementsNotifier {
  _FailingPersist(super.fetch);

  @override
  Future<void> onServerAnswer(Entitlements fresh) async =>
      throw StateError('stockage plein');
}

void main() {
  late _Adapter adapter;
  late SharedPreferences prefs;

  Future<ProviderContainer> setUpContainer({
    Map<String, String>? server,
    String? token,
    String? snapshot,
    String? owner = _owner,
    bool withCursor = true,
  }) async {
    SharedPreferences.setMockInitialValues({
      if (owner != null) kLocalDataOwnerKey: owner,
      if (snapshot != null) kEntitlementsSnapshotKey: snapshot,
    });
    FlutterSecureStorage.setMockInitialValues({
      if (withCursor) kLastSyncTimestampKey: _cursor,
      if (token != null) 'jwt_token': token,
    });
    prefs = await SharedPreferences.getInstance();
    adapter = _Adapter(server);
    final dio = Dio(BaseOptions(baseUrl: 'https://keevo.test'))
      ..httpClientAdapter = adapter;
    final c = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      dioProvider.overrideWithValue(dio),
    ]);
    addTearDown(c.dispose);
    return c;
  }

  /// Lets the background confirmation run to its end.
  Future<void> settle() =>
      Future<void>.delayed(const Duration(milliseconds: 100));

  Future<String?> cursor() =>
      const FlutterSecureStorage().read(key: kLastSyncTimestampKey);

  /// The « full pull requested » marker read by the next sync pull.
  int? marker() => prefs.getInt(kFullPullRequestedKey);

  Entitlements? persisted() => EntitlementsNotifier.readSnapshot(prefs, _owner);

  test('login online: API states exposed, snapshot persisted', () async {
    final c = await setUpContainer(
      token: _token(),
      server: {'invoicing': 'ENABLED', 'multi-store': 'DISABLED'},
    );

    final seed = await c.read(entitlementsProvider.future);
    expect(seed.status, EntitlementsStatus.unknown);
    await settle();

    final e = c.read(entitlementsProvider).requireValue;
    expect(e.status, EntitlementsStatus.server);
    expect(e.isActive('invoicing'), isTrue);
    expect(e.isReadable('multi-store'), isFalse);
    expect(adapter.paths, ['/api/v1/tenants/me/modules']);
    expect(persisted()!.states,
        {'invoicing': 'ENABLED', 'multi-store': 'DISABLED'});
    expect(persisted()!.status, EntitlementsStatus.cached);
    // No baseline yet but a cursor exists (upgrade from 2.0.7): one full pull.
    expect(marker(), isNotNull);
    expect(await cursor(), _cursor, reason: 'the sync consumes the marker');
  });

  test('claim present: seeded from the claim, then replaced by the API',
      () async {
    final c = await setUpContainer(
      token: _token(modules: ['invoicing']),
      server: {'invoicing': 'READ_ONLY', 'pos': 'ENABLED'},
    );
    adapter.gate = Completer<void>();

    final seed = await c.read(entitlementsProvider.future);
    expect(seed.status, EntitlementsStatus.claim);
    expect(seed.isActive('invoicing'), isTrue);
    expect(seed.isReadable('pos'), isFalse);

    adapter.gate!.complete();
    await settle();
    final e = c.read(entitlementsProvider).requireValue;
    expect(e.status, EntitlementsStatus.server);
    expect(e.isActive('invoicing'), isFalse, reason: 'backend wins');
    expect(e.isReadable('invoicing'), isTrue, reason: 'READ_ONLY = readable');
    expect(e.isActive('pos'), isTrue);
  });

  test('restart offline: the persisted snapshot, failure not cached', () async {
    final c = await setUpContainer(
      token: _token(),
      snapshot: _snapshot({'multi-store': 'ENABLED'}),
    );

    final e = await c.read(entitlementsProvider.future);
    await settle();

    expect(c.read(entitlementsProvider).requireValue, e);
    expect(e.status, EntitlementsStatus.cached);
    expect(e.isReadable('multi-store'), isTrue);
    expect(persisted()!.states, {'multi-store': 'ENABLED'}, reason: 'kept');

    // Back online: the next refresh asks again.
    adapter.states = {'multi-store': 'DISABLED'};
    await c.read(entitlementsProvider.notifier).refresh();
    expect(c.read(entitlementsProvider).requireValue.isReadable('multi-store'),
        isFalse);
    expect(adapter.paths, hasLength(2));
  });

  test("another account's snapshot is never read back", () async {
    final c = await setUpContainer(
      token: _token(),
      snapshot: _snapshot({'multi-store': 'ENABLED'}, owner: 'u9@kv_z'),
    );

    final e = await c.read(entitlementsProvider.future);
    expect(e.status, EntitlementsStatus.unknown);
  });

  test('a snapshot newer than the token wins over its claim', () async {
    final c = await setUpContainer(
      token: _token(modules: ['invoicing']),
      snapshot: _snapshot({'invoicing': 'DISABLED'},
          at: DateTime.now().add(const Duration(minutes: 5))),
    );

    final e = await c.read(entitlementsProvider.future);
    expect(e.status, EntitlementsStatus.cached);
    expect(e.isReadable('invoicing'), isFalse);
  });

  test('first launch offline: unknown, nothing readable, retried later',
      () async {
    final c = await setUpContainer(token: _token());

    final e = await c.read(entitlementsProvider.future);
    await settle();

    expect(c.read(entitlementsProvider).requireValue.status,
        EntitlementsStatus.unknown);
    expect(e.isKnown, isFalse);
    expect(e.isReadable('pos'), isFalse);
    expect(persisted(), isNull);
    await expectLater(c.read(entitlementsProvider.notifier).ensureServerState(),
        throwsA(isA<DioException>()));
    expect(adapter.paths, hasLength(2), reason: 'each call asks again');
  });

  test('module disabled server-side: no longer readable after the new token',
      () async {
    final c = await setUpContainer(
        token: _token(), server: {'multi-store': 'ENABLED'});
    await c.read(entitlementsProvider.future);
    await settle();
    expect(c.read(entitlementsProvider).requireValue.isReadable('multi-store'),
        isTrue);
    await prefs.remove(kFullPullRequestedKey); // consumed by a sync

    // Token renewed (SessionClaimsApplier invalidates) → server says DISABLED.
    adapter.states = {'multi-store': 'DISABLED'};
    c.invalidate(entitlementsProvider);
    final reseeded = await c.read(entitlementsProvider.future);
    expect(reseeded.status, EntitlementsStatus.cached);
    await settle();

    expect(c.read(entitlementsProvider).requireValue.isReadable('multi-store'),
        isFalse);
    expect(marker(), isNull, reason: 'disabling never asks for a full pull');
  });

  group('module re-enabled → full pull requested', () {
    for (final now in ['ENABLED', 'READ_ONLY']) {
      test('previously DISABLED, now $now', () async {
        final c = await setUpContainer(
          token: _token(),
          snapshot: _snapshot({'counting': 'DISABLED', 'pos': 'ENABLED'}),
          server: {'counting': now, 'pos': 'ENABLED'},
        );
        await c.read(entitlementsProvider.future);
        await settle();

        expect(marker(), isNotNull);
        expect(await cursor(), _cursor, reason: 'never deleted directly');
        expect(persisted()!.states['counting'], now);
      });
    }

    test('previously absent, now ENABLED', () async {
      final c = await setUpContainer(
        token: _token(),
        snapshot: _snapshot({'pos': 'ENABLED'}),
        server: {'counting': 'ENABLED', 'pos': 'ENABLED'},
      );
      await c.read(entitlementsProvider.future);
      await settle();

      expect(marker(), isNotNull);
    });

    test('nothing newly readable → cursor kept', () async {
      final c = await setUpContainer(
        token: _token(),
        snapshot: _snapshot({'counting': 'READ_ONLY'}),
        server: {'counting': 'ENABLED'},
      );
      await c.read(entitlementsProvider.future);
      await settle();

      expect(marker(), isNull);
    });

    test('no previous snapshot and no cursor (fresh install) → nothing',
        () async {
      final c = await setUpContainer(
          token: _token(), server: {'pos': 'ENABLED'}, withCursor: false);
      await c.read(entitlementsProvider.future);
      await settle();

      expect(marker(), isNull);
      expect(persisted(), isNotNull);
    });

    test('no previous snapshot + cursor → raised once only', () async {
      final c =
          await setUpContainer(token: _token(), server: {'pos': 'ENABLED'});
      await c.read(entitlementsProvider.future);
      await settle();
      expect(marker(), isNotNull);
      await prefs.remove(kFullPullRequestedKey); // consumed by a sync

      await c.read(entitlementsProvider.notifier).refresh();
      expect(marker(), isNull, reason: 'the snapshot is the baseline now');
    });
  });

  test('employee session: the tenant states (not empty)', () async {
    final c = await setUpContainer(
      token: _token(role: 'EMPLOYEE'),
      server: {'pos': 'ENABLED', 'crm': 'READ_ONLY'},
    );
    await c.read(entitlementsProvider.future);
    await settle();

    final e = c.read(entitlementsProvider).requireValue;
    expect(e.states, {'pos': 'ENABLED', 'crm': 'READ_ONLY'});
    expect(e.isReadable('crm'), isTrue);
    expect(e.isActive('crm'), isFalse);
  });

  test('signed out: no request (a 401 would end a session already over)',
      () async {
    final c = await setUpContainer(server: {'pos': 'ENABLED'});
    final e = await c.read(entitlementsProvider.future);
    await settle();

    expect(e.status, EntitlementsStatus.unknown);
    expect(adapter.paths, isEmpty);
  });

  test('signed out: ensureServerState / refresh send nothing', () async {
    final c = await setUpContainer(server: {'pos': 'ENABLED'});
    final notifier = c.read(entitlementsProvider.notifier);

    final e = await notifier.ensureServerState();
    await notifier.refresh();

    expect(e.status, EntitlementsStatus.unknown);
    expect(adapter.paths, isEmpty);
  });

  test('refresh joins the request already running (one GET)', () async {
    final c = await setUpContainer(token: _token(), server: {'pos': 'ENABLED'});
    adapter.gate = Completer<void>();
    final notifier = c.read(entitlementsProvider.notifier);
    final ensure = notifier.ensureServerState();
    await Future<void>.delayed(const Duration(milliseconds: 10));

    final refresh = notifier.refresh();
    adapter.gate!.complete();
    await Future.wait([ensure, refresh]);
    await settle();

    expect(adapter.paths, hasLength(1));
    expect(c.read(entitlementsProvider).requireValue.isActive('pos'), isTrue);
  });

  test('an answer older than the latest request is dropped', () async {
    final first = Completer<Map<String, String>>();
    final second = Completer<Map<String, String>>();
    var calls = 0;
    final c = ProviderContainer(overrides: [
      entitlementsProvider.overrideWith(() => FakeEntitlementsNotifier(
          () => ++calls == 1 ? first.future : second.future)),
    ]);
    addTearDown(c.dispose);
    final notifier = c.read(entitlementsProvider.notifier);
    final older = notifier.ensureServerState();
    await Future<void>.delayed(const Duration(milliseconds: 10));
    // New token: a new request supersedes the running one.
    c.invalidate(entitlementsProvider);
    final newer = c.read(entitlementsProvider.notifier).ensureServerState();
    await Future<void>.delayed(const Duration(milliseconds: 10));

    second.complete({kMultiStoreModuleId: 'DISABLED'});
    await newer;
    first.complete({kMultiStoreModuleId: 'ENABLED'});
    await older;
    await settle();

    expect(
        c
            .read(entitlementsProvider)
            .requireValue
            .isReadable(kMultiStoreModuleId),
        isFalse);
  });

  test('persisting fails → the server answer still applies', () async {
    final c = ProviderContainer(overrides: [
      entitlementsProvider
          .overrideWith(() => _FailingPersist(() async => {'pos': 'ENABLED'})),
    ]);
    addTearDown(c.dispose);

    final e = await c.read(entitlementsProvider.notifier).ensureServerState();

    expect(e.isActive('pos'), isTrue);
    expect(c.read(entitlementsProvider).requireValue.status,
        EntitlementsStatus.server);
  });

  test('ensureServerState reuses the answer of this session', () async {
    final c = await setUpContainer(token: _token(), server: {'pos': 'ENABLED'});
    final notifier = c.read(entitlementsProvider.notifier);

    final first = await notifier.ensureServerState();
    final second = await notifier.ensureServerState();
    await settle();

    expect(first.status, EntitlementsStatus.server);
    expect(identical(first, second), isTrue);
    expect(adapter.paths, hasLength(1),
        reason: 'background confirmation joins');
  });

  group('isMultiStoreOverviewAvailable', () {
    test('error → false, not cached: the next call asks again', () async {
      final c = await setUpContainer(token: _token());
      expect(await isMultiStoreOverviewAvailable(c), isFalse);

      adapter.states = {'multi-store': 'ENABLED'};
      expect(await isMultiStoreOverviewAvailable(c), isTrue);
    });

    test('slow answer → false now, the answer is kept for the next call',
        () async {
      final c = await setUpContainer(
          token: _token(), server: {'multi-store': 'READ_ONLY'});
      adapter.gate = Completer<void>();

      expect(
          await isMultiStoreOverviewAvailable(c,
              timeout: const Duration(milliseconds: 10)),
          isFalse);
      adapter.gate!.complete();
      await settle();
      expect(await isMultiStoreOverviewAvailable(c), isTrue);
      expect(adapter.paths, hasLength(1));
    });

    test('a cached snapshot alone is not enough (offline → free list)',
        () async {
      final c = await setUpContainer(
        token: _token(),
        snapshot: _snapshot({'multi-store': 'ENABLED'}),
      );
      expect(await isMultiStoreOverviewAvailable(c), isFalse);
    });
  });

  group('Entitlements', () {
    test('snapshot JSON round trip', () {
      final e = Entitlements(
        states: const {'pos': 'ENABLED', 'crm': 'READ_ONLY'},
        status: EntitlementsStatus.server,
        fetchedAt: DateTime.utc(2026, 10, 30, 8),
      );
      final back = Entitlements.fromJson(jsonDecode(jsonEncode(e.toJson())));
      expect(back, e.withStatus(EntitlementsStatus.cached));
    });

    test('malformed API answer → FormatException (states kept)', () {
      expect(() => Entitlements.parseApiStates({'oops': 1}),
          throwsFormatException);
    });
  });

  group('JwtClaims.modules', () {
    test('list of strings → ids; absent or malformed → null', () {
      expect(JwtClaims.tryParse(_token(modules: ['pos', 'crm']))!.modules,
          ['pos', 'crm']);
      expect(JwtClaims.tryParse(_token())!.modules, isNull);
      expect(JwtClaims.tryParse(_jwt({'modules': 'pos'}))!.modules, isNull);
      expect(
          JwtClaims.tryParse(_jwt({
            'modules': [1, 'pos']
          }))!
              .modules,
          isNull);
    });
  });
}
