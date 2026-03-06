import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:mocktail/mocktail.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:keevo/core/storage/db_encryption_key_service.dart';
import 'package:keevo/core/storage/app_constants.dart';

class MockFlutterSecureStorage extends Mock implements FlutterSecureStorage {}

void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  group('DbEncryptionKeyService', () {
    late MockFlutterSecureStorage mockStorage;
    late DbEncryptionKeyService keyService;

    setUp(() {
      mockStorage = MockFlutterSecureStorage();
      keyService = DbEncryptionKeyService(storage: mockStorage);
    });

    test('generates 64-char hex key on first launch (null in storage)', () async {
      when(() => mockStorage.read(key: kDbEncryptionKey))
          .thenAnswer((_) async => null);
      when(() => mockStorage.write(key: kDbEncryptionKey, value: any(named: 'value')))
          .thenAnswer((_) async {});

      final key = await keyService.getOrCreate();

      expect(key, isNotEmpty);
      expect(key.length, 64); // 32 bytes × 2 hex chars = 64
      verify(() => mockStorage.write(key: kDbEncryptionKey, value: any(named: 'value'))).called(1);
    });

    test('returns existing key on subsequent launches', () async {
      const existingKey = 'aabbccddee112233445566778899aabb'
          'ccddee112233445566778899aabbccdd'; // 64 hex chars
      when(() => mockStorage.read(key: kDbEncryptionKey))
          .thenAnswer((_) async => existingKey);

      final key = await keyService.getOrCreate();

      expect(key, existingKey);
      verifyNever(() => mockStorage.write(
          key: any(named: 'key'), value: any(named: 'value')));
    });

    test('generated keys are unique across calls', () async {
      // Two separate service instances each get null from storage
      final storage1 = MockFlutterSecureStorage();
      final storage2 = MockFlutterSecureStorage();
      when(() => storage1.read(key: kDbEncryptionKey)).thenAnswer((_) async => null);
      when(() => storage1.write(key: any(named: 'key'), value: any(named: 'value')))
          .thenAnswer((_) async {});
      when(() => storage2.read(key: kDbEncryptionKey)).thenAnswer((_) async => null);
      when(() => storage2.write(key: any(named: 'key'), value: any(named: 'value')))
          .thenAnswer((_) async {});

      final key1 = await DbEncryptionKeyService(storage: storage1).getOrCreate();
      final key2 = await DbEncryptionKeyService(storage: storage2).getOrCreate();

      expect(key1, isNot(equals(key2)));
    });
  });
}
