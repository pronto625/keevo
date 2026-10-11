import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:share_plus/share_plus.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/storage/app_database.dart';
import '../../../../core/sync/sync_monitoring_providers.dart';
import '../../../../core/sync/sync_quarantine_service.dart';
import '../../../../core/sync/sync_rejection_messages.dart';
import '../../../../core/sync/sync_rejection_policy.dart';
import '../../../../core/sync/sync_trigger_notifier.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';

/// Shares an export text and tells whether it left the device: false when
/// the share sheet was dismissed. Overridden in tests.
final quarantineExportShareProvider =
    Provider<Future<bool> Function(String text)>((ref) {
  return (text) async {
    final result =
        await Share.share(text, subject: 'Keevo — opération bloquée');
    return result.status != ShareResultStatus.dismissed;
  };
});

/// A quarantined chain as shown in the list: the head operation (the sale
/// when there is one), its chained operations, and a sale summary.
class QuarantinedChainView {
  final SyncQueueData head;
  final List<SyncQueueData> chained;
  final String? storeName;
  final int? saleTotal;
  final int lineCount;

  const QuarantinedChainView({
    required this.head,
    required this.chained,
    this.storeName,
    this.saleTotal,
    this.lineCount = 0,
  });
}

/// Quarantined operations grouped in chains (v1s-16-15), oldest first.
final quarantinedChainsProvider =
    FutureProvider.autoDispose<List<QuarantinedChainView>>((ref) async {
  final ops = await ref.watch(quarantinedSyncQueueProvider.future);
  if (ops.isEmpty) return const [];
  final service = ref.watch(syncQuarantineServiceProvider);
  final quarantinedSales = {
    for (final o in ops)
      if (o.operation == 'CREATE_SALE' && o.entityId != null) o.entityId!,
  };
  final views = <QuarantinedChainView>[];
  for (final op in ops) {
    // Shown under its sale.
    if (saleChainOperations.contains(op.operation) &&
        quarantinedSales.contains(op.entityId)) {
      continue;
    }
    final chained = op.operation == 'CREATE_SALE'
        ? ops
            .where((o) =>
                saleChainOperations.contains(o.operation) &&
                o.entityId == op.entityId)
            .toList()
        : <SyncQueueData>[];
    final payload = _payloadOf(op);
    final storeId = (payload['storeId'] ?? payload['sourceStoreId']) as String?;
    final isSale = op.operation == 'CREATE_SALE';
    views.add(QuarantinedChainView(
      head: op,
      chained: chained,
      storeName: storeId == null ? null : await service.storeLabel(storeId),
      saleTotal: isSale ? await service.saleTotal(op) : null,
      lineCount:
          isSale ? SyncQuarantineService.saleLinesOf(op).length : 0,
    ));
  }
  return views;
});

Map<String, dynamic> _payloadOf(SyncQueueData op) {
  try {
    final decoded = jsonDecode(op.payload);
    return decoded is Map<String, dynamic> ? decoded : const {};
  } catch (_) {
    return const {};
  }
}

/// "Bloquées": money operations refused for good by the server, kept on the
/// device until someone acts on them (v1s-16-15). Everyone can resend
/// (*Renvoyer*) or export (*Exporter*); the OWNER can also re-attach a sale
/// to existing products / store (*Rattacher*) or abandon it after an export
/// (*Abandonner*).
///
/// [standalone]: the section is the whole page (`/sync/blocked`) — it shows
/// an empty state and its errors. Embedded (queue tab) it disappears when
/// there is nothing to show.
class QuarantinedOperationsSection extends ConsumerWidget {
  final bool standalone;

  const QuarantinedOperationsSection({super.key, this.standalone = false});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncChains = ref.watch(quarantinedChainsProvider);
    final cs = Theme.of(context).colorScheme;

