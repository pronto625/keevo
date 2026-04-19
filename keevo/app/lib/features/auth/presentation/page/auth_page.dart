import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:intl_phone_field/phone_number.dart';

import '../../../../core/di/providers.dart';
import '../../domain/exception/auth_exception.dart';
import '../../domain/model/login_result.dart';
import '../provider/auth_provider.dart';

/// Controls which flow [AuthPage] runs.
enum AuthMode { register, login }

/// AuthPage — unified register / login screen (HF-1 AC4-AC8).
///
/// A single page with a mode toggle that handles both registration (OWNER
/// account creation) and login (two-step flow with tenant selection).
///
/// - Register mode: "Inscription" label, CGU footer, navigates to /onboarding/sector.
/// - Login mode: "Connexion" label, no CGU footer, navigates based on role.
class AuthPage extends ConsumerStatefulWidget {
  final AuthMode initialMode;

  const AuthPage({super.key, this.initialMode = AuthMode.register});

  @override
  ConsumerState<AuthPage> createState() => _AuthPageState();
}

class _AuthPageState extends ConsumerState<AuthPage> {
  late AuthMode _mode;
  final _formKey = GlobalKey<FormState>();
  final _passwordController = TextEditingController();
  bool _obscurePassword = true;

  /// Full phone number with country code (e.g. +237600000000).
  String? _completePhone;
  bool _phoneValid = false;

  @override
  void initState() {
    super.initState();
    _mode = widget.initialMode;
  }

  @override
  void dispose() {
    _passwordController.dispose();
    super.dispose();
  }

  /// Switches between register and login modes, resetting form and providers.
  void _toggleMode() {
    // Clear stale errors from the provider that is being deactivated.
    ref.invalidate(registrationProvider);
    ref.invalidate(loginProvider);
    setState(() {
      _mode = _mode == AuthMode.register ? AuthMode.login : AuthMode.register;
      _formKey.currentState?.reset();
      _passwordController.clear();
      _phoneValid = false;
      _completePhone = null;
    });
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (!_phoneValid || _completePhone == null) return;

    if (_mode == AuthMode.register) {
      await ref.read(registrationProvider.notifier).register(
            phoneNumber: _completePhone!,
            password: _passwordController.text.trim(),
          );
    } else {
      await ref.read(loginProvider.notifier).login(
            phoneNumber: _completePhone!,
            password: _passwordController.text.trim(),
          );
    }
  }

  /// Maps provider error to a user-facing French message.
  String? _computeErrorMessage(AsyncValue<dynamic> asyncState) {
    if (!asyncState.hasError) return null;
    final error = asyncState.error;
    if (error is AuthException) {
      return switch (error.domainCode) {
        'USER_ALREADY_EXISTS' => 'Un compte avec ce numéro existe déjà',
        'VALIDATION_ERROR' => 'Données invalides, vérifiez vos informations',
        'INVALID_CREDENTIALS' => 'Numéro ou mot de passe incorrect',
        'ACCOUNT_LOCKED' =>
          'Compte verrouillé temporairement. Réessayez dans 15 minutes.',
        _ => 'Erreur inattendue, réessayez',
      };
    }
    if (error is ArgumentError) return error.message.toString();
    return 'Erreur de connexion inattendue';
  }

