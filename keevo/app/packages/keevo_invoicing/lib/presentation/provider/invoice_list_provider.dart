import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show DateTimeRange;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:keevo_core/storage/search_fold.dart';

import '../../domain/model/invoice.dart';
import 'invoicing_providers.dart';

/// Story 15.5 — criteria of the invoice list screen.
@immutable
class InvoiceListFilter {
  /// Status code (`DRAFT`…), null = every status.
  final String? status;

  /// Number or client name, as typed.
  final String search;

  /// Issue date range (calendar days, inclusive), null = no range.
  final DateTimeRange? range;

  const InvoiceListFilter({this.status, this.search = '', this.range});

  bool get isFiltered =>
      status != null || search.trim().isNotEmpty || range != null;

  InvoiceListFilter copyWith({
    Object? status = _keep,
    String? search,
    Object? range = _keep,
  }) => InvoiceListFilter(
    status: identical(status, _keep) ? this.status : status as String?,
    search: search ?? this.search,
    range: identical(range, _keep) ? this.range : range as DateTimeRange?,
  );

  @override
  bool operator ==(Object other) =>
      other is InvoiceListFilter &&
      other.status == status &&
      other.search.trim() == search.trim() &&
      other.range == range;

  @override
  int get hashCode => Object.hash(status, search.trim(), range);
}

const _keep = Object();

class InvoiceListFilterNotifier extends Notifier<InvoiceListFilter> {
  @override
  InvoiceListFilter build() => const InvoiceListFilter();

  void setStatus(String? status) => state = state.copyWith(status: status);

  void setSearch(String search) => state = state.copyWith(search: search);

  void setRange(DateTimeRange? range) => state = state.copyWith(range: range);
}

/// The list screen's criteria; reset when the screen is left.
final invoiceListFilterProvider =
    NotifierProvider.autoDispose<InvoiceListFilterNotifier, InvoiceListFilter>(
      InvoiceListFilterNotifier.new,
    );

/// Story 15.5b — the invoices of the list screen among [all] (the device's
/// copy, offline-first): [InvoiceListFilter.status]; the search on the shown
/// title (number, « Brouillon »…) or the client name (case- and
/// accent-insensitive, trimmed); the issue date range
/// (calendar days, inclusive — an invoice without issue date, a draft or one
/// issued offline, is left out by a range). Newest first (creation, then last
/// change).
List<Invoice> filterInvoices(List<Invoice> all, InvoiceListFilter filter) {
  // accents and case folded like the app's other local searches
  final search = foldForSearch(filter.search.trim());
  final range = filter.range;
  DateTime day(DateTime d) => DateTime(d.year, d.month, d.day);
  final matching = [
    for (final i in all)
      if ((filter.status == null || i.status == filter.status) &&
          (search.isEmpty ||
              foldForSearch(i.title).contains(search) ||
              foldForSearch(i.clientName ?? '').contains(search)) &&
          (range == null ||
              (i.issueDate != null &&
                  !day(i.issueDate!).isBefore(day(range.start)) &&
                  !day(i.issueDate!).isAfter(day(range.end)))))
        i,
  ];
  final epoch = DateTime.fromMillisecondsSinceEpoch(0);
  matching.sort((a, b) {
    final created = (b.createdAt ?? b.updatedAt ?? epoch).compareTo(
      a.createdAt ?? a.updatedAt ?? epoch,
    );
    if (created != 0) return created;
    return (b.updatedAt ?? epoch).compareTo(a.updatedAt ?? epoch);
  });
  return matching;
}

/// Story 15.5 — the invoice list; Story 15.5b: read from the device (every
/// invoice the sync brought, plus those written offline), filtered by
/// [invoiceListFilterProvider], updated by each local write and each pull.
final invoiceListProvider = Provider.autoDispose<AsyncValue<List<Invoice>>>((
  ref,
) {
  final filter = ref.watch(invoiceListFilterProvider);
  return ref
      .watch(localInvoicesProvider)
      .whenData((all) => filterInvoices(all, filter));
});