    return asyncChains.when(
      data: (chains) {
        if (chains.isEmpty) {
          return standalone
              ? const Padding(
                  padding: EdgeInsets.all(32),
                  child: Center(child: Text('Aucune opération bloquée')),
                )
              : const SizedBox.shrink();
        }
        final isOwner = ref.watch(currentUserRoleProvider) == 'OWNER';
        return Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const Icon(Icons.block_rounded,
                      size: 18, color: AppTheme.errorColor),
                  const SizedBox(width: 6),
                  Text(
                    'Bloquées (${chains.length})',
                    style: Theme.of(context)
                        .textTheme
                        .titleSmall
                        ?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                'Le serveur a refusé ces opérations. Elles sont gardées sur '
                'cet appareil mais ne sont plus envoyées : choisissez quoi en '
                'faire.',
                style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
              ),
              const SizedBox(height: 8),
              for (final chain in chains)
                _QuarantinedChainCard(chain: chain, isOwner: isOwner),
            ],
          ),
        );
      },
      loading: () => standalone
          ? const Padding(
              padding: EdgeInsets.all(32),
              child: Center(child: CircularProgressIndicator()),
            )
          : const SizedBox.shrink(),
      error: (e, _) => standalone
          ? AppErrorWidget(
              error: e,
              onRetry: () => ref.invalidate(quarantinedSyncQueueProvider),
            )
          : const SizedBox.shrink(),
    );
  }
}

class _QuarantinedChainCard extends ConsumerWidget {
  final QuarantinedChainView chain;
  final bool isOwner;

