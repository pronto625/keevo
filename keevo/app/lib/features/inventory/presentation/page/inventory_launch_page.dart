import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../stores/presentation/provider/active_store_provider.dart';
import '../provider/inventory_session_provider.dart';
import '../widget/active_session_banner.dart';
import '../widget/inventory_config_bottom_sheet.dart';
import '../widget/session_history_card.dart';

/// InventoryLaunchPage — entry point for inventory counting (Story 6.1).
///
/// Shows:
///  - Active session banner with resume/cancel (if exists)
///  - FAB to start a new session (if no active session)
///  - History of past sessions
class InventoryLaunchPage extends ConsumerWidget {
  const InventoryLaunchPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final activeStoreId = ref.watch(activeStoreIdProvider);
    final activeSessionAsync = activeStoreId != null
        ? ref.watch(activeSessionProvider(activeStoreId))
        : const AsyncValue<dynamic>.data(null);
    final historyAsync = ref.watch(sessionHistoryProvider());

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // ── Header ──
          SliverAppBar.medium(
            title: const Text('Inventaire'),
            backgroundColor: theme.colorScheme.surface,
          ),

          // ── Active session banner ──
          if (activeSessionAsync is AsyncData && activeSessionAsync.value != null)
            SliverToBoxAdapter(
              child: ActiveSessionBanner(
                session: activeSessionAsync.value!,
                onResume: () {
                  // Placeholder: navigate to counting page (Story 6.2)
                },
                onCancel: () => _confirmCancel(context, ref,
                    activeSessionAsync.value!.id),
              ),
            ),

          // ── Section title ──
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
              child: Text(
                'Historique',
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),

          // ── History list ──
          historyAsync.when(
            data: (sessions) => sessions.isEmpty
                ? SliverFillRemaining(
                    hasScrollBody: false,
                    child: Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.inventory_2_outlined,
                              size: 64,
                              color: theme.colorScheme.outline
                                  .withValues(alpha: 0.4)),
                          const SizedBox(height: 16),
                          Text(
                            'Aucun inventaire',
                            style: theme.textTheme.bodyLarge?.copyWith(
                              color: theme.colorScheme.outline,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Lancez votre premier inventaire\navec le bouton +',
                            textAlign: TextAlign.center,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.outline
                                  .withValues(alpha: 0.7),
                            ),
                          ),
                        ],
                      ),
                    ),
                  )
                : SliverList.builder(
                    itemCount: sessions.length,
                    itemBuilder: (context, index) => SessionHistoryCard(
                      session: sessions[index],
                    ),
                  ),
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => SliverFillRemaining(
              child: Center(child: Text('Erreur: $e')),
            ),
          ),
        ],
      ),
      // FAB only when no active session
      floatingActionButton:
          (activeSessionAsync is AsyncData && activeSessionAsync.value == null)
              ? FloatingActionButton.extended(
                  onPressed: () => showInventoryConfigBottomSheet(context),
                  icon: const Icon(Icons.add),
                  label: const Text('Nouvel inventaire'),
                )
              : null,
    );
  }

  void _confirmCancel(BuildContext context, WidgetRef ref, String sessionId) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Annuler l\'inventaire ?'),
        content: const Text(
            'Cette action est irréversible. La session sera marquée comme annulée.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Non'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              ref
                  .read(cancelSessionNotifierProvider.notifier)
                  .cancel(sessionId);
            },
            child: const Text('Oui, annuler'),
          ),
        ],
      ),
    );
  }
}
