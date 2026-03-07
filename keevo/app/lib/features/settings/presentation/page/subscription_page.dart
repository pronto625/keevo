import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/model/subscription_info.dart';
import '../provider/subscription_info_provider.dart';

/// SubscriptionPage — Paramètres > Souscription
///
/// Shows the current plan type, status, expiry date (if any), and usage
/// counts for stores, products, and employees.
/// AC6: Users can view their current plan and usage.
class SubscriptionPage extends ConsumerWidget {
  const SubscriptionPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncSub = ref.watch(subscriptionInfoProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Souscription')),
      body: asyncSub.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, _) => Center(
          child: Text(
            'Erreur lors du chargement : $err',
            textAlign: TextAlign.center,
          ),
        ),
        data: (sub) => _SubscriptionBody(info: sub),
      ),
    );
  }
}

class _SubscriptionBody extends StatelessWidget {
  final SubscriptionInfo info;

  const _SubscriptionBody({required this.info});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Plan type ──────────────────────────────────────────────────
          Text(
            _planLabel(info.planType),
            style: theme.textTheme.headlineSmall?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            _statusLabel(info.status),
            style: TextStyle(
              color: info.status == 'ACTIVE' ? Colors.green : Colors.red,
            ),
          ),
          if (info.expiresAt != null) ...[
            const SizedBox(height: 4),
            Text('Expire le ${_formatDate(info.expiresAt!)}'),
          ],
          const SizedBox(height: 32),
          Text('Utilisation', style: theme.textTheme.titleMedium),
          const SizedBox(height: 16),

          // ── Usage rows ─────────────────────────────────────────────────
          _UsageRow(
            icon: Icons.storefront_outlined,
            label: 'boutique',
            current: info.currentStores,
            max: info.maxStores,
          ),
          const SizedBox(height: 12),
          _UsageRow(
            icon: Icons.inventory_2_outlined,
            label: 'produit',
            current: info.currentProducts,
            max: info.maxProducts,
          ),
          const SizedBox(height: 12),
          _UsageRow(
            icon: Icons.people_outline,
            label: 'employé',
            current: info.currentEmployees,
            max: info.maxEmployees,
          ),
        ],
      ),
    );
  }

  static String _planLabel(String planType) => switch (planType) {
        'FREE' => 'Plan gratuit',
        'PREMIUM_TRIAL' => 'Premium Trial',
        'PREMIUM' => 'Premium',
        _ => planType,
      };

  static String _statusLabel(String status) => switch (status) {
        'ACTIVE' => 'Actif',
        'SUSPENDED' => 'Suspendu',
        'EXPIRED' => 'Expiré',
        _ => status,
      };

  static String _formatDate(String isoDate) {
    try {
      final dt = DateTime.parse(isoDate).toLocal();
      return '${dt.day.toString().padLeft(2, '0')}/'
          '${dt.month.toString().padLeft(2, '0')}/'
          '${dt.year}';
    } catch (_) {
      return isoDate;
    }
  }
}

class _UsageRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final int current;
  final int? max; // null = unlimited

  const _UsageRow({
    required this.icon,
    required this.label,
    required this.current,
    this.max,
  });

  @override
  Widget build(BuildContext context) {
    final maxLabel = max == null ? 'illimité' : max.toString();
    final countLabel = max == null
        ? '$current $label${current > 1 ? 's' : ''}'
        : '$current/${max!} $label${max! > 1 ? 's' : ''}';

    return Row(
      children: [
        Icon(icon, size: 20, color: Colors.grey[600]),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            max == null ? '$current / $maxLabel $label' : countLabel,
          ),
        ),
      ],
    );
  }
}
