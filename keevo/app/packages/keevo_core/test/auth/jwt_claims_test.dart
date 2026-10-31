import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/auth/jwt_claims.dart';

String jwt(Map<String, dynamic> payload) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${part({'alg': 'RS256'})}.${part(payload)}.signature';
}

int epoch(DateTime t) => t.millisecondsSinceEpoch ~/ 1000;

void main() {
  final now = DateTime(2026, 10, 2, 12);

  test('reads the session claims', () {
    final claims = JwtClaims.tryParse(jwt({
      'exp': epoch(now.add(const Duration(hours: 1))),
      'role': 'EMPLOYEE',
      'storeId': 's1',
      'firstName': 'Loïc',
      'tenantStatus': 'ACTIVE',
      'passwordChangeRequired': true,
      'onboardingCompleted': false,
    }))!;

    expect(claims.role, 'EMPLOYEE');
    expect(claims.storeId, 's1');
    expect(claims.firstName, 'Loïc');
    expect(claims.passwordChangeRequired, isTrue);
    expect(claims.onboardingCompleted, isFalse);
  });

  test('onboardingCompleted is null on tokens issued before the claim', () {
    final claims = JwtClaims.tryParse(jwt({'exp': epoch(now)}))!;
    expect(claims.onboardingCompleted, isNull);
  });

  test('expiry honours the renewal margin', () {
    final claims = JwtClaims.tryParse(
      jwt({'exp': epoch(now.add(const Duration(seconds: 30)))}),
    )!;

    expect(claims.isExpired(now: now), isFalse);
    expect(claims.isExpired(now: now, margin: const Duration(minutes: 1)), isTrue);
  });

  test('a token without exp counts as expired', () {
    expect(JwtClaims.tryParse(jwt({'role': 'OWNER'}))!.isExpired(now: now), isTrue);
  });

  test('garbage is rejected', () {
    expect(JwtClaims.tryParse(null), isNull);
    expect(JwtClaims.tryParse('not-a-jwt'), isNull);
    expect(JwtClaims.tryParse('eyJ.!!!.sig'), isNull);
  });
}
