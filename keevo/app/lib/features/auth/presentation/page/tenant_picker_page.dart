import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../domain/exception/auth_exception.dart';
import '../../domain/model/membership_dto.dart';
import '../provider/auth_provider.dart';
import '../provider/session_claims_applier.dart';
import '../widget/previous_account_data_dialog.dart';

/// TenantPickerPage — shown when the user belongs to ≥2 tenants (AC9, Story 1.7).
///
/// Displays the list of tenant memberships; selecting one calls
/// POST /auth/select-tenant → stores tokens → navigates to /pos.
class TenantPickerPage extends ConsumerStatefulWidget {
  final String loginToken;
  final List<MembershipDto> memberships;

  const TenantPickerPage({
    super.key,
    required this.loginToken,
    required this.memberships,
  });

  @override
  ConsumerState<TenantPickerPage> createState() => _TenantPickerPageState();
}

class _TenantPickerPageState extends ConsumerState<TenantPickerPage> {
  String? _selectingCode;

  Future<void> _selectTenant(MembershipDto membership) async {
    setState(() => _selectingCode = membership.tenantCode);
    // The role is stored from the issued token once the selection succeeds.
    await ref.read(selectTenantProvider.notifier).select(
          loginToken: widget.loginToken,
          tenantCode: membership.tenantCode,
        );
  }

  String _errorMessage(Object? error) {
    if (error is AuthException) {
      return switch (error.domainCode) {
        'TOKEN_EXPIRED' => 'Session expirée. Reconnectez-vous.',
        'TOKEN_INVALID' => 'Session invalide. Reconnectez-vous.',
        'TENANT_NOT_FOUND' => 'Boutique introuvable.',
        _ => 'Erreur inattendue, réessayez',
      };
    }
    return 'Erreur de connexion inattendue';
  }

  @override
  Widget build(BuildContext context) {
    final asyncState = ref.watch(selectTenantProvider);
    final cs = Theme.of(context).colorScheme;

    // The splash decides the destination (wizard, password change, role).
    ref.listen(selectTenantProvider, (_, next) async {
      next.whenData((tokens) {
        if (tokens != null) context.go('/splash');
      });
      final error = next.error;
      if (next.hasError && error is PreviousAccountDataException) {
        // Another account's unsynced operations are still on the device.
        final notifier = ref.read(selectTenantProvider.notifier);
        final discard = await confirmDiscardPreviousAccountData(
            context, error.pendingOperations);
        if (discard) {
          await notifier.discardPreviousAccountDataAndContinue();
        } else {
          await notifier.cancelPendingSignIn();
          if (context.mounted) context.go('/auth/login');
        }
      }
    });

    final errorMessage = asyncState.hasError &&
            asyncState.error is! PreviousAccountDataException
        ? _errorMessage(asyncState.error)
        : null;

    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;

    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: isDark
              ? AppTheme.softBackgroundGradientDark
              : AppTheme.softBackgroundGradient,
        ),
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 24, 24, 8),
                child: Text(
                  'Choisir une boutique',
                  style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: cs.onSurface,
                    letterSpacing: -0.3,
                  ),
                ),
              ),
              if (errorMessage != null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 8, 24, 0),
                  child: Text(
                    errorMessage,
                    style: TextStyle(color: cs.error),
                    textAlign: TextAlign.center,
                  ),
                ),
              Expanded(
                child: ListView.separated(
                  padding: const EdgeInsets.fromLTRB(24, 16, 24, 24),
                  itemCount: widget.memberships.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (context, index) {
                    final m = widget.memberships[index];
                    final isLoading =
                        asyncState.isLoading && _selectingCode == m.tenantCode;

                    return _TenantTile(
                      membership: m,
                      isLoading: isLoading,
                      onTap:
                          asyncState.isLoading ? null : () => _selectTenant(m),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Rangée de sélection au style des tuiles de l'onboarding (SectorTile) :
/// surface neutre, bordure fine, pastille ronde à gauche.
class _TenantTile extends StatelessWidget {
  final MembershipDto membership;
  final bool isLoading;
  final VoidCallback? onTap;

  const _TenantTile({
    required this.membership,
    required this.isLoading,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final radius = BorderRadius.circular(AppTheme.radiusL);

    return DecoratedBox(
      decoration: BoxDecoration(
        color: cs.surface,
        borderRadius: radius,
        border: Border.all(color: cs.outlineVariant.withAlpha(90)),
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: radius,
        child: InkWell(
          onTap: onTap,
          borderRadius: radius,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                const SoftIconBadge(icon: Icons.storefront_outlined, size: 44),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        membership.tenantName,
                        style: theme.textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w600,
                          color: cs.onSurface,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        membership.tenantCode,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: cs.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                if (isLoading)
                  const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: AppTheme.softTint(theme.brightness),
                      borderRadius: BorderRadius.circular(AppTheme.radiusS),
                    ),
                    child: Text(
                      membership.role,
                      style: TextStyle(
                        color: cs.primary,
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0.5,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
