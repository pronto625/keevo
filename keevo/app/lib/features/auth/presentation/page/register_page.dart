import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:intl_phone_field/phone_number.dart';

import '../../domain/exception/auth_exception.dart';
import '../provider/auth_provider.dart';

/// RegisterPage — écran de création de compte.
///
/// Design inspiré de l'image de référence :
///  - Header avec retour + label "Inscription"
///  - Titre "Continuez vers votre boutique"
///  - Champ numéro WhatsApp avec picker pays (IntlPhoneField)
///  - Footer Conditions d'utilisation + Politique de confidentialité
///  - Bouton "Continuer"
class RegisterPage extends ConsumerStatefulWidget {
  const RegisterPage({super.key});

  @override
  ConsumerState<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends ConsumerState<RegisterPage> {
  final _formKey = GlobalKey<FormState>();
  final _passwordController = TextEditingController();
  bool _obscurePassword = true;

  /// Numéro complet avec indicatif (ex: +237600000000)
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

    await ref.read(registrationProvider.notifier).register(
          phoneNumber: _completePhone!,
          password: _passwordController.text.trim(),
        );
  }

  String? _errorMessage(AsyncValue<dynamic> asyncState) {
    if (!asyncState.hasError) return null;
    final error = asyncState.error;
    if (error is AuthException) {
      return switch (error.domainCode) {
        'USER_ALREADY_EXISTS' => 'Un compte avec ce numéro existe déjà',
        'VALIDATION_ERROR' => 'Données invalides, vérifiez vos informations',
        _ => 'Erreur inattendue, réessayez',
      };
    }
    if (error is ArgumentError) return error.message.toString();
    return 'Erreur de connexion inattendue';
  }

  @override
  Widget build(BuildContext context) {
    final asyncState = ref.watch(registrationProvider);
    final cs = Theme.of(context).colorScheme;

    ref.listen(registrationProvider, (previous, next) {
      next.whenData((result) {
        if (result != null) {
          context.go('/onboarding');
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
                    onPressed: () {
                      if (context.canPop()) {
                        context.pop();
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
                        'Inscription',
                        style: Theme.of(context)
                            .textTheme
                            .labelLarge
                            ?.copyWith(color: cs.primary),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        'Continuez vers\nvotre boutique',
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
                      Text(
                        'Numéro WhatsApp *',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              fontWeight: FontWeight.w500,
                            ),
                      ),
                      const SizedBox(height: 8),
                      IntlPhoneField(
                        key: const Key('phoneField'),
                        decoration: InputDecoration(
                          hintText: 'Entrez votre numéro',
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                            borderSide: BorderSide(
                              color: cs.outline.withOpacity(0.5),
                            ),
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                            borderSide:
                                BorderSide(color: cs.primary, width: 1.5),
                          ),
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 16,
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
                          if (!_phoneValid) {
                            return 'Numéro invalide pour ce pays';
                          }
                          return null;
                        },
                      ),

                      const SizedBox(height: 16),

                      _PasswordField(
                        controller: _passwordController,
                        obscure: _obscurePassword,
                        onToggle: () => setState(
                          () => _obscurePassword = !_obscurePassword,
                        ),
                      ),

                      const SizedBox(height: 24),

                      if (errorMessage != null) ...[
                        _ErrorBanner(message: errorMessage),
                        const SizedBox(height: 16),
                      ],

                      _SubmitButton(
                        isLoading: asyncState.isLoading,
                        onPressed: asyncState.isLoading ? null : _submit,
                      ),

                      const SizedBox(height: 16),
                      Center(
                        child: TextButton(
                          onPressed: () => context.go('/auth/login'),
                          child:
                              const Text('Déjà un compte ? Se connecter'),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),

            // ── Footer CGU ────────────────────────────────────────────
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

class _PasswordField extends StatelessWidget {
  final TextEditingController controller;
  final bool obscure;
  final VoidCallback onToggle;

  const _PasswordField({
    required this.controller,
    required this.obscure,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Mot de passe *',
          style: Theme.of(context)
              .textTheme
              .bodyMedium
              ?.copyWith(fontWeight: FontWeight.w500),
        ),
        const SizedBox(height: 8),
        TextFormField(
          key: const Key('passwordField'),
          controller: controller,
          obscureText: obscure,
          decoration: InputDecoration(
            hintText: 'Minimum 8 caractères',
            prefixIcon: const Icon(Icons.lock_outlined),
            suffixIcon: IconButton(
              icon: Icon(obscure ? Icons.visibility_off : Icons.visibility),
              onPressed: onToggle,
            ),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: cs.outline.withOpacity(0.5)),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: BorderSide(color: cs.primary, width: 1.5),
            ),
            contentPadding: const EdgeInsets.symmetric(
              horizontal: 16,
              vertical: 16,
            ),
          ),
          validator: (value) {
            if (value == null || value.trim().isEmpty) {
              return 'Le mot de passe est requis';
            }
            if (value.trim().length < 8) {
              return 'Le mot de passe doit contenir au moins 8 caractères';
            }
            return null;
          },
          textInputAction: TextInputAction.done,
        ),
      ],
    );
  }
}

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

class _SubmitButton extends StatelessWidget {
  final bool isLoading;
  final VoidCallback? onPressed;

  const _SubmitButton({required this.isLoading, required this.onPressed});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 56,
      child: FilledButton(
        key: const Key('registerButton'),
        onPressed: onPressed,
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
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
              ),
      ),
    );
  }
}
