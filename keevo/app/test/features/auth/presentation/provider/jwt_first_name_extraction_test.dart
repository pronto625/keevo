import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';

/// Tests the JWT firstName extraction helper logic used in auth_provider.
/// The actual function is private (_extractFirstNameFromJwt), so we test
/// the same logic directly here.
void main() {
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  /// Reproduce the extraction logic from auth_provider.dart.
  String? extractFirstNameFromJwt(String accessToken) {
    try {
      final parts = accessToken.split('.');
      if (parts.length != 3) return null;
      final payload = utf8.decode(
        base64Url.decode(base64Url.normalize(parts[1])),
      );
      final claims = jsonDecode(payload) as Map<String, dynamic>;
      return claims['firstName'] as String?;
    } catch (_) {
      return null;
    }
  }

  /// Build a fake JWT with given claims payload (no real signature needed for test).
  String _buildFakeJwt(Map<String, dynamic> claims) {
    final header = base64UrlEncode(utf8.encode('{"alg":"RS256","typ":"JWT"}'));
    final payload = base64UrlEncode(utf8.encode(jsonEncode(claims)));
    return '$header.$payload.fake-signature';
  }

  group('JWT firstName extraction', () {
    test('extracts firstName from valid JWT', () {
      final jwt = _buildFakeJwt({
        'sub': '+237600000001',
        'role': 'OWNER',
        'firstName': 'Amadou',
        'tenantId': 'tenant-001',
      });
      expect(extractFirstNameFromJwt(jwt), 'Amadou');
    });

    test('returns null when firstName claim is missing', () {
      final jwt = _buildFakeJwt({
        'sub': '+237600000001',
        'role': 'OWNER',
        'tenantId': 'tenant-001',
      });
      expect(extractFirstNameFromJwt(jwt), isNull);
    });

    test('returns null for malformed token (not 3 parts)', () {
      expect(extractFirstNameFromJwt('not-a-jwt'), isNull);
      expect(extractFirstNameFromJwt('only.two'), isNull);
    });

    test('returns null for invalid base64 payload', () {
      expect(extractFirstNameFromJwt('a.!!!invalid!!!.c'), isNull);
    });

    test('handles empty firstName value', () {
      final jwt = _buildFakeJwt({
        'sub': '+237600000001',
        'firstName': '',
      });
      expect(extractFirstNameFromJwt(jwt), '');
    });
  });
}
