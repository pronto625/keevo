import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../domain/exception/auth_exception.dart';
import '../provider/auth_provider.dart';

/// RegisterPage — Material 3 registration form.
///
/// Allows a new user to register with phone number + password.
/// On success, navigates to the onboarding wizard.
/// On error, shows inline error banner.
class RegisterPage extends ConsumerStatefulWidget {
  const RegisterPage({super.key});

  @override
  ConsumerState<RegisterPage> createState() => _RegisterPageState();
}

class _RegisterPageState extends ConsumerState<RegisterPage> {
  final _formKey = GlobalKey<FormState>();
  final _phoneController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _obscurePassword = true;

  @override
  void dispose() {
    _phoneController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;

    await ref.read(registrationProvider.notifier).register(
          phoneNumber: _phoneController.text.trim(),
          password: _passwordController.text.trim(),
        );
  }

  /// Maps domain exceptions to user-friendly French messages.
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

    // Navigate to onboarding on successful registration (AC5).
    ref.listen(registrationProvider, (previous, next) {
      next.whenData((result) {
        if (result != null) {
          // Token already stored securely by RegisterUserUseCase.
          context.go('/onboarding');
        }
      });
    });

    final errorMessage = _errorMessage(asyncState);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Créer un compte'),
        centerTitle: true,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _Header(),
                const SizedBox(height: 32),
                _PhoneField(controller: _phoneController),
                const SizedBox(height: 16),
                _PasswordField(
                  controller: _passwordController,
                  obscure: _obscurePassword,
                  onToggle: () =>
                      setState(() => _obscurePassword = !_obscurePassword),
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
                TextButton(
                  onPressed: () => context.go('/auth/login'),
                  child: const Text('Déjà un compte ? Se connecter'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ── Private sub-widgets ──────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Icon(
          Icons.store_rounded,
          size: 64,
          color: Theme.of(context).colorScheme.primary,
        ),
        const SizedBox(height: 16),
        Text(
          'Bienvenue sur Keevo',
          style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.bold,
              ),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          'Créez votre espace de gestion en quelques secondes',
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
          textAlign: TextAlign.center,
        ),
      ],
    );
  }
}

class _PhoneField extends StatelessWidget {
  final TextEditingController controller;
  const _PhoneField({required this.controller});

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      key: const Key('phoneField'),
      controller: controller,
      keyboardType: TextInputType.phone,
      decoration: const InputDecoration(
        labelText: 'Numéro de téléphone',
        hintText: '+237 600 000 000',
        prefixIcon: Icon(Icons.phone_outlined),
        border: OutlineInputBorder(),
      ),
      validator: (value) {
        if (value == null || value.trim().isEmpty) {
          return 'Le numéro de téléphone est requis';
        }
        if (!RegExp(r'^\+?[0-9]{8,15}$').hasMatch(value.trim())) {
          return 'Format invalide (ex: +237600000000)';
        }
        return null;
      },
      textInputAction: TextInputAction.next,
    );
  }
}

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
    return TextFormField(
      key: const Key('passwordField'),
      controller: controller,
      obscureText: obscure,
      decoration: InputDecoration(
        labelText: 'Mot de passe',
        hintText: 'Minimum 8 caractères',
        prefixIcon: const Icon(Icons.lock_outlined),
        suffixIcon: IconButton(
          icon: Icon(obscure ? Icons.visibility_off : Icons.visibility),
          onPressed: onToggle,
        ),
        border: const OutlineInputBorder(),
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
        borderRadius: BorderRadius.circular(8),
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
    return FilledButton(
      key: const Key('registerButton'),
      onPressed: onPressed,
      style: FilledButton.styleFrom(
        minimumSize: const Size.fromHeight(52),
      ),
      child: isLoading
          ? const SizedBox(
              height: 20,
              width: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Text('Créer mon compte'),
    );
  }
}
