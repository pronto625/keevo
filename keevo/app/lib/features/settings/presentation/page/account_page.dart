import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../../account/presentation/page/delete_account_page.dart';
import '../../../account/presentation/widget/delete_account_warning_dialog.dart';
import '../../../account/presentation/widget/deletion_pending_banner.dart';
import '../../../auth/domain/model/account_profile.dart';
import '../../../auth/presentation/provider/auth_provider.dart';

/// AccountPage — "Mon Compte" full-page profile view (Story 8.6 AC2).
///
/// Route: /settings/account
/// Shows: avatar, personal info, optional store section, security (change
/// password), and — Story 14.5 FR91 — the "Zone dangereuse" (OWNER: delete
/// my account, AC7) or the EMPLOYEE explanation (AC9). The
/// [DeletionPendingBanner] (AC8) sits above the profile: this page is where
/// the WhatsApp confirmation sends the owner to cancel.
/// Loads: accountProfileProvider (GET /api/v1/auth/profile).
class AccountPage extends ConsumerWidget {
  const AccountPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileAsync = ref.watch(accountProfileProvider);
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: cs.scaffoldBg,
      appBar: AppBar(
        title: const Text('Mon Compte'),
        titleTextStyle: TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.2,
          color: cs.onSurface,
        ),
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
          onPressed: () => context.pop(),
        ),
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: cs.surface,
        foregroundColor: cs.onSurface,
      ),
      body: profileAsync.when(
        loading: () => const _ProfileSkeleton(),
        error: (error, _) => _ErrorBanner(onRetry: () => ref.invalidate(accountProfileProvider)),
        data: (profile) => Column(
          children: [
            const DeletionPendingBanner(),
            Expanded(child: _ProfileBody(profile: profile)),
          ],
        ),
      ),
    );
  }
}

// ── Profile body ─────────────────────────────────────────────────────────────

class _ProfileBody extends StatelessWidget {
  final AccountProfile profile;
  const _ProfileBody({required this.profile});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Avatar ─────────────────────────────────────────────────────
          Center(child: _Avatar(profile: profile)),
          const SizedBox(height: 24),

          // ── Section Informations personnelles ──────────────────────────
          const _SectionLabel('Informations personnelles'),
          const SizedBox(height: 8),
          _ProfileCard(
            children: [
              _ProfileInfoRow(label: 'Prénom', value: profile.firstName ?? '—'),
              const _RowDivider(),
              _ProfileInfoRow(label: 'Nom', value: profile.lastName ?? '—'),
              const _RowDivider(),
              _ProfileInfoRow(label: 'Téléphone', value: profile.phoneNumber),
              const _RowDivider(),
              _RoleBadgeRow(role: profile.role),
            ],
          ),
          const SizedBox(height: 20),

          // ── Section Boutique (EMPLOYEE only) ──────────────────────────
          if (profile.storeId != null) ...[
            const _SectionLabel('Boutique'),
            const SizedBox(height: 8),
            _ProfileCard(
              children: [
                _ProfileInfoRow(
                  label: 'Boutique assignée',
                  value: profile.storeName ?? profile.storeId!,
                ),
              ],
            ),
            const SizedBox(height: 20),
          ],

          // ── Section Sécurité ───────────────────────────────────────────
          const _SectionLabel('Sécurité'),
          const SizedBox(height: 8),
          _ProfileCard(
            children: [
              _SecurityTile(
                icon: Icons.lock_reset_outlined,
                title: 'Changer mon mot de passe',
                onTap: () => context.push('/settings/change-password'),
              ),
            ],
          ),
          const SizedBox(height: 20),

          // ── Zone dangereuse (Story 14.5 — FR91) ────────────────────────
          if (profile.role == 'OWNER') ...[
            const _SectionLabel('Zone dangereuse'),
            const SizedBox(height: 8),
            _ProfileCard(
              children: [
                _DangerTile(
                  icon: Icons.delete_forever_outlined,
                  title: 'Supprimer mon compte',
                  subtitle: 'Suppression définitive sous 30 jours',
                  onTap: () => _startDeletion(context),
                ),
              ],
            ),
          ] else ...[
            const _SectionLabel('Suppression des données'),
            const SizedBox(height: 8),
            _ProfileCard(
              children: [_EmployeeDeletionNotice(profile: profile)],
            ),
          ],
        ],
      ),
    );
  }

  /// AC7 — step 1 (warning dialog) then step 2 (phone confirmation page).
  Future<void> _startDeletion(BuildContext context) async {
    final proceed = await showDeleteAccountWarningDialog(context);
    if (!proceed || !context.mounted) return;
    context.push('/settings/account/delete');
  }
}

// ── Danger tile (OWNER) ───────────────────────────────────────────────────────

