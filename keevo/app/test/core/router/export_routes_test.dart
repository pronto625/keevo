import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:keevo/core/router/app_router.dart';
import 'package:keevo/features/export/presentation/page/local_export_page.dart';

class _FakeContext extends Fake implements BuildContext {}

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

  test('/exports/local is the on-device CSV, not a job id (v1s-18-5)', () {
    expect(lastPath('/exports/local'), '/exports/local');
    final match =
        appRouter.configuration.findMatch(Uri.parse('/exports/local'));
    final route = match.matches.last.route as GoRoute;
    final state = appRouter.configuration.buildTopLevelGoRouterState(match);
    final page = route.builder!(_FakeContext(), state);
    expect(page, isA<LocalExportPage>());
  });

  test('/exports is « Mes exports »', () {
    expect(lastPath('/exports'), '/exports');
  });
}
