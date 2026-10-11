import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/widget/app_error_widget.dart';

import '../../../stores/presentation/provider/active_store_provider.dart';
import '../../../stores/presentation/provider/store_provider.dart';
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
    final storesAsync = ref.watch(storeListNotifierProvider);
    final storeMap = <String, String>{};
    if (storesAsync is AsyncData) {
      for (final s in storesAsync.value ?? <dynamic>[]) {
        storeMap[s.id] = s.name;
      }
    }

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // ── Header ──
          SliverAppBar.medium(
            title: Text(
              'Inventaire',
              style: TextStyle(
                color: theme.colorScheme.onSurface,
                fontWeight: FontWeight.w700,
                letterSpacing: -0.3,
              ),
            ),
            backgroundColor: theme.colorScheme.surface,
            scrolledUnderElevation: 0,
            leading: Navigator.of(context).canPop()
                ? IconButton(
                    icon: Icon(Icons.arrow_back_ios_new_rounded,
                        color: theme.colorScheme.onSurface, size: 20),
                    tooltip: MaterialLocalizations.of(context).backButtonTooltip,
                    onPressed: () => Navigator.maybePop(context),
                  )
                : null,
          ),

          // ── Active session banner ──
          if (activeSessionAsync is AsyncData && activeSessionAsync.value != null)
            SliverToBoxAdapter(
              child: ActiveSessionBanner(
                session: activeSessionAsync.value!,
                onResume: () {
                  GoRouter.of(context).push(
                      '/inventory/counting/${activeSessionAsync.value!.id}');
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
                  fontWeight: FontWeight.w700,
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
                              color: theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Lancez votre premier inventaire\navec le bouton +',
                            textAlign: TextAlign.center,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
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
                      storeName: storeMap[sessions[index].storeId],
                      onViewReport: sessions[index].status == 'VALIDATED'
                          ? () => context.push(
                              '/inventory/gap-report/${sessions[index].id}')
                          : null,
                    ),
                  ),
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => SliverFillRemaining(
              child: AppErrorWidget(
                error: e,
                onRetry: () => ref.invalidate(sessionHistoryProvider()),
              ),
            ),
          ),
        ],
      ),
      // FAB only when no active session
      floatingActionButton:
          (activeSessionAsync is AsyncData && activeSessionAsync.value == null)
              // Couleur pleine + ombre douce, comme le FAB du catalogue.
              ? Container(
                  decoration: BoxDecoration(
                    color: theme.colorScheme.primary,
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: [
                      BoxShadow(
                        color: theme.colorScheme.primary.withValues(alpha: 0.25),
                        blurRadius: 12,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: FloatingActionButton.extended(
                    onPressed: () async {
                      final sessionId =
                          await showInventoryConfigBottomSheet(context);
                      if (sessionId != null && context.mounted) {
                        GoRouter.of(context)
                            .push('/inventory/counting/$sessionId');
                      }
                    },
                    backgroundColor: Colors.transparent,
                    foregroundColor: Colors.white,
                    elevation: 0,
                    icon: const Icon(Icons.add),
                    label: const Text('Nouvel inventaire',
                        style: TextStyle(fontWeight: FontWeight.w600)),
                  ),
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
            onPressed: () async {
              final messenger = ScaffoldMessenger.of(context);
              Navigator.pop(ctx);
              try {
                await ref
                    .read(cancelSessionNotifierProvider.notifier)
                    .cancel(sessionId);
              } catch (e) {
                messenger.showSnackBar(
                  SnackBar(content: Text(appErrorMessage(e))),
                );
              }
            },
            child: const Text('Oui, annuler'),
          ),
        ],
      ),
    );
  }
}