class _DangerTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  const _DangerTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              SoftIconBadge(icon: icon, color: cs.error),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: cs.error,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: TextStyle(fontSize: 12, color: cs.muted),
                    ),
                  ],
                ),
              ),
              Icon(Icons.chevron_right_rounded, color: cs.muted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Employee notice (AC9) ─────────────────────────────────────────────────────

class _EmployeeDeletionNotice extends StatelessWidget {
  final AccountProfile profile;
  const _EmployeeDeletionNotice({required this.profile});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SoftIconBadge(icon: Icons.info_outline_rounded),
          const SizedBox(width: 14),
          Expanded(
            child: Text(
              employeeDeletionNotice(profile),
              style: TextStyle(fontSize: 13, color: cs.onSurface, height: 1.4),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Avatar ────────────────────────────────────────────────────────────────────

class _Avatar extends StatelessWidget {
  final AccountProfile profile;
  const _Avatar({required this.profile});

  String _initials() {
    final fn = profile.firstName;
    final ln = profile.lastName;
    if (fn != null && fn.isNotEmpty && ln != null && ln.isNotEmpty) {
      return '${fn[0].toUpperCase()}${ln[0].toUpperCase()}';
    }
    if (profile.phoneNumber.isNotEmpty) {
      return profile.phoneNumber[0];
    }
    return '?';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: 72,
      height: 72,
      decoration: BoxDecoration(
        color: AppTheme.softTint(theme.brightness),
        shape: BoxShape.circle,
      ),
      child: Center(
        child: Text(
          _initials(),
          style: TextStyle(
            color: theme.colorScheme.primary,
            fontSize: 26,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
    );
  }
}

// ── Profile card ──────────────────────────────────────────────────────────────

class _ProfileCard extends StatelessWidget {
  final List<Widget> children;
  const _ProfileCard({required this.children});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: cs.cardBg,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        boxShadow: AppTheme.softShadow(cs),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(children: children),
    );
  }
}

// ── Section label ─────────────────────────────────────────────────────────────

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 2),
      child: Text(
        text.toUpperCase(),
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.8,
          color: cs.muted,
        ),
      ),
    );
  }
}

// ── Profile info row ──────────────────────────────────────────────────────────

class _ProfileInfoRow extends StatelessWidget {
  final String label;
  final String value;
  const _ProfileInfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Row(
        children: [
          Expanded(
            flex: 2,
            child: Text(
              label,
              style: TextStyle(fontSize: 14, color: cs.muted),
            ),
          ),
          Expanded(
            flex: 3,
            child: Text(
              value,
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: cs.onSurface,
              ),
              textAlign: TextAlign.end,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Role badge row ────────────────────────────────────────────────────────────

class _RoleBadgeRow extends StatelessWidget {
  final String role;
  const _RoleBadgeRow({required this.role});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isOwner = role == 'OWNER';
    // Mêmes teintes (ambre = propriétaire, bleu = employé) mais résolues
    // selon le thème : les pastels opaques d'origine étaient illisibles en
    // mode sombre.
    final badgeColor =
        isOwner ? cs.warningContainer : AppTheme.softTint(cs.brightness);
    final textColor = isOwner
        ? cs.onWarningContainer
        : cs.primary;
    final label = isOwner ? 'Propriétaire' : 'Employé';

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          Expanded(
            flex: 2,
            child: Text('Rôle', style: TextStyle(fontSize: 14, color: cs.muted)),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: badgeColor,
              borderRadius: BorderRadius.circular(20),
            ),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: textColor,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Security tile ──────────────────────────────────────────────────────────────

class _SecurityTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final VoidCallback onTap;
  const _SecurityTile({
    required this.icon,
    required this.title,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              SoftIconBadge(icon: icon, color: AppTheme.iconBlue),
              const SizedBox(width: 14),
              Expanded(
                child: Text(
                  title,
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: cs.onSurface,
                  ),
                ),
              ),
              Icon(Icons.chevron_right_rounded, color: cs.muted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Row divider ────────────────────────────────────────────────────────────────

class _RowDivider extends StatelessWidget {
  const _RowDivider();

  @override
  Widget build(BuildContext context) {
    return const Divider(indent: 16, endIndent: 16, height: 1, thickness: 0.5);
  }
}

// ── Skeleton loader ────────────────────────────────────────────────────────────

class _ProfileSkeleton extends StatelessWidget {
  const _ProfileSkeleton();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final shimmerColor = cs.surfaceContainerHighest.withAlpha(120);

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 32),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: Container(
              width: 72,
              height: 72,
              decoration: BoxDecoration(
                color: shimmerColor,
                shape: BoxShape.circle,
              ),
            ),
          ),
          const SizedBox(height: 24),
          Container(height: 14, width: 140, color: shimmerColor),
          const SizedBox(height: 8),
          Container(
            height: 160,
            decoration: BoxDecoration(
              color: shimmerColor,
              borderRadius: BorderRadius.circular(AppTheme.radiusL),
            ),
          ),
          const SizedBox(height: 20),
          Container(height: 14, width: 80, color: shimmerColor),
          const SizedBox(height: 8),
          Container(
            height: 56,
            decoration: BoxDecoration(
              color: shimmerColor,
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Error banner ────────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  final VoidCallback onRetry;
  const _ErrorBanner({required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline_rounded, size: 48, color: cs.error),
            const SizedBox(height: 12),
            Text(
              'Impossible de charger le profil',
              style: TextStyle(fontSize: 15, color: cs.onSurface),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            ElevatedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Réessayer'),
            ),
          ],
        ),
      ),
    );
  }
}
