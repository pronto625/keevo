import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl_phone_field/intl_phone_field.dart';
import 'package:intl_phone_field/phone_number.dart';

import '../../../../core/router/app_router.dart';
import '../provider/auth_provider.dart';

/// ForgotPasswordPage — Story 14.12 — Forgot password OTP request screen.
///
/// User enters their WhatsApp number, taps "Envoyer le code".
/// Always shows success (anti-enumeration like the backend).
/// Navigates to /auth/reset-password with the phone number on success.
class ForgotPasswordPage extends ConsumerStatefulWidget {
  const ForgotPasswordPage({super.key});

  @override
  ConsumerState<ForgotPasswordPage> createState() => _ForgotPasswordPageState();
}

class _ForgotPasswordPageState extends ConsumerState<ForgotPasswordPage> {
  String? _completePhone;
  bool _phoneValid = false;
  bool _isCooldown = false;
  int _cooldownSeconds = 0;

  Future<void> _submit() async {
    if (!_phoneValid || _completePhone == null || _isCooldown) return;

    setState(() {
      _isCooldown = true;
      _cooldownSeconds = 60;
    });
    _startCooldown();

    await ref.read(forgotPasswordProvider.notifier).requestReset(
          phoneNumber: _completePhone!,
        );

    if (!mounted) return;

    // Check for error (network failure, 5xx, etc.) — D1 anti-enumeration applies to
    // business logic (unknown phone), but infrastructure errors deserve user feedback.
    // Without this check, user is navigated to reset-password to enter a code that
    // was never delivered.
    final forgotState = ref.read(forgotPasswordProvider);
    if (forgotState.hasError) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            "Erreur de connexion. Vérifiez votre réseau et réessayez.",
          ),
        ),
      );
      return;
    }

    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text(
          "Si ce numéro existe, un code WhatsApp vous a été envoyé.",
        ),
      ),
    );
    context.push('/auth/reset-password', extra: {'phoneNumber': _completePhone!});
  }

  void _startCooldown() {
    Future.delayed(const Duration(seconds: 1), () {
      if (!mounted) return;
      setState(() {
        if (_cooldownSeconds > 1) {
          _cooldownSeconds--;
          _startCooldown();
        } else {
          _isCooldown = false;
        }
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final forgotState = ref.watch(forgotPasswordProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Mot de passe oublié')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 32),
            Text(
              'Réinitialiser votre mot de passe',
              style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
            ),
            const SizedBox(height: 12),
            Text(
              'Entrez votre numéro WhatsApp pour recevoir un code de réinitialisation.',
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            const SizedBox(height: 36),
            IntlPhoneField(
              key: const Key('forgotPhoneField'),
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
            ),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              height: 56,
              child: FilledButton(
                key: const Key('sendOtpButton'),
                onPressed: (forgotState.isLoading || _isCooldown) ? null : _submit,
                style: FilledButton.styleFrom(
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(28),
                  ),
                ),
                child: forgotState.isLoading
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : Text(
                        _isCooldown
                            ? 'Réessayer dans ${_cooldownSeconds}s'
                            : 'Envoyer le code',
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
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
