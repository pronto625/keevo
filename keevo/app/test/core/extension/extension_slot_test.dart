import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo/core/entitlements/entitlements.dart';
import 'package:keevo/core/entitlements/entitlements_provider.dart';
import 'package:keevo/core/extension/extension_actions.dart';
import 'package:keevo/core/extension/extension_slot.dart';
import 'package:keevo/core/widget/deferred_widget.dart';
import 'package:keevo/features/auth/presentation/provider/auth_provider.dart'
    show dioProvider;

import '../../helpers/fake_entitlements.dart';

/// Module states set by the test.
class _ControlledEntitlements extends EntitlementsNotifier {
  final Entitlements initial;

  _ControlledEntitlements(this.initial);

  @override
  Future<Entitlements> build() async => initial;

  void set(Entitlements value) => state = AsyncData(value);
}

Entitlements _server(Map<String, String> states, {DateTime? at}) =>
    Entitlements(
        states: states, status: EntitlementsStatus.server, fetchedAt: at);

const _facturer = ExtensionContribution(
  id: 'invoicing.invoice-sale',
  label: 'Facturer',
  order: 100,
  action: kOpenSaleInvoiceAction,
);

/// Counts the requests and answers [answer] (throw to simulate a failure).
class _Fetcher {
  Future<List<ExtensionContribution>> Function() answer;
  int calls = 0;

  _Fetcher(this.answer);

  Future<List<ExtensionContribution>> call(String pointId) {
    calls++;
    return answer();
  }
}

