import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/exception/auth_exception.dart';
import '../provider/auth_provider.dart';

/// ResetPasswordPage — Story 14.12 — OTP code + new password screen.
///
/// User enters the 6-digit OTP code and a new password.
/// Maps domain error codes to human-readable French messages.
class ResetPasswordPage extends ConsumerStatefulWidget {
  /// Phone the code was sent to (route extra `phoneNumber`).
  final String? phoneNumber;

  const ResetPasswordPage({super.key, this.phoneNumber});

  @override
  ConsumerState<ResetPasswordPage> createState() => _ResetPasswordPageState();
}

class _ResetPasswordPageState extends ConsumerState<ResetPasswordPage> {
  late final String _phoneNumber;
  final _codeController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmController = TextEditingController();
  bool _obscurePassword = true;
  bool _obscureConfirm = true;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    // Passed by the route builder: reading GoRouterState in initState depends
    // on an inherited widget before initState completes (framework error).
    _phoneNumber = widget.phoneNumber ?? '';
  }

  @override
  void dispose() {
    _codeController.dispose();
    _passwordController.dispose();
    _confirmController.dispose();
    super.dispose();
  }

  String _computeErrorMessage(String domainCode) {
    return switch (domainCode) {
      'INVALID_OR_EXPIRED_CODE' => 'Code invalide ou expiré',
      'CODE_LOCKED' => 'Trop de tentatives, demandez un nouveau code',
      'VALIDATION_FAILED' =>
        'Le mot de passe doit contenir au moins 8 caractères et 1 chiffre',
      _ => 'Une erreur est survenue, veuillez réessayer',
    };
  }

  bool _validatePasswords() {
    final pw = _passwordController.text;
    final confirm = _confirmController.text;
    if (pw.length < 8 || !pw.contains(RegExp(r'\d'))) {
      setState(() => _errorMessage =
          'Le mot de passe doit contenir au moins 8 caractères et 1 chiffre');
      return false;
    }
    if (pw != confirm) {
      setState(() => _errorMessage = 'Les mots de passe ne correspondent pas');
      return false;
    }
    return true;
  }

  Future<void> _submit() async {
    setState(() => _errorMessage = null);
    if (!_validatePasswords()) return;

    // The notifier stores failures in its state (AsyncValue.guard) and never
    // throws: read the outcome instead of catching (v1s-15-8 — a wrong code
    // used to show the success message and leave the page).
    await ref.read(resetPasswordProvider.notifier).reset(
          phoneNumber: _phoneNumber,
          // Strip ALL whitespace (internal + edges) — users often paste "123 456"
          // from SMS/WhatsApp. `.trim()` alone keeps internal spaces, which would
          // fail server-side validation (OTP is 6 contiguous digits).
          code: _codeController.text.replaceAll(RegExp(r'\s+'), ''),
          newPassword: _passwordController.text,
        );
    if (!mounted) return;
    final result = ref.read(resetPasswordProvider);
    if (result.hasError) {
      final error = result.error;
      setState(() => _errorMessage = _computeErrorMessage(
          error is AuthException ? error.domainCode : ''));
      return;
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Mot de passe réinitialisé, connectez-vous.'),
      ),
    );
    context.go('/auth/login');
  }

  @override
  Widget build(BuildContext context) {
    final resetState = ref.watch(resetPasswordProvider);

    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final isDark = theme.brightness == Brightness.dark;

    return Scaffold(
      appBar: AppBar(title: const Text('Réinitialiser le mot de passe')),
      extendBodyBehindAppBar: true,
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: isDark
              ? AppTheme.softBackgroundGradientDark
              : AppTheme.softBackgroundGradient,
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 16),
                Text(
                  'Code de vérification',
                  style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: cs.onSurface,
                    letterSpacing: -0.3,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Un code à 6 chiffres a été envoyé par WhatsApp au $_phoneNumber.',
                  style: theme.textTheme.bodyLarge?.copyWith(
                    color: cs.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 28),
                // Code field
                const _FieldLabel('Code de vérification'),
                const SizedBox(height: 6),
                TextFormField(
                  key: const Key('otpCodeField'),
                  controller: _codeController,
                  keyboardType: TextInputType.number,
                  // Digits first, then the 6-char limit: a pasted "123 456"
                  // used to be cut to "123 45" by maxLength (v1s-15-8).
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  maxLength: 6,
                  decoration: InputDecoration(
                    counterText: '',
                    border: _outlinedBorder(cs.outlineVariant),
                    enabledBorder: _outlinedBorder(cs.outlineVariant),
                    focusedBorder: _outlinedBorder(cs.primary, width: 1.5),
                  ),
                ),
                const SizedBox(height: 16),
                // New password
                const _FieldLabel('Nouveau mot de passe'),
                const SizedBox(height: 6),
                TextFormField(
                  key: const Key('newPasswordField'),
                  controller: _passwordController,
                  obscureText: _obscurePassword,
                  decoration: InputDecoration(
                    border: _outlinedBorder(cs.outlineVariant),
                    enabledBorder: _outlinedBorder(cs.outlineVariant),
                    focusedBorder: _outlinedBorder(cs.primary, width: 1.5),
                    suffixIcon: IconButton(
                      icon: Icon(
                        _obscurePassword
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined,
                        color: cs.onSurfaceVariant,
                      ),
                      onPressed: () =>
                          setState(() => _obscurePassword = !_obscurePassword),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                // Confirm password
                const _FieldLabel('Confirmer le mot de passe'),
                const SizedBox(height: 6),
                TextFormField(
                  key: const Key('confirmPasswordField'),
                  controller: _confirmController,
                  obscureText: _obscureConfirm,
                  decoration: InputDecoration(
                    border: _outlinedBorder(cs.outlineVariant),
                    enabledBorder: _outlinedBorder(cs.outlineVariant),
                    focusedBorder: _outlinedBorder(cs.primary, width: 1.5),
                    suffixIcon: IconButton(
                      icon: Icon(
                        _obscureConfirm
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined,
                        color: cs.onSurfaceVariant,
                      ),
                      onPressed: () =>
                          setState(() => _obscureConfirm = !_obscureConfirm),
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                if (_errorMessage != null) ...[
                  _ErrorBanner(message: _errorMessage!),
                  const SizedBox(height: 16),
                ],
                SizedBox(
                  width: double.infinity,
                  height: 56,
                  child: FilledButton(
                    key: const Key('resetButton'),
                    onPressed: resetState.isLoading ? null : _submit,
                    style: FilledButton.styleFrom(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(28),
                      ),
                    ),
                    child: resetState.isLoading
                        ? SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                            ),
                          )
                        : const Text(
                            'Réinitialiser',
                            style: TextStyle(
                                fontSize: 16, fontWeight: FontWeight.w600),
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

class _ErrorBanner extends StatelessWidget {
  final String message;
  const _ErrorBanner({required this.message});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: cs.errorContainer,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, color: cs.onErrorContainer),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(color: cs.onErrorContainer),
            ),
          ),
        ],
      ),
    );
  }
}
