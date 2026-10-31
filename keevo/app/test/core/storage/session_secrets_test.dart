import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/features/auth/data/repository/secure_token_storage.dart';
import 'package:keevo_core/storage/app_constants.dart';
import 'package:keevo_core/storage/secure_storage_provider.dart';

const _deviceSecrets = {
  kDbEncryptionKey: 'db-key',
  'keevo_device_id': 'device-1',
};

const _sessionSecrets = {
  'jwt_token': 'jwt',
  'refresh_token': 'refresh',
  'user_id': 'u1',
  'tenant_id': 't1',
  'store_id': 's1',
  kLastSyncTimestampKey: '1700000000000',
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({
      ..._deviceSecrets,
      ..._sessionSecrets,
    });
  });

  // The DB encryption key must outlive the session: losing it makes the
  // next cold start delete the encrypted DB, and with it every sale still
  // waiting in sync_queue.
  Future<void> expectOnlySessionCleared(FlutterSecureStorage storage) async {
    for (final e in _deviceSecrets.entries) {
      expect(await storage.read(key: e.key), e.value, reason: e.key);
    }
    for (final key in _sessionSecrets.keys) {
      expect(await storage.read(key: key), isNull, reason: key);
    }
  }

  test('clearSessionSecrets keeps the DB key and the device id', () async {
    const storage = FlutterSecureStorage();
    await clearSessionSecrets(storage);
    await expectOnlySessionCleared(storage);
  });

  test('SecureTokenStorage.clearAll keeps the DB key and the device id',
      () async {
    const storage = FlutterSecureStorage();
    await const SecureTokenStorage(storage).clearAll();
    await expectOnlySessionCleared(storage);
  });
}
