import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/core/router/app_router.dart';

/// v1s-18-4 — the real appRouter resolves the export routes: `/exports/new`
/// is not swallowed by `/exports/:id`.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  String lastPath(String location) {
    final match = appRouter.configuration.findMatch(Uri.parse(location));
    return (match.matches.last.route as GoRoute).path;
  }

  test('/exports/new is the export screen, not a job id', () {
    expect(lastPath('/exports/new'), '/exports/new');
  });

  test('/exports/abc is a job deep link with its id', () {
    final match = appRouter.configuration.findMatch(Uri.parse('/exports/abc'));
    expect((match.matches.last.route as GoRoute).path, '/exports/:id');
    expect(match.pathParameters['id'], 'abc');
  });

  test('/exports is « Mes exports »', () {
    expect(lastPath('/exports'), '/exports');
  });
}
