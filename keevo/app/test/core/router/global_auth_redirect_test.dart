import 'package:flutter_test/flutter_test.dart';

import 'package:keevo/core/router/app_router.dart';

/// The real global guard of appRouter (route tests build their own GoRouter
/// and never run it).
void main() {
  Future<String?> redirect(String path, {bool session = false, String? role}) =>
      globalAuthRedirect(
        path,
        hasSession: () async => session,
        readRole: () async => role,
      );

  group('signed out', () {
    // The splash sends a signed-out user who has not accepted the CGU to
    // /terms: guarding it looped /splash → /terms → /splash.
    test('/terms is reachable', () async {
      expect(await redirect('/terms'), isNull);
    });

    test('auth and onboarding pages are reachable', () async {
      for (final path in [
        '/splash',
        '/auth/login',
        '/auth/register',
        '/auth/forgot-password',
        '/auth/reset-password',
        '/tenant-picker',
        '/onboarding',
        '/onboarding/sector',
      ]) {
        expect(await redirect(path), isNull, reason: path);
      }
    });

    test('a protected route goes through the splash', () async {
      expect(await redirect('/pos'), '/splash');
      expect(await redirect('/auth/change-password'), '/splash');
    });
  });

  group('signed in', () {
    test('an employee is kept out of owner-only routes', () async {
      expect(await redirect('/dashboard', session: true, role: 'EMPLOYEE'), '/pos');
      expect(await redirect('/pos', session: true, role: 'EMPLOYEE'), isNull);
    });

    // v1s-16-15: the blocked operations page is open to every role (the
    // rejection SnackBar sends employees there); /settings/sync is not.
    test('an employee reaches /sync/blocked', () async {
      expect(await redirect('/sync/blocked', session: true, role: 'EMPLOYEE'),
          isNull);
      expect(await redirect('/settings/sync', session: true, role: 'EMPLOYEE'),
          '/pos');
    });

    test('an owner reaches owner-only routes', () async {
      expect(await redirect('/dashboard', session: true, role: 'OWNER'), isNull);
    });
  });

  test('unreadable storage sends to the splash, never loops on it', () async {
    Future<bool> broken() async => throw StateError('keystore');
    expect(
      await globalAuthRedirect('/pos', hasSession: broken, readRole: () async => null),
      '/splash',
    );
  });
}