  const _QuarantinedChainCard({required this.chain, required this.isOwner});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final head = chain.head;
    final isSale = head.operation == 'CREATE_SALE';
    final date = DateFormat('dd/MM/yyyy à HH:mm').format(head.createdAt);
    final secondary = TextStyle(fontSize: 12, color: cs.onSurfaceVariant);

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppTheme.radiusM),
        side: BorderSide(color: AppTheme.errorColor.withAlpha(110)),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(friendlySyncOperationLabel(head.operation),
                style: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text('Le $date', style: secondary),
            if (isSale && chain.saleTotal != null)
              Text(
                '${chain.lineCount} article${chain.lineCount > 1 ? 's' : ''} '
                '— ${formatQuarantineAmount(chain.saleTotal!)}',
                style: secondary,
              ),
            if (chain.storeName != null)
              Text('Boutique : ${chain.storeName}', style: secondary),
            if (chain.chained.isNotEmpty)
              Text(
                'Avec : ${chain.chained.map((o) => friendlySyncOperationLabel(o.operation)).join(', ')}',
                style: secondary,
              ),
            const SizedBox(height: 4),
            Text(
              'Motif : ${friendlySyncRejectionReason(head.lastError)}',
              style: const TextStyle(
                  fontSize: 12,
                  color: AppTheme.errorColor,
                  fontWeight: FontWeight.w500),
            ),
            Wrap(
              spacing: 4,
              children: [
                TextButton.icon(
                  onPressed: () => _release(context, ref),
                  icon: const Icon(Icons.refresh_rounded, size: 18),
                  label: const Text('Renvoyer'),
                ),
                TextButton.icon(
                  onPressed: () => _export(context, ref),
                  icon: const Icon(Icons.ios_share_rounded, size: 18),
                  label: const Text('Exporter'),
                ),
                if (isOwner && isSale)
                  TextButton.icon(
                    onPressed: () => _reattach(context, ref),
                    icon: const Icon(Icons.link_rounded, size: 18),
                    label: const Text('Rattacher'),
                  ),
                if (isOwner)
                  TextButton.icon(
                    style: TextButton.styleFrom(
                        foregroundColor: AppTheme.errorColor),
                    onPressed: () => _abandon(context, ref),
                    icon: const Icon(Icons.delete_outline_rounded, size: 18),
                    label: const Text('Abandonner'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _refresh(WidgetRef ref) {
    ref.invalidate(quarantinedSyncQueueProvider);
    ref.invalidate(quarantinedSyncCountProvider);
    ref.invalidate(pendingSyncQueueProvider);
    ref.invalidate(pendingSyncCountProvider);
    ref.invalidate(syncHistoryProvider);
  }

  void _push(WidgetRef ref) {
    ref.read(syncTriggerNotifierProvider.notifier).triggerSync();
  }

  Future<void> _release(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    await ref.read(syncQuarantineServiceProvider).release(chain.head.id);
    _refresh(ref);
    _push(ref);
    messenger.showSnackBar(const SnackBar(
        content: Text('Opération renvoyée — envoi au serveur en cours')));
  }

  Future<void> _export(BuildContext context, WidgetRef ref) async {
    final text =
        await ref.read(syncQuarantineServiceProvider).exportText(chain.head.id);
    if (text.isEmpty) return;
    await ref.read(quarantineExportShareProvider)(text);
  }

  Future<void> _abandon(BuildContext context, WidgetRef ref) async {
    final isSale = chain.head.operation == 'CREATE_SALE';
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Abandonner cette opération ?'),
        content: Text(
          '${isSale ? 'Cette vente' : 'Cette opération'} ne sera jamais '
          'envoyée au serveur et sera retirée de cet appareil. '
          'Un export va d\'abord s\'ouvrir : envoyez-le (WhatsApp, e-mail…) '
          'pour en garder une trace. Si vous fermez le partage sans envoyer, '
          'rien n\'est supprimé.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Annuler'),
          ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: AppTheme.errorColor),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Exporter et abandonner'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;

    final messenger = ScaffoldMessenger.of(context);
    final service = ref.read(syncQuarantineServiceProvider);
    final text = await service.exportText(chain.head.id);
    if (text.isEmpty) return;
    final exported = await ref.read(quarantineExportShareProvider)(text);
    if (!exported) {
      messenger.showSnackBar(SnackBar(
        content: Text(isSale
            ? 'Exportez la vente avant de l\'abandonner'
            : 'Exportez l\'opération avant de l\'abandonner'),
      ));
      return;
    }
    final abandoned = await service.abandon(chain.head.id);
    _refresh(ref);
    if (abandoned) {
      messenger.showSnackBar(
          const SnackBar(content: Text('Opération abandonnée')));
    }
  }

  Future<void> _reattach(BuildContext context, WidgetRef ref) async {
    final done = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (_) => ReattachSaleSheet(chain: chain),
    );
    if (done != true) return;
    _refresh(ref);
    _push(ref);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Vente rattachée — envoi au serveur en cours')));
    }
  }
}

/// *Rattacher* (OWNER): re-points each line of a quarantined sale to a local
/// product, and/or the sale to a local store, then releases it.
class ReattachSaleSheet extends ConsumerStatefulWidget {
  final QuarantinedChainView chain;

  const ReattachSaleSheet({super.key, required this.chain});

  @override
  ConsumerState<ReattachSaleSheet> createState() => _ReattachSaleSheetState();
}

class _ReattachSaleSheetState extends ConsumerState<ReattachSaleSheet> {
  late final Future<(List<Product>, List<Store>)> _choices;
  final Map<String, Product> _products = {};
  Store? _store;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final service = ref.read(syncQuarantineServiceProvider);
    _choices = Future.wait([
      service.reattachableProducts(),
      service.reattachableStores(),
    ]).then((r) => (r[0] as List<Product>, r[1] as List<Store>));
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await ref.read(syncQuarantineServiceProvider).reattachSale(
            widget.chain.head.id,
            productIds: {
              for (final e in _products.entries) e.key: e.value.id,
            },
            storeId: _store?.id,
          );
      if (mounted) Navigator.of(context).pop(true);
    } catch (_) {
      if (mounted) {
        setState(() => _saving = false);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('Impossible de rattacher la vente, réessayez')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final lines = SyncQuarantineService.saleLinesOf(widget.chain.head)
        .where((l) => l.productId != null)
        .toList();
    // One choice per product: two lines of the same product move together.
    final seen = <String>{};
    final distinct = [
      for (final l in lines)
        if (seen.add(l.productId!)) l,
    ];

    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
            bottom: MediaQuery.of(context).viewInsets.bottom),
        child: FutureBuilder<(List<Product>, List<Store>)>(
          future: _choices,
          builder: (context, snap) {
            if (!snap.hasData) {
              return const Padding(
                padding: EdgeInsets.all(32),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            final (products, stores) = snap.data!;
            return ListView(
              shrinkWrap: true,
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
              children: [
                Text('Rattacher la vente',
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                Text(
                  'Choisissez les produits réellement vendus (et la boutique '
                  'si elle n\'existe plus), puis la vente est renvoyée.',
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
                const SizedBox(height: 12),
                for (final line in distinct)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text('${line.name} × ${line.quantity}'),
                    subtitle: Text(
                      _products[line.productId!]?.name ??
                          'Produit inchangé — touchez pour choisir',
                      style: TextStyle(
                          color: _products[line.productId!] != null
                              ? cs.primary
                              : cs.onSurfaceVariant),
                    ),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () async {
                      final picked = await showDialog<Product>(
                        context: context,
                        builder: (_) => _ProductPickerDialog(products: products),
                      );
                      if (picked != null) {
                        setState(() => _products[line.productId!] = picked);
                      }
                    },
                  ),
                const Divider(),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                      'Boutique : ${widget.chain.storeName ?? 'inconnue'}'),
                  subtitle: Text(
                    _store != null
                        ? 'Remplacée par ${_store!.name}'
                        : 'Boutique inchangée — touchez pour choisir',
                    style: TextStyle(
                        color:
                            _store != null ? cs.primary : cs.onSurfaceVariant),
                  ),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () async {
                    final picked = await showDialog<Store>(
                      context: context,
                      builder: (ctx) => SimpleDialog(
                        title: const Text('Choisir la boutique'),
                        children: [
                          if (stores.isEmpty)
                            const Padding(
                              padding: EdgeInsets.all(16),
                              child: Text('Aucune boutique disponible'),
                            ),
                          for (final s in stores)
                            SimpleDialogOption(
                              onPressed: () => Navigator.of(ctx).pop(s),
                              child: Padding(
                                padding:
                                    const EdgeInsets.symmetric(vertical: 8),
                                child: Text(s.name),
                              ),
                            ),
                        ],
                      ),
                    );
                    if (picked != null) setState(() => _store = picked);
                  },
                ),
                const SizedBox(height: 12),
                SizedBox(
                  height: 48,
                  child: FilledButton(
                    onPressed:
                        _saving || (_products.isEmpty && _store == null)
                            ? null
                            : _save,
                    child: const Text('Rattacher et renvoyer'),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _ProductPickerDialog extends StatefulWidget {
  final List<Product> products;

  const _ProductPickerDialog({required this.products});

  @override
  State<_ProductPickerDialog> createState() => _ProductPickerDialogState();
}

class _ProductPickerDialogState extends State<_ProductPickerDialog> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final q = _query.trim().toLowerCase();
    final filtered = q.isEmpty
        ? widget.products
        : widget.products
            .where((p) =>
                p.name.toLowerCase().contains(q) ||
                p.sku.toLowerCase().contains(q))
            .toList();
    return AlertDialog(
      title: const Text('Choisir le produit'),
      content: SizedBox(
        width: double.maxFinite,
        height: 360,
        child: Column(
          children: [
            TextField(
              autofocus: true,
              decoration: const InputDecoration(
                hintText: 'Rechercher un produit',
                prefixIcon: Icon(Icons.search_rounded),
              ),
              onChanged: (v) => setState(() => _query = v),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: filtered.isEmpty
                  ? const Center(child: Text('Aucun produit trouvé'))
                  : ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (_, i) => ListTile(
                        title: Text(filtered[i].name),
                        onTap: () => Navigator.of(context).pop(filtered[i]),
                      ),
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Annuler'),
        ),
      ],
    );
  }
}
