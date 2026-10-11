import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../domain/model/employee_model.dart';
import '../provider/employee_provider.dart';

/// Client-side rule, mirror of the backend one (≥ 8 chars, ≥ 1 digit).
String? validateNewPassword(String? value) {
  final v = value ?? '';
  if (v.length < 8) return 'Au moins 8 caractères';
  if (!RegExp(r'\d').hasMatch(v)) return 'Au moins un chiffre';
  return null;
}

/// Story 14.11 — "Définir le mot de passe" mini-form (AC6).
///
/// Distinct from the temporary-password regeneration: the owner types the
/// password himself, the backend never sends it back. Resolves to `true`
/// when the password was set.
Future<bool?> showSetPasswordBottomSheet({
  required BuildContext context,
  required EmployeeModel employee,
}) {
  return showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(ctx).bottom),
      child: SetPasswordSheet(employee: employee),
    ),
  );
}

class SetPasswordSheet extends ConsumerStatefulWidget {
  final EmployeeModel employee;

  const SetPasswordSheet({super.key, required this.employee});

  @override
  ConsumerState<SetPasswordSheet> createState() => _SetPasswordSheetState();
}

class _SetPasswordSheetState extends ConsumerState<SetPasswordSheet> {
  final _formKey = GlobalKey<FormState>();
  final _passwordController = TextEditingController();
  final _confirmController = TextEditingController();
  bool _obscure = true;

  @override
  void dispose() {
    _passwordController.dispose();
    _confirmController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;

    final confirmed = await showDisconnectConfirmDialog(
      context,
      title: 'Définir le mot de passe ?',
      employee: widget.employee,
    );
    if (confirmed != true || !mounted) return;

    await ref
        .read(setEmployeePasswordProvider.notifier)
        .set(widget.employee.id, _passwordController.text);
    if (!mounted) return;
    final state = ref.read(setEmployeePasswordProvider);
    if (state.hasError) return; // shown inline below the form
    Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final state = ref.watch(setEmployeePasswordProvider);
    final e = widget.employee;

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Center(
                child: SoftIconBadge(icon: Icons.password_rounded, size: 56),
              ),
              const SizedBox(height: 16),
              Text(
                'Définir le mot de passe',
                textAlign: TextAlign.center,
                style: theme.textTheme.titleLarge
                    ?.copyWith(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 4),
              Text(
                '${e.firstName} ${e.lastName} devra le changer à sa '
                'prochaine connexion.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: cs.onSurfaceVariant),
              ),
              const SizedBox(height: 20),
              TextFormField(
                controller: _passwordController,
                obscureText: _obscure,
                autofillHints: const [AutofillHints.newPassword],
                decoration: InputDecoration(
                  labelText: 'Nouveau mot de passe',
                  helperText: '8 caractères minimum, dont un chiffre',
                  prefixIcon: const Icon(Icons.lock_outline),
                  suffixIcon: IconButton(
                    tooltip: _obscure ? 'Afficher' : 'Masquer',
                    icon: Icon(_obscure
                        ? Icons.visibility_outlined
                        : Icons.visibility_off_outlined),
                    onPressed: () => setState(() => _obscure = !_obscure),
                  ),
                ),
                validator: validateNewPassword,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _confirmController,
                obscureText: _obscure,
                decoration: const InputDecoration(
                  labelText: 'Confirmer le mot de passe',
                  prefixIcon: Icon(Icons.lock_outline),
                ),
                validator: (v) => v != _passwordController.text
                    ? 'Les mots de passe ne correspondent pas'
                    : null,
              ),
              if (state.hasError) ...[
                const SizedBox(height: 8),
                AppErrorInline(error: state.error!),
              ],
              const SizedBox(height: 20),
              FilledButton(
                onPressed: state.isLoading ? null : _submit,
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(56),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(AppTheme.radiusL),
                  ),
                ),
                child: state.isLoading
                    ? SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: cs.onSurfaceVariant,
                        ),
                      )
                    : const Text('Enregistrer le mot de passe'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// AC6 — warns before any role change or password set.
Future<bool?> showDisconnectConfirmDialog(
  BuildContext context, {
  required String title,
  required EmployeeModel employee,
}) {
  return showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Text(
        '${employee.firstName} ${employee.lastName} sera déconnecté(e) et '
        'devra se reconnecter.',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx, false),
          child: const Text('Annuler'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(ctx, true),
          child: const Text('Confirmer'),
        ),
      ],
    ),
  );
}
