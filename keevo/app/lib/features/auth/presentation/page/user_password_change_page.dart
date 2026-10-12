import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
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
              backgroundColor: AppTheme.successStrong,
              duration: const Duration(seconds: 3),
            ),
          );
        }
      });
    });

    final isDark = theme.brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: Icon(Icons.arrow_back_ios_new_rounded, color: cs.onSurface),
          onPressed: () => context.pop(),
        ),
      ),
      extendBodyBehindAppBar: true,
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: isDark
              ? AppTheme.softBackgroundGradientDark
              : AppTheme.softBackgroundGradient,
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(24, 8, 24, 40),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // ── Icon header ───────────────────────────────────────────
                  const Align(
                    alignment: Alignment.centerLeft,
                    child:
                        SoftIconBadge(icon: Icons.lock_reset_rounded, size: 48),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Changer mon mot de passe',
                    style: theme.textTheme.headlineMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface,
                      letterSpacing: -0.3,
                    ),
                  ),
                  const SizedBox(height: 24),

                  // ── Field 1: current password ─────────────────────────────
                  const _FieldLabel('Mot de passe actuel'),
                  const SizedBox(height: 6),
                  TextFormField(
                    controller: _currentPwdController,
                    obscureText: _obscureCurrent,
                    decoration: InputDecoration(
                      prefixIcon: const Icon(Icons.key_rounded),
                      suffixIcon: IconButton(
                        icon: Icon(_obscureCurrent
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined),
                        onPressed: () =>
                            setState(() => _obscureCurrent = !_obscureCurrent),
                      ),
                      border: _outlinedBorder(cs.outlineVariant),
                      enabledBorder: _outlinedBorder(cs.outlineVariant),
                      focusedBorder: _outlinedBorder(cs.primary, width: 1.5),
                    ),
                    validator: (v) => (v == null || v.isEmpty)
                        ? 'Mot de passe actuel requis'
                        : null,
                  ),
                  const SizedBox(height: 16),

                  // ── Field 2: new password ─────────────────────────────────
                  const _FieldLabel('Nouveau mot de passe'),
                  const SizedBox(height: 6),
                  TextFormField(
                    controller: _newPwdController,
                    obscureText: _obscureNew,
                    decoration: InputDecoration(
                      prefixIcon: const Icon(Icons.lock_outline),
                      suffixIcon: IconButton(
                        icon: Icon(_obscureNew
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined),
                        onPressed: () =>
                            setState(() => _obscureNew = !_obscureNew),
                      ),
                      border: _outlinedBorder(cs.outlineVariant),
                      enabledBorder: _outlinedBorder(cs.outlineVariant),
                      focusedBorder: _outlinedBorder(cs.primary, width: 1.5),
                      helperText: 'Min. 8 caractères, dont au moins un chiffre',
                    ),
                    validator: _validateNewPassword,
                  ),
                  const SizedBox(height: 16),

                  // ── Field 3: confirm password ─────────────────────────────
                  const _FieldLabel('Confirmer le nouveau mot de passe'),
                  const SizedBox(height: 6),
                  TextFormField(
                    controller: _confirmPwdController,
                    obscureText: _obscureConfirm,
                    decoration: InputDecoration(
                      prefixIcon: const Icon(Icons.lock_rounded),
                      suffixIcon: IconButton(
                        icon: Icon(_obscureConfirm
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined),
                        onPressed: () =>
                            setState(() => _obscureConfirm = !_obscureConfirm),
                      ),
                      border: _outlinedBorder(cs.outlineVariant),
                      enabledBorder: _outlinedBorder(cs.outlineVariant),
                      focusedBorder: _outlinedBorder(cs.primary, width: 1.5),
                    ),
                    validator: _validateConfirmPassword,
                  ),
                  const SizedBox(height: 24),

                  // ── Error banner (INVALID_CREDENTIALS) ────────────────────
                  if (changeState.hasError) ...[
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 16, vertical: 12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: BorderRadius.circular(10),
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
                  SizedBox(
                    height: 56,
                    child: FilledButton(
                      onPressed: changeState.isLoading ? null : _submit,
                      style: FilledButton.styleFrom(
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(28),
                        ),
                      ),
                      child: changeState.isLoading
                          ? SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Theme.of(context).colorScheme.onSurfaceVariant,
                              ),
                            )
                          : const Text(
                              'Modifier le mot de passe',
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                    ),
                  ),
                ],
              ),
            ),
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

// Même bordure fine que les champs d'AuthPage (label au-dessus du champ).
OutlineInputBorder _outlinedBorder(Color color, {double width = 1}) =>
    OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: BorderSide(color: color, width: width),
    );

class _FieldLabel extends StatelessWidget {
  final String text;
  const _FieldLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: Theme.of(context).textTheme.labelLarge?.copyWith(
            fontWeight: FontWeight.w600,
            color: Theme.of(context).colorScheme.onSurface,
          ),
    );
  }
}
