import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/providers.dart';
import '../../domain/exception/auth_exception.dart';
import '../../domain/model/membership_dto.dart';
import '../provider/auth_provider.dart';

/// TenantPickerPage — shown when the user belongs to ≥2 tenants (AC9, Story 1.7).
///
/// Displays the list of tenant memberships; selecting one calls
/// POST /auth/select-tenant → stores tokens → navigates to /pos.
class TenantPickerPage extends ConsumerStatefulWidget {
  final String loginToken;
  final List<MembershipDto> memberships;

  const TenantPickerPage({
    super.key,
    required this.loginToken,
    required this.memberships,
  });

  @override
  ConsumerState<TenantPickerPage> createState() => _TenantPickerPageState();
}

class _TenantPickerPageState extends ConsumerState<TenantPickerPage> {
  String? _selectingCode;

  Future<void> _selectTenant(MembershipDto membership) async {
    setState(() => _selectingCode = membership.tenantCode);
    // Persist role for Story 3.4 (currentUserRoleProvider)
    await ref
        .read(sharedPreferencesProvider)
        .setString(kUserRoleKey, membership.role);
    await ref.read(selectTenantProvider.notifier).select(
          loginToken: widget.loginToken,
          tenantCode: membership.tenantCode,
        );
  }

  String _errorMessage(Object? error) {
    if (error is AuthException) {
      return switch (error.domainCode) {
        'TOKEN_EXPIRED' => 'Session expirée. Reconnectez-vous.',
        'TOKEN_INVALID' => 'Session invalide. Reconnectez-vous.',
        'TENANT_NOT_FOUND' => 'Boutique introuvable.',
        _ => 'Erreur inattendue, réessayez',
      };
    }
    return 'Erreur de connexion inattendue';
  }

  @override
  Widget build(BuildContext context) {
    final asyncState = ref.watch(selectTenantProvider);
    final cs = Theme.of(context).colorScheme;

    // Navigate to /pos on successful tenant selection
    ref.listen(selectTenantProvider, (_, next) {
      next.whenData((tokens) {
        if (tokens != null) {
          if (tokens.passwordChangeRequired) {
            context.go('/auth/change-password');
          } else {
            context.go('/pos');
          }
        }
      });
    });

    final errorMessage = asyncState.hasError ? _errorMessage(asyncState.error) : null;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Choisir une boutique'),
        automaticallyImplyLeading: false,
      ),
      body: Column(
        children: [
          if (errorMessage != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
              child: Text(
                errorMessage,
                style: TextStyle(color: cs.error),
                textAlign: TextAlign.center,
              ),
            ),
          Expanded(
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: widget.memberships.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, index) {
                final m = widget.memberships[index];
                final isLoading =
                    asyncState.isLoading && _selectingCode == m.tenantCode;

                return Card(
                  child: ListTile(
                    title: Text(
                      m.tenantName,
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                    subtitle: Text(m.tenantCode),
                    trailing: isLoading
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Chip(
                            label: Text(
                              m.role,
                              style: const TextStyle(fontSize: 11),
                            ),
                            backgroundColor: cs.primaryContainer,
                          ),
                    onTap: asyncState.isLoading ? null : () => _selectTenant(m),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
