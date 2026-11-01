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

  test('16.11 — reads identity only: sub, tenantId, sid', () {
    final claims = JwtClaims.tryParse(jwt({
      'sub': 'u1',
      'tenantId': 'kv_abc123',
      'sid': 's-1',
      'exp': epoch(now.add(const Duration(minutes: 30))),
      // Indicative legacy claims for V1 apps: this app never reads them.
      'role': 'SUPER_ADMIN',
    }))!;

    expect(claims.subject, 'u1');
    expect(claims.tenantId, 'kv_abc123');
    expect(claims.sessionId, 's-1');
    expect(claims.isPlatformAdmin, isFalse,
        reason: 'a role claim never makes a platform admin');
  });

  test('16.11 — the super-admin is told by its system account', () {
    expect(JwtClaims.tryParse(jwt({'tenantId': 'public', 'exp': epoch(now)}))!
        .isPlatformAdmin, isTrue);
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
