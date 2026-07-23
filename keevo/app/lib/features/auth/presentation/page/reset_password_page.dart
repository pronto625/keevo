import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/router/app_router.dart';
import '../../domain/exception/auth_exception.dart';
import '../provider/auth_provider.dart';

/// ResetPasswordPage — Story 14.12 — OTP code + new password screen.
///
/// User enters the 6-digit OTP code and a new password.
/// Maps domain error codes to human-readable French messages.
class ResetPasswordPage extends ConsumerStatefulWidget {
  const ResetPasswordPage({super.key});

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
    // Extract phone from route extra, fallback to login if missing
    final state = GoRouterState.of(context);
    final extra = state.extra as Map<String, dynamic>?;
    _phoneNumber = extra?['phoneNumber'] as String? ?? '';
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
      'CODE_LOCKED'             => 'Trop de tentatives, demandez un nouveau code',
      'VALIDATION_FAILED'       => 'Le mot de passe doit contenir au moins 8 caractères et 1 chiffre',
      _                         => 'Une erreur est survenue, veuillez réessayer',
    };
  }

  bool _validatePasswords() {
    final pw = _passwordController.text;
    final confirm = _confirmController.text;
    if (pw.length < 8 || !pw.contains(RegExp(r'\d'))) {
      setState(() => _errorMessage = 'Le mot de passe doit contenir au moins 8 caractères et 1 chiffre');
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

    try {
      await ref.read(resetPasswordProvider.notifier).reset(
            phoneNumber: _phoneNumber,
            // Strip ALL whitespace (internal + edges) — users often paste "123 456"
            // from SMS/WhatsApp. `.trim()` alone keeps internal spaces, which would
            // fail server-side validation (OTP is 6 contiguous digits).
            code: _codeController.text.replaceAll(RegExp(r'\s+'), ''),
            newPassword: _passwordController.text,
          );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Mot de passe réinitialisé, connectez-vous.'),
        ),
      );
      context.go('/auth/login');
    } on AuthException catch (e) {
      setState(() => _errorMessage = _computeErrorMessage(e.domainCode));
    }
  }

  @override
  Widget build(BuildContext context) {
    final resetState = ref.watch(resetPasswordProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Réinitialiser le mot de passe')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 32),
            Text(
              'Code de vérification',
              style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
            ),
            const SizedBox(height: 12),
            Text(
              'Un code à 6 chiffres a été envoyé par WhatsApp au $_phoneNumber.',
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            const SizedBox(height: 36),
            // Code field
            TextFormField(
              key: const Key('otpCodeField'),
              controller: _codeController,
              keyboardType: TextInputType.number,
              maxLength: 6,
              decoration: InputDecoration(
                labelText: 'Code de vérification',
                counterText: '',
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),
            const SizedBox(height: 16),
            // New password
            TextFormField(
              key: const Key('newPasswordField'),
              controller: _passwordController,
              obscureText: _obscurePassword,
              decoration: InputDecoration(
                labelText: 'Nouveau mot de passe',
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                suffixIcon: IconButton(
                  icon: Icon(
                    _obscurePassword ? Icons.visibility_off : Icons.visibility,
                  ),
                  onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                ),
              ),
            ),
            const SizedBox(height: 16),
            // Confirm password
            TextFormField(
              key: const Key('confirmPasswordField'),
              controller: _confirmController,
              obscureText: _obscureConfirm,
              decoration: InputDecoration(
                labelText: 'Confirmer le mot de passe',
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                suffixIcon: IconButton(
                  icon: Icon(
                    _obscureConfirm ? Icons.visibility_off : Icons.visibility,
                  ),
                  onPressed: () => setState(() => _obscureConfirm = !_obscureConfirm),
                ),
              ),
            ),
            const SizedBox(height: 24),
            if (_errorMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.errorContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  _errorMessage!,
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onErrorContainer,
                  ),
                ),
              ),
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
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Text(
                        'Réinitialiser',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
