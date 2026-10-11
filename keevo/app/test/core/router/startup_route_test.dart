import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keevo/core/auth/session_refresher.dart';
import 'package:keevo/core/router/startup_route.dart';
import 'package:keevo/core/storage/app_constants.dart';
import 'package:keevo/core/di/providers.dart';

class _MockRefresher extends Mock implements SessionRefresher {}

String _jwt(Map<String, dynamic> claims, {Duration expiresIn = const Duration(hours: 1)}) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  final exp = DateTime.now().add(expiresIn).millisecondsSinceEpoch ~/ 1000;
  return '${part({'alg': 'RS256'})}.${part({'exp': exp, ...claims})}.sig';
}

void main() {
  late SharedPreferences prefs;
  late _MockRefresher refresher;
  late List<String> applied;
  const storage = FlutterSecureStorage();

  Future<String> route() => resolveStartupRoute(
        storage: storage,
        prefs: prefs,
        refresher: refresher,
        applyClaims: (token) async => applied.add(token),
      );

  Future<void> signedIn(String accessToken, {String? refreshToken = 'r1'}) async {
    FlutterSecureStorage.setMockInitialValues({
      'jwt_token': accessToken,
      if (refreshToken != null) 'refresh_token': refreshToken,
      kDbEncryptionKey: 'db-key',
    });
  }

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    refresher = _MockRefresher();
    applied = [];
    FlutterSecureStorage.setMockInitialValues({});
  });

  group('signed in', () {
    test('owner with onboarding done lands on the dashboard', () async {
      await signedIn(_jwt({'role': 'OWNER', 'onboardingCompleted': true}));
      expect(await route(), '/dashboard');
      expect(applied, hasLength(1));
    });

    test('employee lands on the POS', () async {
      await signedIn(_jwt({'role': 'EMPLOYEE', 'onboardingCompleted': true}));
      expect(await route(), '/pos');
    });

    test('wizard interrupted (app killed): back to the wizard, even with ACTIVE tenant', () async {
      await signedIn(_jwt({
        'role': 'OWNER',
        'tenantStatus': 'ACTIVE',
        'onboardingCompleted': false,
      }));
      await prefs.setBool(kOnboardingWizardSeenKey, true);

      expect(await route(), '/onboarding/sector');
    });

    test('wizard finished on this device wins over a token still saying false', () async {
      // The registration token says onboardingCompleted=false until renewed.
      await signedIn(_jwt({'role': 'OWNER', 'onboardingCompleted': false}));
      await prefs.setBool(kOnboardingWizardCompletedKey, true);

      expect(await route(), '/dashboard');
      expect(prefs.getBool(kOnboardingWizardSeenKey), isTrue);
    });

    test('token from before the onboarding claim: tenantStatus fallback', () async {
      await signedIn(_jwt({'role': 'OWNER', 'tenantStatus': 'ACTIVE'}));
      expect(await route(), '/dashboard');
    });

    test('forced password change comes first', () async {
      await signedIn(_jwt({'role': 'EMPLOYEE', 'onboardingCompleted': true}));
      await prefs.setBool(kPasswordChangeRequiredKey, true);
      expect(await route(), '/auth/change-password');
    });
  });

  group('expired access token', () {
    final expired = _jwt({'role': 'OWNER', 'onboardingCompleted': true},
        expiresIn: const Duration(days: -1));

    test('renewed through the refresh token: user stays signed in', () async {
      await signedIn(expired);
      final renewed = _jwt({'role': 'OWNER', 'onboardingCompleted': true});
      when(() => refresher.refresh()).thenAnswer((_) async {
        await storage.write(key: 'jwt_token', value: renewed);
        return RefreshOutcome.renewed;
      });

      expect(await route(), '/dashboard');
      expect(applied.last, renewed);
    });

    test('offline: user keeps working, tokens kept', () async {
      await signedIn(expired);
      when(() => refresher.refresh()).thenAnswer((_) async => RefreshOutcome.unreachable);

      expect(await route(), '/dashboard');
      expect(await storage.read(key: 'refresh_token'), 'r1');
      expect(await storage.read(key: 'jwt_token'), expired);
    });

    test('refused by the server: back to login, device secrets kept', () async {
      await signedIn(expired);
      await prefs.setBool(kOnboardingSeenKey, true);
      await prefs.setBool(kTermsAcceptedKey, true);
      when(() => refresher.refresh()).thenAnswer((_) async => RefreshOutcome.rejected);

      expect(await route(), '/auth/login');
      expect(await storage.read(key: 'refresh_token'), isNull);
      expect(await storage.read(key: 'jwt_token'), isNull);
      expect(await storage.read(key: kDbEncryptionKey), 'db-key');
    });
  });

  group('no session', () {
    test('first launch: welcome screen', () async {
      expect(await route(), '/onboarding');
    });

    test('welcome seen but terms not accepted: terms', () async {
      await prefs.setBool(kOnboardingSeenKey, true);
      expect(await route(), '/terms');
    });

    test('returning user: login', () async {
      await prefs.setBool(kOnboardingSeenKey, true);
      await prefs.setBool(kTermsAcceptedKey, true);
      expect(await route(), '/auth/login');
    });

    test('garbage token is discarded', () async {
      await signedIn('not-a-jwt');
      expect(await route(), '/onboarding');
      expect(await storage.read(key: 'jwt_token'), isNull);
    });
  });

  group('hasSession (route guard)', () {
    test('valid access token', () async {
      await signedIn(_jwt({'role': 'OWNER'}));
      expect(await hasSession(storage), isTrue);
    });

    test('expired access token with a refresh token: still a session', () async {
      await signedIn(_jwt({'role': 'OWNER'}, expiresIn: const Duration(days: -1)));
      expect(await hasSession(storage), isTrue);
    });

    test('expired access token without refresh token: no session', () async {
      await signedIn(_jwt({'role': 'OWNER'}, expiresIn: const Duration(days: -1)),
          refreshToken: null);
      expect(await hasSession(storage), isFalse);
    });

    test('nothing stored: no session', () async {
      expect(await hasSession(storage), isFalse);
    });
  });
}
