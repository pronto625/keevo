import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/exception/auth_exception.dart';
import '../provider/auth_provider.dart';

/// UserPasswordChangePage — Voluntary password change from Settings (Story 8.6 AC5, AC6).
///
/// Route: /settings/change-password
/// Differences from [PasswordChangePage] (Story 3.5 forced change):
///   - NO PopScope — user CAN navigate back
///   - Title: "Changer mon mot de passe" (not "Créer votre mot de passe")
///   - 3 fields: current + new + confirm (new has no 2-field limitation)
///   - On success: context.pop() + green snackbar (not context.go('/pos'))
///   - Provider reset in initState to avoid re-triggering a stale success state
class UserPasswordChangePage extends ConsumerStatefulWidget {
  const UserPasswordChangePage({super.key});

  @override
  ConsumerState<UserPasswordChangePage> createState() =>
      _UserPasswordChangePageState();
}

class _UserPasswordChangePageState
    extends ConsumerState<UserPasswordChangePage> {
  final _formKey = GlobalKey<FormState>();
  final _currentPwdController = TextEditingController();
  final _newPwdController = TextEditingController();
  final _confirmPwdController = TextEditingController();

  bool _obscureCurrent = true;
  bool _obscureNew = true;
  bool _obscureConfirm = true;

  @override
  void initState() {
    super.initState();
    // Reset provider so ref.listen fires on next success even if this page
    // was visited before in the same session (provider already in data state).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.invalidate(changePasswordProvider);
    });
  }

  @override
  void dispose() {
    _currentPwdController.dispose();
    _newPwdController.dispose();
    _confirmPwdController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;

    await ref.read(changePasswordProvider.notifier).change(
          currentPassword: _currentPwdController.text,
          newPassword: _newPwdController.text,
        );
  }

  String? _validateNewPassword(String? value) {
    if (value == null || value.isEmpty) return 'Mot de passe requis';
    if (value.length < 8) return 'Min. 8 caractères requis';
    if (!value.contains(RegExp(r'[0-9]'))) {
      return 'Doit contenir au moins un chiffre';
    }
    return null;
  }

  String? _validateConfirmPassword(String? value) {
    if (value == null || value.isEmpty) return 'Confirmation requise';
    if (value != _newPwdController.text) {
      return 'Les mots de passe ne correspondent pas';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final changeState = ref.watch(changePasswordProvider);

    // AC6: on success → pop + green snackbar
    ref.listen(changePasswordProvider, (prev, next) {
      next.whenData((tokens) {
        if (tokens != null && context.mounted) {
          // Capture messenger before pop — context is detached after navigation.
          final messenger = ScaffoldMessenger.of(context);
          context.pop();
          messenger.showSnackBar(
            SnackBar(
              content: const Text('Mot de passe modifié avec succès'),
              backgroundColor: AppTheme.success,
              duration: const Duration(seconds: 3),
            ),
          );
        }
      });
    });

    return Scaffold(
      backgroundColor: theme.scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Changer mon mot de passe'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new_rounded),
          onPressed: () => context.pop(),
        ),
        backgroundColor: AppTheme.primary,
        foregroundColor: Colors.white,
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 32, 24, 40),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // ── Icon header ───────────────────────────────────────────
              Center(
                child: Container(
                  width: 56,
                  height: 56,
                  decoration: BoxDecoration(
                    color: AppTheme.iconBlueBg,
                    borderRadius: BorderRadius.circular(28),
                  ),
                  child: const Icon(
                    Icons.lock_reset_rounded,
                    color: AppTheme.iconBlue,
                    size: 32,
                  ),
                ),
              ),
              const SizedBox(height: 28),

              // ── Field 1: current password ─────────────────────────────
              TextFormField(
                controller: _currentPwdController,
                obscureText: _obscureCurrent,
                decoration: InputDecoration(
                  labelText: 'Mot de passe actuel',
                  prefixIcon: const Icon(Icons.key_rounded),
                  suffixIcon: IconButton(
                    icon: Icon(_obscureCurrent
                        ? Icons.visibility_off_outlined
                        : Icons.visibility_outlined),
                    onPressed: () =>
                        setState(() => _obscureCurrent = !_obscureCurrent),
                  ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                validator: (v) =>
                    (v == null || v.isEmpty) ? 'Mot de passe actuel requis' : null,
              ),
              const SizedBox(height: 16),

              // ── Field 2: new password ─────────────────────────────────
              TextFormField(
                controller: _newPwdController,
                obscureText: _obscureNew,
                decoration: InputDecoration(
                  labelText: 'Nouveau mot de passe',
                  prefixIcon: const Icon(Icons.lock_outline),
                  suffixIcon: IconButton(
                    icon: Icon(_obscureNew
                        ? Icons.visibility_off_outlined
                        : Icons.visibility_outlined),
                    onPressed: () =>
                        setState(() => _obscureNew = !_obscureNew),
                  ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                  helperText: 'Min. 8 caractères, dont au moins un chiffre',
                ),
                validator: _validateNewPassword,
              ),
              const SizedBox(height: 16),

              // ── Field 3: confirm password ─────────────────────────────
              TextFormField(
                controller: _confirmPwdController,
                obscureText: _obscureConfirm,
                decoration: InputDecoration(
                  labelText: 'Confirmer le nouveau mot de passe',
                  prefixIcon: const Icon(Icons.lock_rounded),
                  suffixIcon: IconButton(
                    icon: Icon(_obscureConfirm
                        ? Icons.visibility_off_outlined
                        : Icons.visibility_outlined),
                    onPressed: () =>
                        setState(() => _obscureConfirm = !_obscureConfirm),
                  ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                validator: _validateConfirmPassword,
              ),
              const SizedBox(height: 24),

              // ── Error banner (INVALID_CREDENTIALS) ────────────────────
              if (changeState.hasError) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.error_outline,
                          color: cs.onErrorContainer, size: 20),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          _errorMessage(changeState.error),
                          style: TextStyle(color: cs.onErrorContainer),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
              ],

              // ── Submit button ─────────────────────────────────────────
              FilledButton(
                onPressed: changeState.isLoading ? null : _submit,
                style: FilledButton.styleFrom(
                  backgroundColor: AppTheme.primary,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                child: changeState.isLoading
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Text('Modifier le mot de passe'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _errorMessage(Object? error) {
    if (error is AuthException) {
      return switch (error.domainCode) {
        'INVALID_CREDENTIALS' => 'Mot de passe actuel incorrect',
        'VALIDATION_FAILED' =>
          'Le nouveau mot de passe ne respecte pas les critères',
        _ => 'Erreur inattendue, réessayez',
      };
    }
    return 'Erreur de connexion, réessayez';
  }
}
