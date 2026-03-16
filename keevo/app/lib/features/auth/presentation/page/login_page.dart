import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';

import '../../domain/exception/auth_exception.dart';
import '../../domain/model/login_result.dart';
import '../provider/auth_provider.dart';

/// LoginPage — écran d'authentification.
///
/// AC1: phone + password form → calls loginProvider → navigates to /home on success.
/// AC4: displays lockout error message when ACCOUNT_LOCKED received.
/// Mirrors design of RegisterPage.
class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _formKey = GlobalKey<FormState>();
  final _passwordController = TextEditingController();
  bool _obscurePassword = true;

  /// Complete phone number with country code (e.g. +237600000000)
  String? _completePhone;
  bool _phoneValid = false;

  @override
  void dispose() {
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (!_phoneValid || _completePhone == null) return;

    await ref.read(loginProvider.notifier).login(
          phoneNumber: _completePhone!,
          password: _passwordController.text,
        );
  }

  /// Maps an [AuthException] domain code to a user-facing message.
  String? _errorMessage(AsyncValue<dynamic> asyncState) {
    if (!asyncState.hasError) return null;
    final error = asyncState.error;
    if (error is AuthException) {
      return switch (error.domainCode) {
        'INVALID_CREDENTIALS' => 'Numéro ou mot de passe incorrect',
        'ACCOUNT_LOCKED' =>
          'Compte verrouillé temporairement. Réessayez dans 15 minutes.',
        _ => 'Erreur inattendue, réessayez',
      };
    }
    return 'Erreur de connexion inattendue';
  }

  @override
  Widget build(BuildContext context) {
    final asyncState = ref.watch(loginProvider);
    final cs = Theme.of(context).colorScheme;
    final isLoading = asyncState.isLoading;

    // Navigate on successful login — handles both single and multi-membership
    ref.listen(loginProvider, (previous, next) {
      next.whenData((result) {
        if (result == null) return;
        switch (result) {
          case AuthenticatedResult(:final tokens):
            if (tokens.passwordChangeRequired) {
              context.go('/auth/change-password');
            } else {
              context.go('/pos');
            }
          case NeedsTenantSelectionResult(:final loginToken, :final memberships):
            // AC9: multiple tenants — navigate to picker
            context.go('/tenant-picker', extra: <String, dynamic>{
              'loginToken': loginToken,
              'memberships': memberships,
            });
        }
      });
    });

    final errorMessage = _errorMessage(asyncState);

    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      body: SafeArea(
        child: Column(
          children: [
            // ── Header ─────────────────────────────────────────────────
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
              child: Row(
                children: [
                  IconButton(
                    icon: Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: cs.surfaceContainerHighest,
                      ),
                      child: Icon(
                        Icons.arrow_back,
                        size: 20,
                        color: cs.onSurface,
                      ),
                    ),
                    onPressed: () =>
                        context.canPop() ? context.pop() : context.go('/auth/register'),
                  ),
                  const SizedBox(width: 12),
                  Text(
                    'Connexion',
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(fontWeight: FontWeight.w600),
                  ),
                ],
              ),
            ),

            // ── Body ───────────────────────────────────────────────────
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const SizedBox(height: 24),

                      // Title
                      Text(
                        'Connectez-vous à votre boutique',
                        style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Entrez votre numéro WhatsApp et votre mot de passe.',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: cs.onSurfaceVariant,
                            ),
                      ),
                      const SizedBox(height: 32),

                      // Phone field
                      IntlPhoneField(
                        key: const Key('phoneField'),
                        decoration: InputDecoration(
                          labelText: 'Numéro WhatsApp',
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        initialCountryCode: 'CM',
                        onChanged: (phone) {
                          setState(() {
                            _completePhone = phone.completeNumber;
                            _phoneValid = phone.number.length >= 6;
                          });
                        },
                        validator: (_) =>
                            _phoneValid ? null : 'Numéro invalide',
                      ),
                      const SizedBox(height: 16),

                      // Password field
                      TextFormField(
                        key: const Key('passwordField'),
                        controller: _passwordController,
                        obscureText: _obscurePassword,
                        decoration: InputDecoration(
                          labelText: 'Mot de passe',
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                          suffixIcon: IconButton(
                            icon: Icon(
                              _obscurePassword
                                  ? Icons.visibility_off
                                  : Icons.visibility,
                            ),
                            onPressed: () => setState(
                                () => _obscurePassword = !_obscurePassword),
                          ),
                        ),
                        validator: (v) => (v == null || v.isEmpty)
                            ? 'Mot de passe requis'
                            : null,
                      ),
                      const SizedBox(height: 24),

                      // Error message
                      if (errorMessage != null) ...[
                        Container(
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: cs.errorContainer,
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Row(
                            children: [
                              Icon(Icons.error_outline,
                                  color: cs.onErrorContainer, size: 20),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  errorMessage,
                                  style: TextStyle(color: cs.onErrorContainer),
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 16),
                      ],

                      // Submit button
                      FilledButton(
                        onPressed: isLoading ? null : _submit,
                        style: FilledButton.styleFrom(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        child: isLoading
                            ? const SizedBox(
                                height: 20,
                                width: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white,
                                ),
                              )
                            : const Text('Continuer'),
                      ),

                      // Link to registration screen
                      const SizedBox(height: 8),
                      Center(
                        child: TextButton(
                          onPressed: () => context.go('/auth/register'),
                          child: const Text("Pas encore de compte ? S'inscrire"),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
