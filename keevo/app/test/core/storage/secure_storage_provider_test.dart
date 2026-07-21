import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/storage/secure_storage_provider.dart';

void main() {
  group('buildSecureStorage', () {
    test('shouldUseEncryptedSharedPreferencesOnAndroid', () {
      final storage = buildSecureStorage();
      final optionsMap = storage.aOptions.toMap();
      expect(
        optionsMap['encryptedSharedPreferences'],
        equals('true'),
        reason:
            'Android storage must use EncryptedSharedPreferences (S8 hardening)',
      );
    });

    test('shouldUseFirstUnlockOnIOS', () {
      final storage = buildSecureStorage();
      final optionsMap = storage.iOptions.toMap();
      expect(
        optionsMap['accessibility'],
        equals('first_unlock_this_device'),
        reason:
            'iOS keychain must use first_unlock_this_device accessibility (S8 hardening)',
      );
    });
  });
}
