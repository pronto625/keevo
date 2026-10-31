import 'dart:async';

import 'package:flutter/material.dart' show DateTimeRange;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_invoicing/data/remote_invoice_datasource.dart';
import 'package:keevo_invoicing/domain/model/invoice.dart';
import 'package:keevo_invoicing/presentation/provider/invoice_list_provider.dart';
import 'package:keevo_invoicing/presentation/provider/invoicing_providers.dart';

Invoice _inv(String id) => Invoice(id: id, status: kInvoiceIssued, totalTtc: 1);

/// `list` answers come from [answer] (page, search) → future; calls recorded.
class _FakeDataSource implements RemoteInvoiceDataSource {
  final Future<InvoicePage> Function(int page, String? search) answer;
  final List<({int page, String? search})> calls = [];

  _FakeDataSource(this.answer);

  @override
  Future<InvoicePage> list({
    String? status,
    String? search,
    DateTime? from,
    DateTime? to,
    int page = 0,
    int size = 20,
  }) {
    final text = search?.trim();
    final s = text == null || text.isEmpty ? null : text;
    calls.add((page: page, search: s));
    return answer(page, s);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

ProviderContainer _container(_FakeDataSource ds) {
  final c = ProviderContainer(
    retry: noProviderRetry,
    overrides: [invoiceDataSourceProvider.overrideWithValue(ds)],
  );
  addTearDown(c.dispose);
  // keep the auto-dispose providers alive for the test
  c.listen(invoiceListProvider, (_, _) {});
  c.listen(invoiceListFilterProvider, (_, _) {});
  return c;
}

void main() {
  group('InvoiceListFilter', () {
    test('equality ignores surrounding spaces of the search', () {
      expect(
        const InvoiceListFilter(search: ' awa '),
        const InvoiceListFilter(search: 'awa'),
      );
      expect(
        const InvoiceListFilter(search: 'awa').hashCode,
        const InvoiceListFilter(search: ' awa').hashCode,
      );
      expect(
        const InvoiceListFilter(status: kInvoicePaid),
        isNot(const InvoiceListFilter()),
      );
    });

    test('copyWith keeps, sets and clears nullable criteria', () {
      final range = DateTimeRange(
        start: DateTime(2026, 10, 1),
        end: DateTime(2026, 10, 31),
      );
      final f = InvoiceListFilter(
        status: kInvoicePaid,
        search: 'x',
        range: range,
      );
      expect(
        f.copyWith(search: 'y'),
        InvoiceListFilter(status: kInvoicePaid, search: 'y', range: range),
      );
      expect(f.copyWith(status: null).status, isNull);
      expect(f.copyWith(range: null).range, isNull);
      expect(f.copyWith().range, range);
      expect(const InvoiceListFilter().isFiltered, isFalse);
      expect(const InvoiceListFilter(search: '  ').isFiltered, isFalse);
      expect(f.isFiltered, isTrue);
    });
  });

  group('InvoiceListNotifier', () {
    test(
      'overlapping pages: an invoice already listed is not added twice',
      () async {
        final ds = _FakeDataSource(
          (page, _) async => page == 0
              ? InvoicePage(items: [_inv('a'), _inv('b')], totalElements: 4)
              // an invoice created meanwhile shifted the offsets: « b » again
              : InvoicePage(items: [_inv('b'), _inv('c')], totalElements: 5),
        );
        final c = _container(ds);
        await c.read(invoiceListProvider.future);
        await c.read(invoiceListProvider.notifier).loadMore();
        final state = c.read(invoiceListProvider).value!;
        expect(state.items.map((i) => i.id), ['a', 'b', 'c']);
        expect(state.totalElements, 5);
        expect(state.nextPage, 2);
      },
    );

    test('a page arriving after setSearch is dropped', () async {
      final slow = Completer<InvoicePage>();
      final ds = _FakeDataSource((page, search) {
        if (search == null && page == 0) {
          return Future.value(
            InvoicePage(items: [_inv('a')], totalElements: 2),
          );
        }
        if (search == null && page == 1) return slow.future;
        return Future.value(InvoicePage(items: [_inv('n1')], totalElements: 1));
      });
      final c = _container(ds);
      await c.read(invoiceListProvider.future);
      final more = c.read(invoiceListProvider.notifier).loadMore();
      c.read(invoiceListFilterProvider.notifier).setSearch('ndolo');
      final searched = await c.read(invoiceListProvider.future);
      expect(searched.items.map((i) => i.id), ['n1']);

      slow.complete(InvoicePage(items: [_inv('stale')], totalElements: 2));
      await more;
      final state = c.read(invoiceListProvider).value!;
      expect(state.items.map((i) => i.id), ['n1']);
      expect(state.loadingMore, isFalse);
    });

    test('an empty page ends the list (no endless requests)', () async {
      final ds = _FakeDataSource(
        (page, _) async => page == 0
            ? InvoicePage(items: [_inv('a')], totalElements: 3)
            : const InvoicePage(items: [], totalElements: 3),
      );
      final c = _container(ds);
      await c.read(invoiceListProvider.future);
      await c.read(invoiceListProvider.notifier).loadMore();
      final state = c.read(invoiceListProvider).value!;
      expect(state.hasMore, isFalse);
      await c.read(invoiceListProvider.notifier).loadMore();
      expect(ds.calls, hasLength(2));
    });

    test(
      'after a failed page: no retry on scroll, only on « Réessayer »',
      () async {
        var fail = true;
        final ds = _FakeDataSource((page, _) async {
          if (page == 0) {
            return InvoicePage(items: [_inv('a')], totalElements: 2);
          }
          if (fail) throw Exception('hors ligne');
          return InvoicePage(items: [_inv('b')], totalElements: 2);
        });
        final c = _container(ds);
        await c.read(invoiceListProvider.future);
        final notifier = c.read(invoiceListProvider.notifier);
        await notifier.loadMore();
        expect(c.read(invoiceListProvider).value!.moreError, isNotNull);
        await notifier.loadMore(); // scroll tick
        expect(ds.calls, hasLength(2));
        fail = false;
        await notifier.loadMore(retry: true);
        expect(ds.calls, hasLength(3));
        expect(c.read(invoiceListProvider).value!.items.map((i) => i.id), [
          'a',
          'b',
        ]);
      },
    );
  });
}
