import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../domain/model/subscription_info.dart';
import '../provider/subscription_info_provider.dart';
import '../provider/tenant_modules_provider.dart';

/// SubscriptionPage — Paramètres > Abonnement.
///
/// Neutral header, plan hero card, linear progress bars for usage,
/// premium CTA when on FREE plan. UX spec: Indigo Sky palette.
class SubscriptionPage extends ConsumerWidget {
  const SubscriptionPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncSub = ref.watch(subscriptionInfoProvider);
    // 13.10b: a fresh subscription (after a purchase / plan change) makes the
    // cached module states stale.
    ref.listen(subscriptionInfoProvider, (_, next) {
      if (next is AsyncData) ref.invalidate(tenantModuleStatesProvider);
    });

    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerLowest,
      body: asyncSub.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, _) => SafeArea(
          child: AppErrorWidget(
            error: err,
            title: 'Impossible de charger l\'abonnement',
            onRetry: () => ref.invalidate(subscriptionInfoProvider),
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
    final cs = Theme.of(context).colorScheme;

    return CustomScrollView(
      slivers: [
        // ── Header ────────────────────────────────────────────────────────
        SliverAppBar(
          expandedHeight: 96,
          pinned: true,
          elevation: 0,
          scrolledUnderElevation: 0,
          backgroundColor: cs.surface,
          leading: IconButton(
            icon: Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
            onPressed: () => context.pop(),
          ),
          flexibleSpace: FlexibleSpaceBar(
            background: ColoredBox(color: cs.surface),
            centerTitle: false,
            title: Text(
              'Abonnement',
              style: TextStyle(
                  color: cs.onSurface,
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.2),
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
                icon: Icons.storefront_outlined,
                label: 'Boutiques',
                iconColor: cs.primary,
                current: info.currentStores,
                max: info.maxStores,
              ),
              const SizedBox(height: 12),
              _UsageCard(
                icon: Icons.inventory_2_outlined,
                label: 'Produits',
                iconColor: AppTheme.iconPurple,
                current: info.currentProducts,
                max: info.maxProducts,
              ),
              const SizedBox(height: 12),
              _UsageCard(
                icon: Icons.people_outline_rounded,
                label: 'Employés',
                iconColor: cs.success,
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
    final cs = Theme.of(context).colorScheme;
    // Carte neutre ; la couleur du plan ne vit plus que dans le badge
    // (plus de fond dégradé saturé).
    final (planLabel, planColor, planIcon) = switch (info.planType) {
      'PREMIUM' => (
          'Premium',
          cs.warningStrong,
          Icons.workspace_premium_outlined
        ),
      'PREMIUM_TRIAL' => (
          'Essai Premium',
          cs.primary,
          Icons.auto_awesome_outlined
        ),
      _ => (
          'Gratuit',
          AppTheme.iconGrey,
          Icons.lock_open_outlined
        ),
    };

    final statusColor = switch (info.status) {
      'ACTIVE' => cs.success,
      'SUSPENDED' => cs.warning,
      _ => cs.error,
    };
    final statusLabel = switch (info.status) {
      'ACTIVE' => 'Actif',
      'SUSPENDED' => 'Suspendu',
      _ => 'Expiré',
    };

    return Container(
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              SoftIconBadge(icon: planIcon, color: planColor, size: 44),
              const Spacer(),
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: statusColor.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  statusLabel,
                  style: TextStyle(
                    color: statusColor,
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
            style: TextStyle(
              color: cs.onSurface,
              fontSize: 22,
              fontWeight: FontWeight.w700,
              letterSpacing: -0.3,
            ),
          ),
          if (info.expiresAt != null) ...[
            const SizedBox(height: 4),
            Text(
              'Expire le ${_formatDate(info.expiresAt!)}',
              style: TextStyle(
                color: cs.onSurfaceVariant,
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
                color: cs.onSurfaceVariant,
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
  final String label;
  final int current;
  final int? max;

  const _UsageCard({
    required this.icon,
    required this.iconColor,
    required this.label,
    required this.current,
    this.max,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isUnlimited = max == null;
    final ratio = isUnlimited ? 0.0 : current / max!;
    final barColor = isUnlimited
        ? cs.primary
        : ratio >= 1.0
            ? cs.error
            : ratio >= 0.8
                ? cs.warning
                : cs.primary;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      child: Row(
        children: [
          SoftIconBadge(icon: icon, color: iconColor),
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
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: cs.onSurface,
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
                    backgroundColor: cs.surfaceContainerHighest,
                    valueColor: AlwaysStoppedAnimation<Color>(
                      isUnlimited ? cs.success : barColor,
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
    final cs = Theme.of(context).colorScheme;
    // Carte neutre + accent corail (badge et bouton pleins) plutôt qu'un
    // fond dégradé saturé.
    return Container(
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      padding: const EdgeInsets.all(20),
      child: Row(
        children: [
          SoftIconBadge(
            icon: Icons.rocket_launch_outlined,
            color: cs.secondary,
            size: 48,
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Passez au Premium',
                  style: TextStyle(
                    color: cs.onSurface,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  'Boutiques, produits et employés illimités',
                  style: TextStyle(
                    color: cs.onSurfaceVariant,
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
                color: cs.secondary,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Text(
                'Voir',
                style: TextStyle(
                  color: cs.onSecondary,
                  fontWeight: FontWeight.w700,
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
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.8,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}
