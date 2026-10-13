import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'package:intl/intl.dart';

import '../../../../core/di/providers.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../stores/presentation/provider/store_provider.dart';
import '../../domain/model/employee_activity.dart';
import '../../domain/model/employee_model.dart';
import '../provider/employee_provider.dart';
import '../util/employee_whatsapp.dart';
import 'employee_activity_sheet.dart';
import 'set_password_bottom_sheet.dart';
import 'temp_password_bottom_sheet.dart';

/// EmployeeCard — Story 3.5, AC8.
///
/// Shows employee name, store, status badge, passwordChangeRequired badge.
/// Tap shows bottom sheet with reassign/deactivate actions.
///
/// v1s-14-6 (FR67): with an [activity] (OWNER sessions only — the provider
/// feeding it is empty otherwise) an active card shows today's sales and the
/// last activity, plus a « Sans activité 48 h+ » badge when idle.
class EmployeeCard extends ConsumerWidget {
  final EmployeeModel employee;
  final VoidCallback onActionComplete;
  final EmployeeActivity? activity;

  const EmployeeCard({
    super.key,
    required this.employee,
    required this.onActionComplete,
    this.activity,
  });

  static final _currencyFmt = NumberFormat.currency(
    locale: 'fr_FR',
    symbol: 'XAF',
    decimalDigits: 0,
  );

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isActive = employee.status == 'ACTIVE';
    final storesAsync = ref.watch(storeListNotifierProvider);
    // Resolved here so the "self" check in the action sheet has a value.
    ref.watch(currentUserIdProvider);

    // Resolve store name from the loaded stores list
    final storeName = storesAsync.maybeWhen(
      data: (stores) =>
          stores.where((s) => s.id == employee.storeId).firstOrNull?.name ??
          'Boutique inconnue',
      error: (_, __) => 'Boutique inconnue',
      orElse: () => 'Chargement...',
    );

    final theme = Theme.of(context);
    final cs = theme.colorScheme;

