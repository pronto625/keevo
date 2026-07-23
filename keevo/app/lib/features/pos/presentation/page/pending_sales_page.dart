import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../domain/model/sale_model.dart';
import '../provider/pos_providers.dart';

/// PendingSalesPage — OWNER-only list of PENDING_VALIDATION sales.
/// Story 4.3 AC6.
///
/// Note v1s-15-5 (2026-07-23): currently unreachable from normal client flows
/// since the 2026-05-03 draft-sale redesign (commit 30bdd07, Story v1s-13-6).
/// Retained as manual-rescue / admin tool. See deferred-work.md v1s-15-5 Décision D1.
class PendingSalesPage extends ConsumerWidget {
  const PendingSalesPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storeId = ref.watch(activeStoreIdProvider);
    final pendingSalesAsync = ref.watch(pendingSalesProvider(storeId));
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Ventes en attente'),
        centerTitle: true,
      ),
      body: pendingSalesAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => AppErrorWidget(error: e),
        data: (sales) {
          if (sales.isEmpty) {
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.check_circle_outline,
                      size: 64, color: cs.outlineVariant),
                  const SizedBox(height: 16),
                  Text(
                    'Aucune vente en attente',
                    style: TextStyle(
                      color: cs.onSurfaceVariant,
                      fontSize: 16,
                    ),
                  ),
                ],
              ),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: sales.length,
            separatorBuilder: (_, __) => const SizedBox(height: 10),
            itemBuilder: (context, index) {
              final sale = sales[index];
              return _PendingSaleTile(
                sale: sale,
                onTap: () => context.push('/pos/pending/${sale.id}', extra: sale),
              );
            },
          );
        },
      ),
    );
  }
}

class _PendingSaleTile extends StatelessWidget {
  final Sale sale;
  final VoidCallback onTap;

  const _PendingSaleTile({required this.sale, required this.onTap});

  static final _currencyFormat =
      NumberFormat.currency(locale: 'fr_CM', symbol: 'FCFA', decimalDigits: 0);

  static final _dateFormat = DateFormat('dd/MM HH:mm');

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: AppTheme.onWarning.withOpacity(0.2)),
      ),
      color: AppTheme.warning,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppTheme.onWarning.withOpacity(0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: const Icon(Icons.pending_actions,
                    color: AppTheme.onWarning, size: 24),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _currencyFormat.format(sale.totalAmount),
                      style: const TextStyle(
                        color: AppTheme.onWarning,
                        fontWeight: FontWeight.w700,
                        fontSize: 16,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${sale.items.length} article${sale.items.length > 1 ? 's' : ''} · ${_dateFormat.format(sale.createdAt)}',
                      style: TextStyle(
                        color: AppTheme.onWarning.withOpacity(0.65),
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: AppTheme.onWarning),
            ],
          ),
        ),
      ),
    );
  }
}
