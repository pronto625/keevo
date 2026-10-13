import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../../domain/exception/auth_exception.dart';
import '../provider/auth_provider.dart';
import '../provider/sign_out_service.dart';

/// PasswordChangePage — Story 3.5, AC4.
///
/// Full-screen page (no bottom nav) shown when passwordChangeRequired is true.
/// No back button, no skip. Employee must set a new password.
class PasswordChangePage extends ConsumerStatefulWidget {
  const PasswordChangePage({super.key});

  @override
  ConsumerState<PasswordChangePage> createState() => _PasswordChangePageState();
}

class _PasswordChangePageState extends ConsumerState<PasswordChangePage> {
  final _formKey = GlobalKey<FormState>();
  final _currentPwdController = TextEditingController();
  final _newPwdController = TextEditingController();
  bool _obscureCurrent = true;
  bool _obscureNew = true;
  bool _signingOut = false;

  /// v1s-16-13: the forced change had no exit — signing out returns to login
  /// (with the session still there, /auth/login would send back here).
  Future<void> _signOut() async {
    setState(() => _signingOut = true);
    try {
      await ref.read(signOutServiceProvider).signOut();
    } catch (_) {
      // Best effort: every server call in signOut() tolerates being
      // offline; if the device storage itself fails, the login page is
      // shown anyway (its redirect treats unreadable storage as signed out).
    }
    if (mounted) context.go('/auth/login');
  }

  @override
  void dispose() {
    _currentPwdController.dispose();
    _newPwdController.dispose();
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
    if (value == null || value.isEmpty) {
      return 'Mot de passe requis';
    }
    if (value.length < 8) {
      return 'Au moins 8 caractères';
    }
    if (!value.contains(RegExp(r'[0-9]'))) {
      return 'Doit contenir au moins un chiffre';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final changeState = ref.watch(changePasswordProvider);

    // The splash decides the destination (an owner lands on the dashboard).
    ref.listen(changePasswordProvider, (prev, next) {
      next.whenData((tokens) {
        if (tokens != null) context.go('/splash');
      });
    });

    final isDark = theme.brightness == Brightness.dark;

    return PopScope(
      canPop: false,
      child: Scaffold(
        body: DecoratedBox(
          decoration: BoxDecoration(
            gradient: isDark
                ? AppTheme.softBackgroundGradientDark
                : AppTheme.softBackgroundGradient,
          ),
          child: SafeArea(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // ── Info icon ─────────────────────────────────────
                    const Align(
                      alignment: Alignment.centerLeft,
                      child: SoftIconBadge(
                        icon: Icons.lock_reset_rounded,
                        size: 56,
                      ),
                    ),
                    const SizedBox(height: 20),

                    // ── Title ─────────────────────────────────────────
                    Text(
                      'Créer votre mot de passe',
                      style: theme.textTheme.headlineMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                        color: cs.onSurface,
                        letterSpacing: -0.3,
                      ),
                    ),
                    const SizedBox(height: 8),

                    // ── Subtitle ──────────────────────────────────────
                    Text(
                      'Votre employeur a créé ce compte. Pour votre sécurité, '
                      'définissez votre propre mot de passe.',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: cs.onSurfaceVariant,
                        height: 1.5,
                      ),
                    ),
                    const SizedBox(height: 28),

                    // ── Current password ──────────────────────────────
                    const _FieldLabel('Mot de passe temporaire'),
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
                          onPressed: () => setState(
                              () => _obscureCurrent = !_obscureCurrent),
                        ),
                        border: _outlinedBorder(cs.outlineVariant),
                        enabledBorder: _outlinedBorder(cs.outlineVariant),
                        focusedBorder: _outlinedBorder(cs.primary, width: 1.5),
                      ),
                      validator: (v) => (v == null || v.isEmpty)
                          ? 'Mot de passe requis'
                          : null,
                    ),
                    const SizedBox(height: 16),

                    // ── New password ──────────────────────────────────
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
                        helperText:
                            'Min. 8 caractères, dont au moins un chiffre',
                      ),
                      validator: _validateNewPassword,
                    ),
                    const SizedBox(height: 24),

                    // ── Error ─────────────────────────────────────────
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

                    // ── Submit button ─────────────────────────────────
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
                                'Confirmer',
                                style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextButton.icon(
                      key: const Key('password_change_sign_out'),
                      onPressed: (_signingOut || changeState.isLoading)
                          ? null
                          : _signOut,
                      icon: const Icon(Icons.logout_rounded),
                      label: const Text('Se déconnecter'),
                    ),
                  ],
                ),
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
        'INVALID_CREDENTIALS' => 'Mot de passe temporaire incorrect',
        'VALIDATION_FAILED' =>
          'Le nouveau mot de passe ne respecte pas les critères',
        _ => 'Erreur inattendue, réessayez',
      };
    }
    if (error is ArgumentError) return error.message.toString();
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