    // Rangée de liste : surface + bordure fine plutôt qu'une Card blanche
    // figée (illisible en mode sombre).
    return Container(
      decoration: BoxDecoration(
        color: cs.surface,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        border: Border.all(color: cs.outlineVariant.withAlpha(90)),
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        child: InkWell(
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        onTap: () => _showActions(context, ref, storeName,
            resolvedStoreName: storesAsync.valueOrNull
                    ?.where((s) => s.id == employee.storeId)
                    .firstOrNull
                    ?.name ??
                ''),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              // ── Avatar ──────────────────────────────────────────────
              CircleAvatar(
                radius: 22,
                backgroundColor: isActive
                    ? AppTheme.softTint(theme.brightness)
                    : cs.surfaceContainerHighest,
                child: Text(
                  _initials(employee),
                  style: TextStyle(
                    fontWeight: FontWeight.w600,
                    color: isActive ? cs.primary : cs.onSurfaceVariant,
                  ),
                ),
              ),
              const SizedBox(width: 12),

              // ── Info ────────────────────────────────────────────────
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${employee.firstName} ${employee.lastName}',
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: isActive ? cs.onSurface : cs.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      storeName,
                      style: TextStyle(
                        fontSize: 12,
                        color: cs.onSurfaceVariant,
                      ),
                    ),
                    if (isActive && activity != null) ...[
                      const SizedBox(height: 4),
                      _ActivityStats(
                        activity: activity!,
                        currencyFmt: _currencyFmt,
                      ),
                    ],
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 6,
                      runSpacing: 4,
                      children: [
                        _StatusBadge(isActive: isActive),
                        if (employee.passwordChangeRequired)
                          const _PwdBadge(),
                        if (isActive && activity?.inactive == true)
                          const _IdleBadge(),
                      ],
                    ),
                  ],
                ),
              ),

              Icon(Icons.chevron_right_rounded,
                  color: cs.onSurfaceVariant.withAlpha(110), size: 20),
            ],
          ),
        ),
      ),
      ),
    );
  }

  // Les noms peuvent être vides (données importées) : pas d'index direct.
  static String _initials(EmployeeModel e) {
    String first(String s) => s.trim().isEmpty ? '' : s.trim()[0];
    final initials = '${first(e.firstName)}${first(e.lastName)}'.toUpperCase();
    return initials.isEmpty ? '?' : initials;
  }

  /// Lance l'action et ne signale le succès que si elle a abouti ; en cas
  /// d'échec (refus du serveur, réseau…) l'utilisateur voit la raison.
  Future<void> _runAction(
      BuildContext context, Future<void> Function() action) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await action();
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(appErrorMessage(e))));
      return;
    }
    onActionComplete();
  }

  void _openEditPage(BuildContext context) {
    context
        .push<bool>('/settings/team/${employee.id}/edit', extra: employee)
        .then((changed) {
      if (changed == true) onActionComplete();
    });
  }

  Future<void> _setPassword(BuildContext context) async {
    final done = await showSetPasswordBottomSheet(
      context: context,
      employee: employee,
    );
    if (done == true && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Mot de passe défini')),
      );
      onActionComplete();
    }
  }

  /// [resolvedStoreName] is the real store name, or '' while stores are not
  /// resolved — the only value that may travel in a WhatsApp text.
  void _showActions(BuildContext context, WidgetRef ref, String storeName,
      {required String resolvedStoreName}) {
    final isActive = employee.status == 'ACTIVE';
    final isOwner = ref.read(currentUserRoleProvider) == 'OWNER';
    // Already watched in build(), so the future has resolved by now.
    final isSelf =
        ref.read(currentUserIdProvider).valueOrNull == employee.userId;

    showModalBottomSheet<void>(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      // Up to six rows: scroll rather than overflow on small screens.
      builder: (sheetCtx) => SafeArea(
        child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Handle bar
            Container(
              margin: const EdgeInsets.only(top: 12),
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.outlineVariant,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  '${employee.firstName} ${employee.lastName}',
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontWeight: FontWeight.w700),
                ),
              ),
            ),
            const Divider(height: 1),

            // ── Activity (v1s-14-6, FR67 — OWNER only) ──────────────
            if (isOwner) ...[
              ListTile(
                leading: Icon(Icons.insights_rounded,
                    color: Theme.of(context).colorScheme.primary),
                title: const Text('Voir l\'activité'),
                subtitle: const Text('Dernière connexion, ventes'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  showEmployeeActivitySheet(
                    context: context,
                    employee: employee,
                    storeName: resolvedStoreName,
                  );
                },
              ),
              const Divider(indent: 56, height: 1),
            ],

            // ── WhatsApp invitation (v1s-14-6, FR65) — never a password ─
            // Only while the temporary password is still pending: the text
            // tells the employee to change it at first login.
            if (isActive && isOwner && !isSelf &&
                employee.passwordChangeRequired &&
                (employee.phoneNumber?.trim().isNotEmpty ?? false)) ...[
              ListTile(
                leading: Icon(Icons.chat_outlined,
                    color: Theme.of(context).colorScheme.primary),
                title: const Text('Renvoyer l\'invitation WhatsApp'),
                subtitle: const Text('Lien de l\'application et numéro de connexion'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  final phone = employee.phoneNumber!.trim();
                  openWhatsApp(
                    ScaffoldMessenger.of(context),
                    whatsAppUri(
                      phone,
                      buildInviteMessage(
                        firstName: employee.firstName,
                        storeName: resolvedStoreName,
                        phone: phone,
                      ),
                    ),
                  );
                },
              ),
              const Divider(indent: 56, height: 1),
            ],

            if (isActive && isOwner) ...[
              // ── Edit profile (Story 14.11, FR66) ────────────────────
              ListTile(
                leading: Icon(Icons.edit_outlined,
                    color: Theme.of(context).colorScheme.primary),
                title: const Text('Modifier les informations'),
                subtitle: const Text('Nom, téléphone, boutique, rôle'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  _openEditPage(context);
                },
              ),
              const Divider(indent: 56, height: 1),
            ],

            if (isActive) ...[
              // ── Reassign store ──────────────────────────────────────
              ListTile(
                leading: Icon(Icons.swap_horiz_rounded,
                    color: Theme.of(context).colorScheme.primary),
                title: const Text('Modifier l\'assignation'),
                subtitle: Text('Actuellement : $storeName'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  _showReassignDialog(context, ref);
                },
              ),
              const Divider(indent: 56, height: 1),

              // ── Regenerate password ─────────────────────────────────
              ListTile(
                leading: Icon(Icons.lock_reset_rounded,
                    color: Theme.of(context).colorScheme.warningStrong),
                title: const Text('Regénérer le mot de passe'),
                subtitle: const Text('Nouveau mot de passe temporaire'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  _confirmRegeneratePassword(context, ref);
                },
              ),
              const Divider(indent: 56, height: 1),

              // ── Set password (Story 14.11) — not for oneself ────────
              if (isOwner && !isSelf) ...[
                ListTile(
                  leading: Icon(Icons.password_rounded,
                      color: Theme.of(context).colorScheme.primary),
                  title: const Text('Définir le mot de passe'),
                  subtitle: const Text('Choisir un mot de passe vous-même'),
                  onTap: () {
                    Navigator.pop(sheetCtx);
                    _setPassword(context);
                  },
                ),
                const Divider(indent: 56, height: 1),
              ],

              // ── Deactivate ──────────────────────────────────────────
              ListTile(
                leading:
                    Icon(Icons.person_off_rounded, color: AppTheme.errorColor),
                title: const Text('Désactiver l\'accès'),
                subtitle: const Text('L\'employé ne pourra plus se connecter'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  _confirmDeactivate(context, ref);
                },
              ),
            ],

            if (!isActive) ...[
              // ── Reactivate ──────────────────────────────────────────
              ListTile(
                leading: Icon(Icons.person_add_rounded,
                    color: Theme.of(context).colorScheme.successStrong),
                title: const Text('Réactiver l\'accès'),
                subtitle: const Text('L\'employé pourra se reconnecter'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  _confirmReactivate(context, ref);
                },
              ),
              const Divider(indent: 56, height: 1),

              // ── Regenerate password (also for inactive) ─────────────
              ListTile(
                leading: Icon(Icons.lock_reset_rounded,
                    color: Theme.of(context).colorScheme.warningStrong),
                title: const Text('Regénérer le mot de passe'),
                subtitle: const Text('Nouveau mot de passe temporaire'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  _confirmRegeneratePassword(context, ref);
                },
              ),
            ],
            const SizedBox(height: 8),
          ],
        ),
        ),
      ),
    );
  }

  void _showReassignDialog(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.read(storeListNotifierProvider);
    final stores = storesAsync.valueOrNull ?? [];

    String? selectedStoreId = employee.storeId;

    showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          title: const Text('Réassigner la boutique'),
          content: DropdownButtonFormField<String>(
            initialValue: selectedStoreId,
            decoration: const InputDecoration(
              labelText: 'Boutique',
            ),
            items: stores
                .map((s) => DropdownMenuItem(
                      value: s.id,
                      child: Text(s.name),
                    ))
                .toList(),
            onChanged: (val) =>
                setDialogState(() => selectedStoreId = val),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Annuler'),
            ),
            FilledButton(
              onPressed: selectedStoreId != null &&
                      selectedStoreId != employee.storeId
                  ? () async {
                      Navigator.pop(ctx);
                      await _runAction(
                        context,
                        () => ref
                            .read(employeeRepositoryProvider)
                            .reassignStore(employee.id, selectedStoreId!),
                      );
                    }
                  : null,
              child: const Text('Confirmer'),
            ),
          ],
        ),
      ),
    );
  }

  void _confirmDeactivate(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Désactiver cet employé ?'),
        content: Text(
          '${employee.firstName} ${employee.lastName} ne pourra plus se connecter.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Annuler'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.errorStrong,
              foregroundColor: AppTheme.onBrand,
            ),
            onPressed: () async {
              Navigator.pop(ctx);
              await _runAction(
                context,
                () => ref
                    .read(employeeRepositoryProvider)
                    .deactivateEmployee(employee.id),
              );
            },
            child: const Text('Désactiver'),
          ),
        ],
      ),
    );
  }

  void _confirmReactivate(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Réactiver cet employé ?'),
        content: Text(
          '${employee.firstName} ${employee.lastName} pourra à nouveau se connecter.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Annuler'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.successStrong,
              foregroundColor: AppTheme.onBrand,
            ),
            onPressed: () async {
              Navigator.pop(ctx);
              await _runAction(
                context,
                () => ref
                    .read(employeeRepositoryProvider)
                    .reactivateEmployee(employee.id),
              );
            },
            child: const Text('Réactiver'),
          ),
        ],
      ),
    );
  }

  void _confirmRegeneratePassword(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Regénérer le mot de passe ?'),
        content: Text(
          'Un nouveau mot de passe temporaire sera créé pour '
          '${employee.firstName} ${employee.lastName}. '
          'L\'ancien mot de passe ne fonctionnera plus.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () async {
              Navigator.pop(ctx);
              try {
                final result = await ref
                    .read(employeeRepositoryProvider)
                    .regeneratePassword(employee.id);
                if (!context.mounted) return;
                final storesAsync = ref.read(storeListNotifierProvider);
                final resolved = storesAsync.valueOrNull
                    ?.where((s) => s.id == result.employee.storeId)
                    .firstOrNull
                    ?.name;
                final sName = resolved ?? 'Boutique';
                await showTempPasswordBottomSheet(
                  context: context,
                  employeeName:
                      '${result.employee.firstName} ${result.employee.lastName}',
                  storeName: sName,
                  temporaryPassword: result.temporaryPassword,
                  invitePhone:
                      result.employee.phoneNumber ?? employee.phoneNumber,
                  // Never the « Boutique » placeholder in the WhatsApp text.
                  inviteStoreName: resolved ?? '',
                  firstName: result.employee.firstName,
                );
                onActionComplete();
              } catch (_) {
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Erreur lors de la regénération')),
                );
              }
            },
            child: const Text('Regénérer'),
          ),
        ],
      ),
    );
  }
}

