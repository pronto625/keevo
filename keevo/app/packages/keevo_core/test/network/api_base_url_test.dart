import 'package:flutter_test/flutter_test.dart';

import 'package:keevo_core/network/api_base_url.dart';

/// G5: a release build must never ship pointing at localhost / clear text.
void main() {
  group('resolveApiBaseUrl — debug / profile', () {
    test('defaults to localhost when not provided (local dev, adb reverse)', () {
      expect(resolveApiBaseUrl('', isRelease: false), kDevApiBaseUrl);
      expect(kDevApiBaseUrl, 'http://localhost:4500');
    });

    test('keeps a provided http URL (emulator, LAN backend)', () {
      expect(resolveApiBaseUrl('http://10.0.3.2:4500', isRelease: false),
          'http://10.0.3.2:4500');
    });
  });

  group('resolveApiBaseUrl — release', () {
    test('accepts an https URL', () {
      expect(resolveApiBaseUrl('https://api.keevo.app', isRelease: true),
          'https://api.keevo.app');
    });

    test('trims surrounding spaces', () {
      expect(resolveApiBaseUrl(' https://api.keevo.app ', isRelease: true),
          'https://api.keevo.app');
    });

    test('fails when API_BASE_URL is not provided', () {
      expect(
        () => resolveApiBaseUrl('', isRelease: true),
        throwsA(isA<InvalidApiBaseUrlError>().having(
            (e) => e.message, 'message', contains('--dart-define=API_BASE_URL'))),
      );
    });

    test('fails on a clear-text http URL', () {
      expect(() => resolveApiBaseUrl('http://localhost:4500', isRelease: true),
          throwsA(isA<InvalidApiBaseUrlError>()));
      expect(() => resolveApiBaseUrl('http://api.keevo.app', isRelease: true),
          throwsA(isA<InvalidApiBaseUrlError>()));
    });

    test('fails on a URL without host or with another scheme', () {
      expect(() => resolveApiBaseUrl('https://', isRelease: true),
          throwsA(isA<InvalidApiBaseUrlError>()));
      expect(() => resolveApiBaseUrl('api.keevo.app', isRelease: true),
          throwsA(isA<InvalidApiBaseUrlError>()));
      expect(() => resolveApiBaseUrl('ftp://api.keevo.app', isRelease: true),
          throwsA(isA<InvalidApiBaseUrlError>()));
    });
  });
}
