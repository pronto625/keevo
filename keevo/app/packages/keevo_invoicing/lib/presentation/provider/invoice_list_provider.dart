import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show DateTimeRange;
import 'package:flutter_riverpod/flutter_riverpod.dart';

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

/// Loaded pages of the invoice list.
@immutable
class InvoiceListState {
  final List<Invoice> items;
  final int totalElements;
  final int nextPage;
  final bool loadingMore;

  /// Error of the last « load more », shown at the bottom with a retry.
  final Object? moreError;

  const InvoiceListState({
    required this.items,
    required this.totalElements,
    required this.nextPage,
    this.loadingMore = false,
    this.moreError,
  });

  bool get hasMore => items.length < totalElements;

  InvoiceListState copyWith({
    List<Invoice>? items,
    int? totalElements,
    int? nextPage,
    bool? loadingMore,
    Object? moreError,
  }) => InvoiceListState(
    items: items ?? this.items,
    totalElements: totalElements ?? this.totalElements,
    nextPage: nextPage ?? this.nextPage,
    loadingMore: loadingMore ?? this.loadingMore,
    moreError: moreError,
  );
}

/// Page size of the invoice list.
const kInvoicePageSize = 20;

/// Story 15.5 — the invoice list (online only): first page for the current
/// [invoiceListFilterProvider], next pages with [loadMore] (infinite scroll).
class InvoiceListNotifier extends AsyncNotifier<InvoiceListState> {
  @override
  Future<InvoiceListState> build() async {
    final filter = ref.watch(invoiceListFilterProvider);
    final page = await _fetch(filter, 0);
    return InvoiceListState(
      items: page.items,
      totalElements: page.totalElements,
      nextPage: 1,
    );
  }

  Future<InvoicePage> _fetch(InvoiceListFilter filter, int page) {
    final range = filter.range;
    return ref
        .read(invoiceDataSourceProvider)
        .list(
          status: filter.status,
          search: filter.search,
          from: range?.start,
          to: range?.end,
          page: page,
          size: kInvoicePageSize,
        );
  }

  /// The criteria changed (or the list is reloading) since [filter] was used
  /// for the page that ends: its result is not for the current list.
  bool _stale(InvoiceListFilter filter, InvoiceListState loading) =>
      !ref.mounted ||
      ref.read(invoiceListFilterProvider) != filter ||
      state.isLoading ||
      !identical(state.value, loading);

  /// Loads the next page; ignored while one is loading or when everything
  /// is loaded. After a failed page, only an explicit [retry] (« Réessayer »)
  /// asks again — not every scroll tick. A result that arrives after the
  /// criteria changed is dropped.
  Future<void> loadMore({bool retry = false}) async {
    final current = state.value;
    if (current == null || current.loadingMore || !current.hasMore) return;
    if (state.isLoading) return;
    if (current.moreError != null && !retry) return;
    final filter = ref.read(invoiceListFilterProvider);
    final loading = current.copyWith(loadingMore: true);
    state = AsyncData(loading);
    try {
      final page = await _fetch(filter, current.nextPage);
      if (_stale(filter, loading)) return;
      // An invoice created meanwhile shifts the offsets: never twice.
      final known = {for (final i in current.items) i.id};
      final items = [
        ...current.items,
        for (final i in page.items)
          if (!known.contains(i.id)) i,
      ];
      state = AsyncData(
        InvoiceListState(
          items: items,
          // An empty page (invoices deleted meanwhile) ends the list.
          totalElements: page.items.isEmpty ? items.length : page.totalElements,
          nextPage: current.nextPage + 1,
        ),
      );
    } catch (e) {
      if (_stale(filter, loading)) return;
      state = AsyncData(current.copyWith(moreError: e));
    }
  }
}

final invoiceListProvider =
    AsyncNotifierProvider.autoDispose<InvoiceListNotifier, InvoiceListState>(
      InvoiceListNotifier.new,
    );