// ── Badge widgets ───────────────────────────────────────────────────────────

class _StatusBadge extends StatelessWidget {
  final bool isActive;
  const _StatusBadge({required this.isActive});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // Vert foncé en clair (lisible sur fond pâle), vert clair en sombre.
    final activeColor = cs.successStrong;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: isActive
            ? activeColor.withValues(alpha: 0.12)
            : cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        isActive ? 'Actif' : 'Inactif',
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: isActive ? activeColor : cs.onSurfaceVariant,
        ),
      ),
    );
  }
}

class _PwdBadge extends StatelessWidget {
  const _PwdBadge();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final color = cs.warningStrong;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        'Mot de passe non changé',
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: color,
        ),
      ),
    );
  }
}

/// « Sans activité 48 h+ » — ACTIVE employee idle for more than 48 h (FR67).
class _IdleBadge extends StatelessWidget {
  const _IdleBadge();

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.warningStrong;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        'Sans activité 48 h+',
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: color,
        ),
      ),
    );
  }
}

/// Today's sales and last activity of the card (FR67, OWNER only).
class _ActivityStats extends StatelessWidget {
  final EmployeeActivity activity;
  final NumberFormat currencyFmt;

  const _ActivityStats({required this.activity, required this.currencyFmt});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final count = activity.todaySalesCount;
    final style = TextStyle(fontSize: 12, color: cs.onSurfaceVariant);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Aujourd\'hui : $count vente${count > 1 ? 's' : ''} · '
          '${currencyFmt.format(activity.todaySalesAmount)}',
          style: style,
        ),
        Text(
          'Dernière activité : '
          '${formatLastSeen(activity.lastActivityAt, DateTime.now())}',
          style: style,
        ),
      ],
    );
  }
}