  @override
  Widget build(BuildContext context) {
    final regState = ref.watch(registrationProvider);
    final loginState = ref.watch(loginProvider);
    final asyncState = _mode == AuthMode.register ? regState : loginState;
    final isLoading = asyncState.isLoading;
    final errorMessage = _computeErrorMessage(asyncState);
    final cs = Theme.of(context).colorScheme;

    // Navigate on successful registration (guard: skip if already in login mode).
    ref.listen(registrationProvider, (_, next) {
      if (_mode != AuthMode.register) return;
      next.whenData((result) {
        if (result != null) context.go('/onboarding/sector');
      });
    });

    // Navigate on successful login (guard: skip if already in register mode).
    ref.listen(loginProvider, (_, next) {
      if (_mode != AuthMode.login) return;
      next.whenData((result) {
        if (result == null) return;
        switch (result) {
          case AuthenticatedResult(:final tokens):
            if (tokens.passwordChangeRequired) {
              context.go('/auth/change-password');
            } else {
              final prefs = ref.read(sharedPreferencesProvider);
              final role = prefs.getString(kUserRoleKey);
              context.go(role == 'OWNER' ? '/dashboard' : '/pos');
            }
          case NeedsTenantSelectionResult(:final loginToken, :final memberships):
            context.go('/tenant-picker', extra: <String, dynamic>{
              'loginToken': loginToken,
              'memberships': memberships,
            });
        }
      });
    });

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
                    onPressed: () {
                      if (context.canPop()) {
                        context.pop();
                      } else if (_mode == AuthMode.login) {
                        context.go('/auth/register');
                      } else {
                        context.go('/onboarding');
                      }
                    },
                  ),
                ],
              ),
            ),

            // ── Corps ──────────────────────────────────────────────────
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _mode == AuthMode.register ? 'Inscription' : 'Connexion',
                        style: Theme.of(context)
                            .textTheme
                            .labelLarge
                            ?.copyWith(color: cs.primary),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        _mode == AuthMode.register
                            ? 'Continuez vers\nvotre boutique'
                            : 'Connectez-vous à\nvotre boutique',
                        style: Theme.of(context)
                            .textTheme
                            .headlineMedium
                            ?.copyWith(
                              fontWeight: FontWeight.bold,
                              height: 1.2,
                            ),
                      ),
                      const SizedBox(height: 36),

                      // ── Champ numéro WhatsApp ───────────────────────
                      IntlPhoneField(
                        key: const Key('phoneField'),
                        decoration: InputDecoration(
                          labelText: 'Numéro WhatsApp',
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        initialCountryCode: 'CM',
                        languageCode: 'fr',
                        keyboardType: TextInputType.phone,
                        onChanged: (PhoneNumber phone) {
                          _completePhone = phone.completeNumber;
                          _phoneValid = true;
                        },
                        onCountryChanged: (_) {
                          _phoneValid = false;
                          _completePhone = null;
                        },
                        validator: (_) {
                          if (!_phoneValid) return 'Numéro invalide pour ce pays';
                          return null;
                        },
                      ),

                      const SizedBox(height: 16),

                      // ── Champ mot de passe ──────────────────────────
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
                              () => _obscurePassword = !_obscurePassword,
                            ),
                          ),
                        ),
                        validator: (value) {
                          if (value == null || value.trim().isEmpty) {
                            return 'Le mot de passe est requis';
                          }
                          if (_mode == AuthMode.register &&
                              value.trim().length < 8) {
                            return 'Le mot de passe doit contenir au moins 8 caractères';
                          }
                          return null;
                        },
                        textInputAction: TextInputAction.done,
                      ),

                      const SizedBox(height: 24),

                      if (errorMessage != null) ...[
                        _ErrorBanner(message: errorMessage),
                        const SizedBox(height: 16),
                      ],

                      // ── Bouton Continuer ────────────────────────────
                      SizedBox(
                        width: double.infinity,
                        height: 56,
                        child: FilledButton(
                          key: const Key('submitButton'),
                          onPressed: isLoading ? null : _submit,
                          style: FilledButton.styleFrom(
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(28),
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
                              : const Text(
                                  'Continuer',
                                  style: TextStyle(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                        ),
                      ),

                      const SizedBox(height: 16),
                      Center(
                        child: TextButton(
                          onPressed: _toggleMode,
                          child: Text(
                            _mode == AuthMode.register
                                ? 'Déjà un compte ? Se connecter'
                                : "Pas encore de compte ? S'inscrire",
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),

            // ── Footer CGU (register mode only) ───────────────────────
            if (_mode == AuthMode.register)
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 20),
                child: RichText(
                  textAlign: TextAlign.center,
                  text: TextSpan(
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: cs.onSurfaceVariant,
                        ),
                    children: [
                      const TextSpan(text: 'En continuant, vous acceptez nos '),
                      TextSpan(
                        text: "Conditions d'utilisation",
                        style: TextStyle(
                          color: cs.primary,
                          decoration: TextDecoration.underline,
                        ),
                      ),
                      const TextSpan(text: ' et notre '),
                      TextSpan(
                        text: 'Politique de confidentialité',
                        style: TextStyle(
                          color: cs.primary,
                          decoration: TextDecoration.underline,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

// ── Widgets privés ────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  final String message;
  const _ErrorBanner({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const Key('errorBanner'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Icon(
            Icons.error_outline,
            color: Theme.of(context).colorScheme.onErrorContainer,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onErrorContainer,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
