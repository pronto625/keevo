import 'dart:async';

import 'package:flutter/material.dart' show DateTimeRange;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:keevo_core/state/provider_retry.dart';
import 'package:keevo_invoicing/domain/model/invoice.dart';
import 'package:keevo_invoicing/presentation/provider/invoice_list_provider.dart';
import 'package:keevo_invoicing/presentation/provider/invoicing_providers.dart';

Invoice _inv(
  String id, {
  String status = kInvoiceIssued,
  String? number,
  String client = 'Client',
  DateTime? issued,
  DateTime? created,
  bool pending = false,
}) => Invoice(
  id: id,
  status: status,
  totalTtc: 1,
  number: number,
  clientName: client,
  issueDate: issued,
  createdAt: created,
  pending: pending,
);

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

  group('filterInvoices (15.5b — device copy)', () {
    final all = [
      _inv(
        'a',
        number: 'FAC-2026-000001',
        client: 'Awa Traoré',
        issued: DateTime(2026, 10, 1),
        created: DateTime(2026, 10, 1, 8),
      ),
      _inv(
        'b',
        number: 'FAC-2026-000002',
        client: 'Boutique Ndolo',
        issued: DateTime(2026, 10, 15),
        created: DateTime(2026, 10, 15, 8),
      ),
      _inv(
        'c',
        status: kInvoicePaid,
        number: 'FAC-2026-000003',
        client: 'Ets Mbarga',
        issued: DateTime(2026, 10, 31),
        created: DateTime(2026, 10, 31, 8),
      ),
      _inv(
        'd',
        status: kInvoiceDraft,
        client: 'Ndolo et fils',
        created: DateTime(2026, 11, 1, 8),
        pending: true,
      ),
      // issued offline: no number, no issue date until the sync
      _inv('e', client: 'Paul', created: DateTime(2026, 10, 20), pending: true),
    ];
    List<String> ids(InvoiceListFilter f) => [
      for (final i in filterInvoices(all, f)) i.id,
    ];

    test('no criteria: everything, newest created first', () {
      expect(ids(const InvoiceListFilter()), ['d', 'c', 'e', 'b', 'a']);
    });

    test('search: number or client name, case-insensitive, trimmed', () {
      expect(ids(const InvoiceListFilter(search: '  NDOLO ')), ['d', 'b']);
      expect(ids(const InvoiceListFilter(search: 'fac-2026-000003')), ['c']);
      expect(ids(const InvoiceListFilter(search: 'zzz')), isEmpty);
    });

    test('search folds accents and matches the shown title', () {
      final more = [
        ...all,
        _inv(
          'f',
          number: 'FAC-2026-000010',
          client: 'Société Générale',
          created: DateTime(2026, 9, 1),
        ),
      ];
      List<String> find(String q) => [
        for (final i in filterInvoices(more, InvoiceListFilter(search: q)))
          i.id,
      ];
      expect(find('SOCIETE'), ['f']);
      expect(find('brouillon'), ['d']);
      expect(find('numéro à venir'), ['e']);
    });

    test('status', () {
      expect(ids(const InvoiceListFilter(status: kInvoicePaid)), ['c']);
      expect(ids(const InvoiceListFilter(status: kInvoiceIssued)), [
        'e',
        'b',
        'a',
      ]);
    });

    test('issue date range: calendar days inclusive; no issue date → out', () {
      final range = DateTimeRange(
        start: DateTime(2026, 10, 15),
        end: DateTime(2026, 10, 31, 23, 59),
      );
      expect(ids(InvoiceListFilter(range: range)), ['c', 'b']);
    });
  });

  group('invoiceListProvider', () {
    test('follows the invoices on the device and the filter', () async {
      final local = StreamController<List<Invoice>>();
      addTearDown(local.close);
      final c = ProviderContainer(
        retry: noProviderRetry,
        overrides: [localInvoicesProvider.overrideWith((ref) => local.stream)],
      );
      addTearDown(c.dispose);
      final seen = <AsyncValue<List<Invoice>>>[];
      c.listen(
        invoiceListProvider,
        (_, v) => seen.add(v),
        fireImmediately: true,
      );
      expect(seen.last.isLoading, isTrue);

      local.add([_inv('a', client: 'Awa'), _inv('b', client: 'Bob')]);
      await Future<void>.delayed(Duration.zero);
      expect(seen.last.value!.map((i) => i.id), ['a', 'b']);

      c.read(invoiceListFilterProvider.notifier).setSearch('bob');
      await Future<void>.delayed(Duration.zero);
      expect(c.read(invoiceListProvider).value!.map((i) => i.id), ['b']);

      // a pull brought another one
      local.add([
        _inv('a', client: 'Awa'),
        _inv('b', client: 'Bob'),
        _inv('c', client: 'Bobby'),
      ]);
      await Future<void>.delayed(Duration.zero);
      expect(c.read(invoiceListProvider).value!.map((i) => i.id), ['b', 'c']);
    });
  });
}
