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
        '/terms/view',
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

    // v1s-18-7: the action history, its detail and the stock movements
    // screen are owner-only.
    test('an employee is kept out of the action history routes', () async {
      for (final path in [
        '/audit',
        '/audit/entry/abc',
        '/audit/stock-movements',
      ]) {
        expect(await redirect(path, session: true, role: 'EMPLOYEE'), '/pos',
            reason: path);
        expect(await redirect(path, session: true, role: 'OWNER'), isNull,
            reason: path);
      }
    });

    test('an owner reaches owner-only routes', () async {
      expect(await redirect('/dashboard', session: true, role: 'OWNER'), isNull);
    });

    // v1s-16-13: a missing role is not a pass; the splash re-applies the
    // JWT claims.
    test('a missing role is sent to the splash on owner-only routes',
        () async {
      expect(await redirect('/dashboard', session: true), '/splash');
      expect(await redirect('/settings/team', session: true), '/splash');
      expect(await redirect('/pos', session: true), isNull);
    });

    test('an unknown role is kept out of owner-only routes', () async {
      expect(await redirect('/dashboard', session: true, role: 'MANAGER'),
          '/pos');
    });
  });

  group('loginRouteRedirect (v1s-16-13)', () {
    test('a session goes through the splash (wizard, forced change, role)',
        () async {
      expect(await loginRouteRedirect(hasSession: () async => true),
          '/splash');
    });

    test('no session shows the login form', () async {
      expect(await loginRouteRedirect(hasSession: () async => false), isNull);
    });

    test('a failed splash shows the login form even with a session',
        () async {
      expect(
          await loginRouteRedirect(
              hasSession: () async => true, extra: kSplashFailedExtra),
          isNull);
    });

    test('unreadable storage shows the login form', () async {
      expect(
          await loginRouteRedirect(
              hasSession: () async => throw StateError('keystore')),
          isNull);
    });
  });

  group('resetPasswordRedirect (v1s-16-13)', () {
    test('a deep link without phone restarts from forgot-password', () {
      expect(resetPasswordRedirect(null), '/auth/forgot-password');
      expect(resetPasswordRedirect(<String, dynamic>{}),
          '/auth/forgot-password');
      expect(resetPasswordRedirect({'phoneNumber': ''}),
          '/auth/forgot-password');
      expect(resetPasswordRedirect({'phoneNumber': '   '}),
          '/auth/forgot-password');
      expect(resetPasswordRedirect('junk'), '/auth/forgot-password');
    });

    test('the phone extra opens the reset page', () {
      expect(resetPasswordRedirect({'phoneNumber': '+237600000000'}), isNull);
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
