import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:keevo_core/di/core_providers.dart';
import 'package:keevo_core/module/module_sync.dart';
import 'package:keevo_core/widget/app_error_widget.dart';
import 'package:keevo_core/widget/date_range_clamp.dart';

import '../../domain/model/invoice.dart';
import '../invoicing_format.dart';
import '../provider/invoice_list_provider.dart';
import '../provider/invoicing_providers.dart';

/// Story 15.5 — « Factures » (`/invoicing`): the tenant's invoices, newest
/// first, with status chips, a search on number / client name and an issue
/// date range. Story 15.5b: read from the device (works offline; invoices
/// written offline show « En attente de synchronisation »); pulling down
/// runs a sync.
class InvoiceListPage extends ConsumerStatefulWidget {
  const InvoiceListPage({super.key});

  @override
  ConsumerState<InvoiceListPage> createState() => _InvoiceListPageState();
}

const _statusFilters = <(String?, String)>[
  (null, 'Toutes'),
  (kInvoiceDraft, 'Brouillons'),
  (kInvoiceIssued, 'Émises'),
  (kInvoicePaid, 'Payées'),
  (kInvoiceCancelled, 'Annulées'),
];

class _InvoiceListPageState extends ConsumerState<InvoiceListPage> {
  final _search = TextEditingController();
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  void _onSearchChanged(String text) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () {
      if (!mounted) return;
      ref.read(invoiceListFilterProvider.notifier).setSearch(text);
    });
    setState(() {}); // clear button
  }

  void _clearSearch() {
    _debounce?.cancel();
    _search.clear();
    ref.read(invoiceListFilterProvider.notifier).setSearch('');
    setState(() {});
  }

  Future<void> _pickRange() async {
    // Issue dates are Douala calendar days: never later than today there.
    final today = doualaToday();
    final first = DateTime(2020);
    final picked = await showDateRangePicker(
      context: context,
      firstDate: first,
      lastDate: today,
      initialDateRange: clampDateRange(
        ref.read(invoiceListFilterProvider).range,
        first,
        today,
      ),
      helpText: 'Période d\'émission',
      saveText: 'Valider',
      confirmText: 'Valider',
    );
    if (picked == null || !mounted) return;
    ref
        .read(invoiceListFilterProvider.notifier)
        .setRange(
          DateTimeRange(
            start: DateUtils.dateOnly(picked.start),
            end: DateUtils.dateOnly(picked.end),
          ),
        );
  }

  Future<void> _open(String location) async {
    // The list follows the device's copy: nothing to reload on return.
    await context.push(location);
  }

  @override
  Widget build(BuildContext context) {
    final filter = ref.watch(invoiceListFilterProvider);
    final list = ref.watch(invoiceListProvider);
    final isOwner = ref.watch(isOwnerSessionProvider);
    final range = filter.range;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Factures'),
        actions: [
          if (isOwner)
            IconButton(
              key: const Key('invoicing-settings'),
              tooltip: 'Mes informations de facturation',
              icon: const Icon(Icons.storefront_outlined),
              onPressed: () => _open('/invoicing/settings'),
            ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const Key('invoice-new'),
        onPressed: () => _open('/invoicing/new'),
        icon: const Icon(Icons.add_rounded),
        label: const Text('Nouvelle facture'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: TextField(
              key: const Key('invoice-search'),
              controller: _search,
              onChanged: _onSearchChanged,
              maxLength: 100,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: 'Rechercher un numéro ou un client',
                prefixIcon: const Icon(Icons.search_rounded),
                counterText: '',
                suffixIcon: _search.text.isEmpty
                    ? null
                    : IconButton(
                        tooltip: 'Effacer',
                        icon: const Icon(Icons.close_rounded),
                        onPressed: _clearSearch,
                      ),
              ),
            ),
          ),
          SizedBox(
            height: 52,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              children: [
                for (final (status, label) in _statusFilters)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(label),
                      selected: filter.status == status,
                      onSelected: (_) => ref
                          .read(invoiceListFilterProvider.notifier)
                          .setStatus(status),
                    ),
                  ),
                InputChip(
                  key: const Key('invoice-range'),
                  avatar: const Icon(Icons.date_range_rounded, size: 18),
                  label: Text(
                    range == null
                        ? 'Période'
                        : '${formatDay(range.start)} – ${formatDay(range.end)}',
                  ),
                  selected: range != null,
                  onPressed: _pickRange,
                  onDeleted: range == null
                      ? null
                      : () => ref
                            .read(invoiceListFilterProvider.notifier)
                            .setRange(null),
                  deleteButtonTooltipMessage: 'Retirer la période',
                ),
              ],
            ),
          ),
          Expanded(
            child: list.when(
              data: (items) => _InvoiceList(
                items: items,
                filtered: filter.isFiltered,
                onOpen: (i) => _open('/invoicing/${Uri.encodeComponent(i.id)}'),
                // A sync cycle: queued changes sent, the server's pulled.
                onRefresh: () => ref.read(moduleSyncQueueProvider).syncNow(),
              ),
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => AppErrorWidget(
                error: e,
                title: 'Impossible de charger les factures',
                message: invoicingErrorMessage(e),
                onRetry: () => ref.invalidate(localInvoicesProvider),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _InvoiceList extends StatelessWidget {
  final List<Invoice> items;
  final bool filtered;
  final void Function(Invoice) onOpen;
  final Future<void> Function() onRefresh;

  const _InvoiceList({
    required this.items,
    required this.filtered,
    required this.onOpen,
    required this.onRefresh,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (items.isEmpty) {
      return RefreshIndicator(
        onRefresh: onRefresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            const SizedBox(height: 80),
            Icon(
              Icons.receipt_long_outlined,
              size: 56,
              color: theme.colorScheme.onSurfaceVariant,
            ),
            const SizedBox(height: 12),
            Text(
              filtered ? 'Aucune facture ne correspond' : 'Aucune facture',
              textAlign: TextAlign.center,
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 4),
            Text(
              filtered
                  ? 'Modifiez la recherche ou les filtres.'
                  : 'Créez votre première facture avec « Nouvelle facture ».',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView.separated(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.only(bottom: 96),
        itemCount: items.length,
        separatorBuilder: (_, _) => const Divider(height: 1),
        itemBuilder: (context, index) {
          final invoice = items[index];
          return _InvoiceTile(invoice: invoice, onTap: () => onOpen(invoice));
        },
      ),
    );
  }
}

class _InvoiceTile extends StatelessWidget {
  final Invoice invoice;
  final VoidCallback onTap;

  const _InvoiceTile({required this.invoice, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final client = invoice.clientName;
    final date = invoice.issueDate ?? invoice.createdAt;
    return ListTile(
      onTap: onTap,
      title: Text(
        invoice.title,
        style: const TextStyle(fontWeight: FontWeight.w700),
      ),
      subtitle: Text(
        [
          [
            client == null || client.isEmpty ? 'Client non renseigné' : client,
            if (date != null) formatDay(date),
          ].join(' · '),
          // Story 15.5b — written offline, not on the server yet.
          if (invoice.pending) kPendingSyncLabel,
        ].join('\n'),
        maxLines: invoice.pending ? 2 : 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(
            formatXaf(invoice.totalTtc),
            style: theme.textTheme.bodyMedium?.copyWith(
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 4),
          InvoiceBadge(
            label: invoice.statusLabel,
            color: invoiceStatusColor(invoice.status, cs),
          ),
        ],
      ),
    );
  }
}
