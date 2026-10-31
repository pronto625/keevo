import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/router/session_presence_cache.dart';
import 'package:keevo/features/auth/data/repository/secure_token_storage.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart';
import 'package:keevo_core/storage/secure_storage_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockStorage extends Mock implements FlutterSecureStorage {}

String _jwt({required DateTime expiresAt}) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  final exp = expiresAt.millisecondsSinceEpoch ~/ 1000;
  return '${part({'alg': 'RS256'})}.${part({'exp': exp, 'role': 'OWNER'})}.sig';
}

/// v1s-15-10: the router guard does not read secure storage on every
/// navigation; it re-reads only after a session secret changed.
void main() {
  late _MockStorage storage;
  late Map<String, String> backing;
  late DateTime now;
  late SessionPresenceCache cache;

  int readsOf(String key) =>
      verify(() => storage.read(key: key)).callCount;

  setUp(() {
    storage = _MockStorage();
    backing = {};
    now = DateTime(2026, 10, 12, 12);
    when(() => storage.read(key: any(named: 'key')))
        .thenAnswer((i) async => backing[i.namedArguments[#key] as String]);
    when(() => storage.write(
          key: any(named: 'key'),
          value: any(named: 'value'),
        )).thenAnswer((i) async {
      backing[i.namedArguments[#key] as String] =
          i.namedArguments[#value] as String;
    });
    when(() => storage.delete(key: any(named: 'key'))).thenAnswer((i) async {
      backing.remove(i.namedArguments[#key] as String);
    });
    cache = SessionPresenceCache(storage, now: () => now);
  });

  test('repeated checks reuse the cached secrets', () async {
    backing['jwt_token'] = _jwt(expiresAt: now.add(const Duration(hours: 1)));

    expect(await cache.hasSession(), isTrue);
    expect(await cache.hasSession(), isTrue);
    expect(await cache.hasSession(), isTrue);

    expect(readsOf('jwt_token'), 1);
    expect(readsOf('refresh_token'), 1);
  });

  test('a token saved through SecureTokenStorage is seen by the next check',
      () async {
    expect(await cache.hasSession(), isFalse);

    await SecureTokenStorage(storage)
        .saveToken(_jwt(expiresAt: now.add(const Duration(hours: 1))));

    expect(await cache.hasSession(), isTrue);
  });

  test('a saved refresh token is seen by the next check', () async {
    backing['jwt_token'] = _jwt(expiresAt: now.subtract(const Duration(hours: 1)));
    expect(await cache.hasSession(), isFalse);

    await SecureTokenStorage(storage).saveRefreshToken('r1');

    expect(await cache.hasSession(), isTrue);
  });

  test('clearSessionSecrets is seen by the next check', () async {
    backing['jwt_token'] = _jwt(expiresAt: now.add(const Duration(hours: 1)));
    backing['refresh_token'] = 'r1';
    expect(await cache.hasSession(), isTrue);

    await clearSessionSecrets(storage);

    expect(await cache.hasSession(), isFalse);
  });

  test('expiry is evaluated on every check, without re-reading storage',
      () async {
    backing['jwt_token'] = _jwt(expiresAt: now.add(const Duration(minutes: 5)));
    expect(await cache.hasSession(), isTrue);

    now = now.add(const Duration(minutes: 10));
    expect(await cache.hasSession(), isFalse,
        reason: 'expired and no refresh token');
    expect(readsOf('jwt_token'), 1);
  });

  test('expired access token with a cached refresh token: still a session',
      () async {
    backing['jwt_token'] = _jwt(expiresAt: now.add(const Duration(minutes: 5)));
    backing['refresh_token'] = 'r1';
    expect(await cache.hasSession(), isTrue);

    now = now.add(const Duration(minutes: 10));
    expect(await cache.hasSession(), isTrue);
  });

  test('a storage error is not cached', () async {
    when(() => storage.read(key: 'jwt_token'))
        .thenThrow(Exception('keystore locked'));
    await expectLater(cache.hasSession(), throwsException);

    when(() => storage.read(key: 'jwt_token')).thenAnswer(
        (_) async => _jwt(expiresAt: now.add(const Duration(hours: 1))));
    expect(await cache.hasSession(), isTrue);
  });

  test('provider: one cache per container, built on the app storage', () {
    final container = ProviderContainer(overrides: [
      flutterSecureStorageProvider.overrideWithValue(storage),
    ]);
    addTearDown(container.dispose);

    expect(container.read(sessionPresenceCacheProvider),
        same(container.read(sessionPresenceCacheProvider)));
  });
}
