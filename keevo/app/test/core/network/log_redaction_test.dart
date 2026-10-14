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

  group('redactSensitiveFields / redactLogLine', () {
    test('redacts the password of a Dart map dump (LogInterceptor data:)', () {
      const input = 'data: {phoneNumber: +237690000000, password: Jean2027!}';
      final out = redactLogLine(input);
      expect(out, 'data: {phoneNumber: +237690000000, password: [REDACTED]}');
      expect(out, isNot(contains('Jean2027!')));
    });

    test('redacts every sensitive key of a map dump, keeps the others', () {
      const input = '{currentPassword: old1, newPassword: new2, oldPassword: x, '
          'otp: 123456, code: 654321, tenantCode: KV-1, loginToken: lt, '
          'accessToken: at, refreshToken: rt, token: t}';
      final out = redactSensitiveFields(input);
      for (final secret in [
        'old1', 'new2', ': x', '123456', '654321', 'lt', ': at', ': rt', ': t}'
      ]) {
        expect(out, isNot(contains(secret)), reason: secret);
      }
      expect(out, contains('tenantCode: KV-1'));
      expect('[REDACTED]'.allMatches(out).length, 9);
    });

    test('redacts JSON string and bare values', () {
      const input =
          '{"phoneNumber":"+237690000000","password":"Je\\"an","otp": 123456}';
      expect(
        redactSensitiveFields(input),
        '{"phoneNumber":"+237690000000","password":"[REDACTED]","otp": [REDACTED]}',
      );
    });

    test('redacts tokens of a login response dump', () {
      const input =
          '{accessToken: eyJhbGciOi.payload.sig, refreshToken: 8f1c2d, expiresIn: 900}';
      expect(redactSensitiveFields(input),
          '{accessToken: [REDACTED], refreshToken: [REDACTED], expiresIn: 900}');
    });

    test('redacts sensitive query parameters', () {
      const input = 'uri: https://api/x?token=abc&page=2';
      expect(redactSensitiveFields(input), 'uri: https://api/x?token=[REDACTED]&page=2');
    });

    test('keeps unrelated lines and statusCode untouched', () {
      const input = 'statusCode: 403';
      expect(redactLogLine(input), input);
    });

    test('still redacts the Authorization header', () {
      const input = 'Authorization: Bearer abc.def.ghi';
      expect(redactLogLine(input), 'Authorization: Bearer [REDACTED]');
    });
  });
}
