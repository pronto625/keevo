import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqlite3/open.dart';

import 'package:keevo/core/di/providers.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/core/storage/app_database.dart';
import 'package:keevo/features/auth/presentation/provider/session_claims_applier.dart';
import 'package:keevo/features/pos/domain/model/cart_item.dart';
import 'package:keevo/features/pos/presentation/provider/cart_provider.dart';
import 'package:keevo/features/stores/presentation/provider/active_store_provider.dart';

void _overrideSqlite3ForLinuxTesting() {
  if (!Platform.isLinux) return;
  open.overrideFor(OperatingSystem.linux, () {
    try {
      return DynamicLibrary.open('libsqlite3.so');
    } catch (_) {
      return DynamicLibrary.open('libsqlite3.so.0');
    }
  });
}

String _jwt(Map<String, dynamic> claims) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  final exp = DateTime.now().add(const Duration(hours: 1)).millisecondsSinceEpoch ~/ 1000;
  return '${part({'alg': 'RS256'})}.${part({'exp': exp, ...claims})}.sig';
}

String _employee(String user, {String tenant = 'kv_a', String store = 's1'}) => _jwt({
      'sub': user,
      'tenantId': tenant,
      'role': 'EMPLOYEE',
      'storeId': store,
      'firstName': 'Loïc',
      'passwordChangeRequired': true,
    });

String _owner(String user, {String tenant = 'kv_a'}) =>
    _jwt({'sub': user, 'tenantId': tenant, 'role': 'OWNER'});

void main() {
  setUpAll(_overrideSqlite3ForLinuxTesting);

  late AppDatabase db;
  late SharedPreferences prefs;
  late ProviderContainer container;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({
      kDbEncryptionKey: 'db-key',
      kLastSyncTimestampKey: '1700000000000',
    });
    prefs = await SharedPreferences.getInstance();
    db = AppDatabase.forTesting();
    container = ProviderContainer(overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
      appDatabaseProvider.overrideWithValue(db),
    ]);
  });

  tearDown(() async {
    container.dispose();
    await db.close();
  });

  SessionClaimsApplier applier() => container.read(sessionClaimsApplierProvider);

  Future<void> seedLocalData() => db.into(db.categories).insert(
        CategoriesCompanion.insert(
          id: 'c1',
          name: 'Boissons',
          createdAt: DateTime.now(),
          updatedAt: DateTime.now(),
        ),
      );

  test('stores the claims the app reads elsewhere', () async {
    await applier().apply(_employee('u1'), newSession: true);

    expect(container.read(currentUserRoleProvider), 'EMPLOYEE');
    expect(prefs.getBool(kPasswordChangeRequiredKey), isTrue);
    expect(prefs.getString('user_first_name'), 'Loïc');
    expect(container.read(activeStoreIdProvider), 's1');
  });

  test('a renewal moves a reassigned employee to the new store', () async {
    await applier().apply(_employee('u1'), newSession: true);

    await applier().apply(_employee('u1', store: 's2'));

    expect(container.read(activeStoreIdProvider), 's2');
  });

  test('a renewal keeps the store an owner picked', () async {
    await applier().apply(_owner('o1'), newSession: true);
    container.read(activeStoreIdProvider.notifier).setActiveStore('picked');

    await applier().apply(_owner('o1'));

    expect(container.read(activeStoreIdProvider), 'picked');
  });

  test('the same account signing in again keeps its local data', () async {
    await applier().apply(_employee('u1'), newSession: true);
    await seedLocalData();

    await applier().apply(_employee('u1'), newSession: true);

    expect(await db.select(db.categories).get(), hasLength(1));
  });

  test('another account signing in starts from a clean device state', () async {
    await applier().apply(_employee('u1'), newSession: true);
    await seedLocalData();
    container.read(cartProvider.notifier).addItem(CartItem(
          id: 'p1',
          productId: 'p1',
          productName: 'Savon',
          unitPrice: 500,
          appliedUnitPrice: 500,
          quantity: 1,
        ));

    await applier().apply(_owner('o2', tenant: 'kv_b'), newSession: true);

    expect(await db.select(db.categories).get(), isEmpty);
    expect(container.read(cartProvider), isEmpty);
    // Full pull for the new account instead of a delta from the previous one.
    expect(
      await const FlutterSecureStorage().read(key: kLastSyncTimestampKey),
      isNull,
    );
    expect(
      await const FlutterSecureStorage().read(key: kDbEncryptionKey),
      'db-key',
    );
  });

  Future<void> queueUnsyncedSale() => db.into(db.syncQueue).insert(
        SyncQueueCompanion.insert(
          id: 'op-1',
          operation: 'CREATE_SALE',
          payload: '{}',
          createdAt: DateTime.now(),
        ),
      );

  group('previous account still has unsynced operations', () {
    test('another account signing in is stopped before anything is deleted', () async {
      await applier().apply(_employee('u1'), newSession: true);
      await seedLocalData();
      await queueUnsyncedSale();
      await prefs.setString(kSectorTypeKey, 'FOOD');

      await expectLater(
        applier().apply(_owner('o2', tenant: 'kv_b'), newSession: true),
        throwsA(isA<PreviousAccountDataException>()
            .having((e) => e.pendingOperations, 'pendingOperations', 1)),
      );

      expect(await db.select(db.categories).get(), hasLength(1));
      expect(await db.select(db.syncQueue).get(), hasLength(1));
      expect(prefs.getString(kLocalDataOwnerKey), 'u1@kv_a');
      expect(prefs.getString(kSectorTypeKey), 'FOOD');
    });

    test('accepted by the user: the device is cleared for the new account', () async {
      await applier().apply(_employee('u1'), newSession: true);
      await queueUnsyncedSale();
      await prefs.setString(kSectorTypeKey, 'FOOD');
      await prefs.setBool(kOnboardingWizardCompletedKey, true);

      await applier().apply(_owner('o2', tenant: 'kv_b'),
          newSession: true, discardPreviousAccountData: true);

      expect(await db.select(db.syncQueue).get(), isEmpty);
      expect(prefs.getString(kLocalDataOwnerKey), 'o2@kv_b');
      // Account-bound prefs do not follow the device to the next account.
      expect(prefs.getString(kSectorTypeKey), isNull);
      expect(prefs.getBool(kOnboardingWizardCompletedKey), isNull);
    });

    test('the same account signing back in keeps its operations', () async {
      await applier().apply(_employee('u1'), newSession: true);
      await queueUnsyncedSale();

      await applier().apply(_employee('u1'), newSession: true);

      expect(await db.select(db.syncQueue).get(), hasLength(1));
    });
  });

  // A device upgraded while signed in has no owner recorded yet: the session
  // restored at startup owns the data, so the next account does not inherit it.
  test('a restored session records itself as the owner when none is known', () async {
    await seedLocalData();

    await applier().apply(_employee('u1'));
    expect(prefs.getString(kLocalDataOwnerKey), 'u1@kv_a');

    await applier().apply(_owner('o2', tenant: 'kv_b'), newSession: true);
    expect(await db.select(db.categories).get(), isEmpty);
  });

  test('abandoning a sign-in removes the new tokens and keeps device secrets', () async {
    const storage = FlutterSecureStorage();
    await storage.write(key: 'jwt_token', value: 'new');
    await storage.write(key: 'refresh_token', value: 'new-r');

    await applier().abandonSignIn();

    expect(await storage.read(key: 'jwt_token'), isNull);
    expect(await storage.read(key: 'refresh_token'), isNull);
    expect(await storage.read(key: kDbEncryptionKey), 'db-key');
  });
}
