import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/network/log_redaction.dart';

void main() {
  group('redactAuthorizationHeader', () {
    test('redacts Bearer token when Authorization header is present', () {
      const input = 'Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9';
      const expected = 'Authorization: Bearer [REDACTED]';
      expect(redactAuthorizationHeader(input), equals(expected));
    });

    test('redacts only the Bearer token, preserves rest of the line', () {
      const input =
          'GET /api/v1/products HTTP/1.1\r\nAuthorization: Bearer abc.def.ghi\r\nContent-Type: application/json';
      const expected =
          'GET /api/v1/products HTTP/1.1\r\nAuthorization: Bearer [REDACTED]\r\nContent-Type: application/json';
      expect(redactAuthorizationHeader(input), equals(expected));
    });

    test('returns line unchanged when Authorization header is absent', () {
      const input = 'Content-Type: application/json';
      expect(redactAuthorizationHeader(input), equals(input));
    });

    test('returns line unchanged for empty string', () {
      const input = '';
      expect(redactAuthorizationHeader(input), equals(input));
    });

    test('handles multiple Authorization headers (unusual but defensive)', () {
      const input =
          'Authorization: Bearer token1\r\nAuthorization: Bearer token2';
      const expected =
          'Authorization: Bearer [REDACTED]\r\nAuthorization: Bearer [REDACTED]';
      expect(redactAuthorizationHeader(input), equals(expected));
    });
  });
}
