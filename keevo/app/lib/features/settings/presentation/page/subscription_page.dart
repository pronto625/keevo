import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/model/subscription_info.dart';
import '../provider/subscription_info_provider.dart';

/// SubscriptionPage — Paramètres > Abonnement.
///
/// Gradient header, plan hero card, linear progress bars for usage,
/// premium CTA when on FREE plan. UX spec: Indigo Sky palette.
class SubscriptionPage extends ConsumerWidget {
  const SubscriptionPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncSub = ref.watch(subscriptionInfoProvider);

    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      body: asyncSub.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.error_outline,
                    size: 48, color: AppTheme.errorColor),
                const SizedBox(height: 12),
                const Text(
                  'Impossible de charger l\'abonnement',
                  style: TextStyle(
                      fontWeight: FontWeight.w600, fontSize: 16),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 6),
                Text(
                  '$err',
                  style:
                      const TextStyle(color: Color(0xFF868E96), fontSize: 13),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
        data: (sub) => _SubscriptionBody(info: sub),
      ),
    );
  }
}

// ── Body ─────────────────────────────────────────────────────────────────────

class _SubscriptionBody extends StatelessWidget {
  final SubscriptionInfo info;
  const _SubscriptionBody({required this.info});

  @override
  Widget build(BuildContext context) {
    final isFree = info.planType == 'FREE';

    return CustomScrollView(
      slivers: [
        // ── Gradient header ───────────────────────────────────────────────
        SliverAppBar(
          expandedHeight: 140,
          pinned: true,
          elevation: 0,
          backgroundColor: Colors.transparent,
          leading: IconButton(
            icon: const Icon(Icons.arrow_back_ios_new, color: Colors.white),
            onPressed: () => context.pop(),
          ),
          flexibleSpace: FlexibleSpaceBar(
            background: Container(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: [AppTheme.primary, AppTheme.primary.withAlpha(180)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
              ),
            ),
            title: const Text(
              'Abonnement',
              style: TextStyle(
                  color: Colors.white,
                  fontSize: 18,
                  fontWeight: FontWeight.bold),
            ),
            titlePadding: const EdgeInsets.only(left: 56, bottom: 16),
          ),
        ),

        // ── Content ───────────────────────────────────────────────────────
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 40),
          sliver: SliverList(
            delegate: SliverChildListDelegate([
              // Plan hero card
              _PlanCard(info: info),
              const SizedBox(height: 20),

              // Usage section
              const _SectionTitle('Utilisation'),
              const SizedBox(height: 10),
              _UsageCard(
                icon: Icons.storefront_rounded,
                label: 'Boutiques',
                iconColor: AppTheme.primary,
                iconBg: const Color(0xFFD0EBFF),
                current: info.currentStores,
                max: info.maxStores,
              ),
              const SizedBox(height: 12),
              _UsageCard(
                icon: Icons.inventory_2_rounded,
                label: 'Produits',
                iconColor: const Color(0xFF9B59B6),
                iconBg: const Color(0xFFF3E5F5),
                current: info.currentProducts,
                max: info.maxProducts,
              ),
              const SizedBox(height: 12),
              _UsageCard(
                icon: Icons.people_rounded,
                label: 'Employés',
                iconColor: AppTheme.success,
                iconBg: const Color(0xFFE8F8EC),
                current: info.currentEmployees,
                max: info.maxEmployees,
              ),

              // Premium CTA for FREE plan
              if (isFree) ...[
                const SizedBox(height: 24),
                const _PremiumCta(),
              ],
            ]),
          ),
        ),
      ],
    );
  }
}

// ── Plan hero card ────────────────────────────────────────────────────────────

class _PlanCard extends StatelessWidget {
  final SubscriptionInfo info;
  const _PlanCard({required this.info});

