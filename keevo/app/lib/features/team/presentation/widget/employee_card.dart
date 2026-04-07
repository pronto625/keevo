import '../../../../core/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../stores/presentation/provider/store_provider.dart';
import '../../domain/model/employee_model.dart';
import '../provider/employee_provider.dart';
import 'temp_password_bottom_sheet.dart';

/// EmployeeCard — Story 3.5, AC8.
///
/// Shows employee name, store, status badge, passwordChangeRequired badge.
/// Tap shows bottom sheet with reassign/deactivate actions.
class EmployeeCard extends ConsumerWidget {
  final EmployeeModel employee;
  final VoidCallback onActionComplete;

  const EmployeeCard({
    super.key,
    required this.employee,
    required this.onActionComplete,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isActive = employee.status == 'ACTIVE';
    final storesAsync = ref.watch(storeListNotifierProvider);

    // Resolve store name from the loaded stores list
    final storeName = storesAsync.maybeWhen(
      data: (stores) =>
          stores.where((s) => s.id == employee.storeId).firstOrNull?.name ??
          'Boutique inconnue',
      orElse: () => 'Chargement...',
    );

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      color: Colors.white,
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => _showActions(context, ref, storeName),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              // ── Avatar ──────────────────────────────────────────────
              CircleAvatar(
                radius: 22,
                backgroundColor: isActive
                    ? const Color(0xFFDBEAFE)
                    : const Color(0xFFF1F3F5),
                child: Text(
                  '${employee.firstName[0]}${employee.lastName[0]}'
                      .toUpperCase(),
                  style: TextStyle(
                    fontWeight: FontWeight.w600,
                    color: isActive
                        ? const Color(0xFF3B82F6)
                        : const Color(0xFF868E96),
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
                        color: isActive
                            ? const Color(0xFF212529)
                            : const Color(0xFF868E96),
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      storeName,
                      style: const TextStyle(
                        fontSize: 12,
                        color: Color(0xFF868E96),
                      ),
                    ),
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 6,
                      children: [
                        _StatusBadge(isActive: isActive),
                        if (employee.passwordChangeRequired)
                          const _PwdBadge(),
                      ],
                    ),
                  ],
                ),
              ),

              const Icon(Icons.chevron_right,
                  color: Color(0xFFADB5BD), size: 20),
            ],
          ),
        ),
      ),
    );
  }

  void _showActions(BuildContext context, WidgetRef ref, String storeName) {
    final isActive = employee.status == 'ACTIVE';

    showModalBottomSheet<void>(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (sheetCtx) => SafeArea(
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
                      ?.copyWith(fontWeight: FontWeight.bold),
                ),
              ),
            ),
            const Divider(height: 1),

            if (isActive) ...[
              // ── Reassign store ──────────────────────────────────────
              ListTile(
                leading: const Icon(Icons.swap_horiz_rounded,
                    color: Color(0xFF3B82F6)),
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
                leading: const Icon(Icons.lock_reset_rounded,
                    color: Color(0xFFF59E0B)),
                title: const Text('Regénérer le mot de passe'),
                subtitle: const Text('Nouveau mot de passe temporaire'),
                onTap: () {
                  Navigator.pop(sheetCtx);
                  _confirmRegeneratePassword(context, ref);
                },
              ),
              const Divider(indent: 56, height: 1),

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
                leading: const Icon(Icons.person_add_rounded,
                    color: Color(0xFF2B8A3E)),
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
                leading: const Icon(Icons.lock_reset_rounded,
                    color: Color(0xFFF59E0B)),
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
            decoration: InputDecoration(
              labelText: 'Boutique',
              border:
                  OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
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
                      await ref
                          .read(reassignStoreProvider.notifier)
                          .reassign(employee.id, selectedStoreId!);
                      onActionComplete();
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
              backgroundColor: AppTheme.errorColor,
            ),
            onPressed: () async {
              Navigator.pop(ctx);
              await ref
                  .read(deactivateEmployeeProvider.notifier)
                  .deactivate(employee.id);
              onActionComplete();
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
              backgroundColor: const Color(0xFF2B8A3E),
            ),
            onPressed: () async {
              Navigator.pop(ctx);
              await ref
                  .read(reactivateEmployeeProvider.notifier)
                  .reactivate(employee.id);
              onActionComplete();
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
                final sName = storesAsync.maybeWhen(
                  data: (stores) =>
                      stores
                          .where((s) => s.id == result.employee.storeId)
                          .firstOrNull
                          ?.name ??
                      'Boutique',
                  orElse: () => 'Boutique',
                );
                await showTempPasswordBottomSheet(
                  context: context,
                  employeeName:
                      '${result.employee.firstName} ${result.employee.lastName}',
                  storeName: sName,
                  temporaryPassword: result.temporaryPassword,
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
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: isActive ? const Color(0xFFD3F9D8) : const Color(0xFFF1F3F5),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        isActive ? 'Actif' : 'Inactif',
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: isActive ? const Color(0xFF2B8A3E) : const Color(0xFF868E96),
        ),
      ),
    );
  }
}

class _PwdBadge extends StatelessWidget {
  const _PwdBadge();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF3E0),
        borderRadius: BorderRadius.circular(20),
      ),
      child: const Text(
        'Mot de passe non changé',
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: Color(0xFFE65100),
        ),
      ),
    );
  }
}