void main() {
  group('ExtensionContribution.parseList', () {
    test('ordered by order; incomplete entries dropped', () {
      final list = ExtensionContribution.parseList([
        {'id': 'b', 'label': 'B', 'order': 200, 'action': 'x.b'},
        {'id': 'a', 'label': ' A ', 'order': 100, 'action': 'x.a'},
        {'id': 'c', 'label': '', 'order': 1, 'action': 'x.c'},
        {'id': 'd', 'order': 1, 'action': 'x.d'},
        'garbage',
      ]);
      expect(list.map((c) => c.id), ['a', 'b']);
      expect(list.first.label, 'A');
    });

    test('duplicate ids kept once; ties by id; missing order last', () {
      final list = ExtensionContribution.parseList([
        {'id': 'z', 'label': 'Z', 'action': 'x.z'},
        {'id': 'b', 'label': 'B', 'order': 10, 'action': 'x.b'},
        {'id': 'a', 'label': 'A', 'order': 10, 'action': 'x.a'},
        {'id': 'a', 'label': 'A bis', 'order': 1, 'action': 'x.a2'},
      ]);
      expect(list.map((c) => c.id), ['a', 'b', 'z']);
      expect(list.first.label, 'A');
      expect(list.last.order, kExtensionUnorderedLast);
    });

    test('not a list → FormatException', () {
      expect(() => ExtensionContribution.parseList({'x': 1}),
          throwsFormatException);
    });
  });

  test('extensionCacheKey: known-ness and every module state', () {
    expect(extensionCacheKey(null), '?');
    expect(extensionCacheKey(Entitlements.unknown), '?');
    final a = _server({'invoicing': 'ENABLED', 'crm': 'READ_ONLY'},
        at: DateTime(2026, 1, 1));
    final sameStates = _server({'crm': 'READ_ONLY', 'invoicing': 'ENABLED'},
        at: DateTime(2026, 2, 1));
    expect(extensionCacheKey(a), extensionCacheKey(sameStates));
    // ENABLED → READ_ONLY keeps the readable set but changes the key.
    expect(
        extensionCacheKey(a),
        isNot(extensionCacheKey(
            _server({'invoicing': 'READ_ONLY', 'crm': 'READ_ONLY'}))));
  });

  group('extensionContributionsProvider', () {
    late _Fetcher fetcher;
    late _ControlledEntitlements entitlements;
    late ProviderContainer container;

    setUp(() {
      fetcher = _Fetcher(() async => [_facturer]);
      entitlements = _ControlledEntitlements(_server({'invoicing': 'ENABLED'}));
      container = ProviderContainer(overrides: [
        entitlementsProvider.overrideWith(() => entitlements),
        extensionContributionsFetcherProvider.overrideWithValue(fetcher.call),
      ]);
      addTearDown(container.dispose);
    });

    Future<List<ExtensionContribution>> read() => container.read(
        extensionContributionsProvider(kPosCheckoutPostSaleActions).future);

    test('cached per session: the same point twice → one request', () async {
      await container.read(entitlementsProvider.future);
      expect(await read(), [_facturer]);
      expect(await read(), [_facturer]);
      expect(fetcher.calls, 1);
    });

    test('readable set changes → cache dropped, next answer empty', () async {
      await container.read(entitlementsProvider.future);
      final sub = container.listen(
          extensionContributionsProvider(kPosCheckoutPostSaleActions),
          (_, __) {});
      addTearDown(sub.close);
      expect(await read(), [_facturer]);

      fetcher.answer = () async => [];
      entitlements.set(_server({'invoicing': 'DISABLED'}));
      expect(await read(), isEmpty);
      expect(fetcher.calls, 2);
    });

    test('same readable set (new fetch date) → no new request', () async {
      await container.read(entitlementsProvider.future);
      final sub = container.listen(
          extensionContributionsProvider(kPosCheckoutPostSaleActions),
          (_, __) {});
      addTearDown(sub.close);
      await read();
      entitlements
          .set(_server({'invoicing': 'ENABLED'}, at: DateTime(2027, 1, 1)));
      await read();
      expect(fetcher.calls, 1);
    });

    test('failure not cached: the next read asks again; the success is kept',
        () async {
      fetcher.answer = () async => throw Exception('offline');
      await container.read(entitlementsProvider.future);
      await expectLater(read(), throwsException);
      await pumpEventQueue();

      fetcher.answer = () async => [_facturer];
      expect(await read(), [_facturer]);
      expect(fetcher.calls, 2);
      await pumpEventQueue();
      expect(await read(), [_facturer]);
      expect(fetcher.calls, 2);
    });

    test('an invalidation (sign-out / account switch) drops the cache',
        () async {
      await container.read(entitlementsProvider.future);
      await read();
      container.invalidate(extensionContributionsProvider);
      await read();
      expect(fetcher.calls, 2);
    });
  });

  test(
      'module states still loading at start → one request, after them; '
      'a token renewal (same readable set) asks nothing', () async {
    var fetches = 0;
    final c = ProviderContainer(overrides: [
      entitlementsProvider.overrideWith(() => FakeEntitlementsNotifier(
          () async => const {'invoicing': 'ENABLED'},
          seed: Entitlements(
              states: const {'invoicing': 'ENABLED'},
              status: EntitlementsStatus.cached))),
      extensionContributionsFetcherProvider.overrideWithValue((_) async {
        fetches++;
        return const [_facturer];
      }),
    ]);
    addTearDown(c.dispose);
    final point = extensionContributionsProvider(kPosCheckoutPostSaleActions);
    final sub = c.listen(point, (_, __) {});
    addTearDown(sub.close);
    expect(await c.read(point.future), [_facturer]);
    await pumpEventQueue();
    expect(fetches, 1);

    c.invalidate(entitlementsProvider);
    await c.read(entitlementsProvider.future);
    await pumpEventQueue();
    await c.read(point.future);
    expect(fetches, 1);
  });

  group('ExtensionSlot', () {
    late List<Map<String, Object?>> runs;

    setUp(() => runs = []);

    Future<void> pump(
      WidgetTester tester,
      Future<List<ExtensionContribution>> Function() answer,
    ) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          entitlementsProvider.overrideWith(
              () => _ControlledEntitlements(_server({'invoicing': 'ENABLED'}))),
          extensionContributionsFetcherProvider
              .overrideWithValue((_) => answer()),
          extensionActionsProvider.overrideWithValue({
            kOpenSaleInvoiceAction: (context, ref, args) async =>
                runs.add(args),
          }),
        ],
        child: const MaterialApp(
          home: Scaffold(
            body: ExtensionSlot(
              pointId: kPosCheckoutPostSaleActions,
              args: {'saleId': 's-1'},
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('invoicing active → « Facturer », tap runs its handler',
        (tester) async {
      await pump(tester, () async => [_facturer]);
      expect(find.text('Facturer'), findsOneWidget);
      expect(find.textContaining('invoicing'), findsNothing);

      await tester.tap(find.text('Facturer'));
      await tester.pump();
      expect(runs, [
        {'saleId': 's-1'}
      ]);
    });

    testWidgets('invoicing inactive (empty answer) → nothing', (tester) async {
      await pump(tester, () async => []);
      expect(find.byType(OutlinedButton), findsNothing);
    });

    testWidgets('offline / error → nothing, no error UI', (tester) async {
      await pump(tester, () async => throw Exception('offline'));
      expect(find.byType(OutlinedButton), findsNothing);
      expect(find.textContaining('rreur'), findsNothing);
    });

    testWidgets('unknown action → not rendered; known ones in order',
        (tester) async {
      await pump(
          tester,
          () async => [
                const ExtensionContribution(
                    id: 'other',
                    label: 'Autre',
                    order: 1,
                    action: 'other.thing'),
                _facturer,
              ]);
      expect(find.text('Autre'), findsNothing);
      expect(find.text('Facturer'), findsOneWidget);
    });
  });

  group('production fetcher', () {
    test('GET /api/v1/tenants/me/extensions/<point>, {data:[…]} parsed',
        () async {
      final paths = <String>[];
      final dio = Dio()
        ..httpClientAdapter = _Adapter((options) {
          paths.add(options.path);
          return ResponseBody.fromString(
            jsonEncode({
              'success': true,
              'data': [
                {
                  'id': 'invoicing.invoice-sale',
                  'label': 'Facturer',
                  'order': 100,
                  'action': kOpenSaleInvoiceAction,
                }
              ],
            }),
            200,
            headers: {
              Headers.contentTypeHeader: [Headers.jsonContentType]
            },
          );
        });
      final c =
          ProviderContainer(overrides: [dioProvider.overrideWithValue(dio)]);
      addTearDown(c.dispose);
      final list = await c.read(extensionContributionsFetcherProvider)(
          kPosCheckoutPostSaleActions);
      expect(paths.single,
          '/api/v1/tenants/me/extensions/pos.checkout.post-sale-actions');
      expect(list, [_facturer]);
    });
  });

  group('ExtensionSlot taps', () {
    Future<void> pump(WidgetTester tester, ExtensionActionHandler handler) =>
        tester.pumpWidget(ProviderScope(
          overrides: [
            entitlementsProvider.overrideWith(() =>
                _ControlledEntitlements(_server({'invoicing': 'ENABLED'}))),
            extensionContributionsFetcherProvider
                .overrideWithValue((_) async => [_facturer]),
            extensionActionsProvider
                .overrideWithValue({kOpenSaleInvoiceAction: handler}),
          ],
          child: const MaterialApp(
            home: Scaffold(
              body: ExtensionSlot(pointId: kPosCheckoutPostSaleActions),
            ),
          ),
        ));

    testWidgets('double tap while the action runs → one handler call',
        (tester) async {
      var calls = 0;
      final running = Completer<void>();
      await pump(tester, (context, ref, args) {
        calls++;
        return running.future;
      });
      await tester.pumpAndSettle();
      await tester.tap(find.text('Facturer'));
      await tester.tap(find.text('Facturer'));
      await tester.pump();
      expect(calls, 1);

      running.complete();
      await tester.pump();
      await tester.tap(find.text('Facturer'));
      await tester.pump();
      expect(calls, 2);
    });

    testWidgets('handler failure (module code not loaded) → French snackbar',
        (tester) async {
      await pump(tester, (context, ref, args) async {
        throw DeferredLoadException('Loading invoicing failed');
      });
      await tester.pumpAndSettle();
      await tester.tap(find.text('Facturer'));
      await tester.pumpAndSettle();
      expect(find.byType(SnackBar), findsOneWidget);
      expect(find.text(kDeferredLoadFailedMessage), findsOneWidget);
    });
  });

  test('the production registry knows « Facturer »', () {
    expect(kExtensionActions.keys, contains(kOpenSaleInvoiceAction));
  });
}

/// Serves canned answers to a real [Dio].
class _Adapter implements HttpClientAdapter {
  final ResponseBody Function(RequestOptions) handler;

  _Adapter(this.handler);

  @override
  Future<ResponseBody> fetch(RequestOptions options,
          Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async =>
      handler(options);

  @override
  void close({bool force = false}) {}
}