  @override
  Widget build(BuildContext context) {
    final (planLabel, planGradient, planIcon) = switch (info.planType) {
      'PREMIUM' => (
          'Premium',
          const [Color(0xFFF9A825), Color(0xFFFFCA28)],
          Icons.workspace_premium_rounded
        ),
      'PREMIUM_TRIAL' => (
          'Premium Trial',
          const [Color(0xFF3B5BDB), Color(0xFF9B59B6)],
          Icons.auto_awesome_rounded
        ),
      _ => (
          'Gratuit',
          const [Color(0xFF868E96), Color(0xFFADB5BD)],
          Icons.lock_open_rounded
        ),
    };

    final statusColor = switch (info.status) {
      'ACTIVE' => AppTheme.success,
      'SUSPENDED' => AppTheme.warning,
      _ => AppTheme.errorColor,
    };
    final statusLabel = switch (info.status) {
      'ACTIVE' => 'Actif',
      'SUSPENDED' => 'Suspendu',
      _ => 'Expiré',
    };

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        gradient: LinearGradient(
          colors: planGradient,
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        boxShadow: [
          BoxShadow(
            color: planGradient.last.withAlpha(100),
            blurRadius: 20,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Colors.white.withAlpha(40),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(planIcon, color: Colors.white, size: 24),
              ),
              const Spacer(),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: statusColor,
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  statusLabel,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Text(
            planLabel,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 22,
              fontWeight: FontWeight.bold,
            ),
          ),
          if (info.expiresAt != null) ...[
            const SizedBox(height: 4),
            Text(
              'Expire le ${_formatDate(info.expiresAt!)}',
              style: TextStyle(
                color: Colors.white.withAlpha(210),
                fontSize: 13,
              ),
            ),
          ] else ...[
            const SizedBox(height: 4),
            Text(
              info.planType == 'FREE'
                  ? 'Passez au Premium pour lever les limites'
                  : 'Aucune date d\'expiration',
              style: TextStyle(
                color: Colors.white.withAlpha(210),
                fontSize: 13,
              ),
            ),
          ],
        ],
      ),
    );
  }

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

// ── Usage card with progress bar ──────────────────────────────────────────────

class _UsageCard extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final Color iconBg;
  final String label;
  final int current;
  final int? max;

  const _UsageCard({
    required this.icon,
    required this.iconColor,
    required this.iconBg,
    required this.label,
    required this.current,
    this.max,
  });

  @override
  Widget build(BuildContext context) {
    final isUnlimited = max == null;
    final ratio = isUnlimited ? 0.0 : current / max!;
    final barColor = isUnlimited
        ? AppTheme.primary
        : ratio >= 1.0
            ? AppTheme.errorColor
            : ratio >= 0.8
                ? AppTheme.warning
                : AppTheme.primary;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(10),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: iconBg,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(icon, color: iconColor, size: 20),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      label,
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: Color(0xFF343A40),
                      ),
                    ),
                    Text(
                      isUnlimited ? '$current / ∞' : '$current / $max',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        color: barColor,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: isUnlimited ? 1.0 : ratio.clamp(0.0, 1.0),
                    minHeight: 4,
                    backgroundColor: const Color(0xFFE9ECEF),
                    valueColor: AlwaysStoppedAnimation<Color>(
                      isUnlimited ? AppTheme.success : barColor,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ── Premium CTA ───────────────────────────────────────────────────────────────

class _PremiumCta extends StatelessWidget {
  const _PremiumCta();

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        gradient: const LinearGradient(
          colors: [Color(0xFFFF6B6B), Color(0xFFFF8E53)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        boxShadow: [
          BoxShadow(
            color: const Color(0xFFFF6B6B).withAlpha(80),
            blurRadius: 16,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      padding: const EdgeInsets.all(20),
      child: Row(
        children: [
          const Icon(Icons.rocket_launch_rounded,
              color: Colors.white, size: 32),
          const SizedBox(width: 16),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Passez au Premium',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                SizedBox(height: 2),
                Text(
                  'Boutiques, produits et employés illimités',
                  style: TextStyle(
                    color: Colors.white70,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          GestureDetector(
            onTap: () => ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(
                content: Text('Mise à niveau disponible bientôt'),
                duration: Duration(seconds: 2),
              ),
            ),
            child: Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(20),
              ),
              child: const Text(
                'Voir',
                style: TextStyle(
                  color: Color(0xFFFF6B6B),
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

class _SectionTitle extends StatelessWidget {
  final String text;
  const _SectionTitle(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 4),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.8,
          color: Color(0xFF868E96),
        ),
      ),
    );
  }
}
