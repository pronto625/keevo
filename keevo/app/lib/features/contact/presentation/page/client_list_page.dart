import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_button.dart';
import '../../domain/model/client_model.dart';
import '../provider/contact_provider.dart';

/// ClientListPage — Material 3 list with background sync (Story 2.5).
class ClientListPage extends ConsumerStatefulWidget {
  const ClientListPage({super.key});

  @override
  ConsumerState<ClientListPage> createState() => _ClientListPageState();
}

class _ClientListPageState extends ConsumerState<ClientListPage> {
  final _searchController = TextEditingController();
  bool _includeArchived = false;
  bool _isSyncing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncSilently());
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _syncSilently() async {
    try {
      await ref.read(clientListNotifierProvider.notifier).syncFromRemote();
    } catch (_) {
      // Hors ligne : la liste locale reste affichée, sans message.
    }
  }

  Future<void> _syncWithFeedback() async {
    setState(() => _isSyncing = true);
    try {
      await ref.read(clientListNotifierProvider.notifier).syncFromRemote();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Row(children: [
              Icon(Icons.check_circle_rounded, color: Colors.white, size: 18),
              SizedBox(width: 8),
              Text('Clients synchronisés'),
            ]),
            backgroundColor: AppTheme.primary,
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            margin: const EdgeInsets.all(16),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Synchronisation impossible : ${appErrorMessage(e)}'),
            backgroundColor: Theme.of(context).colorScheme.error,
            behavior: SnackBarBehavior.floating,
            margin: const EdgeInsets.all(16),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isSyncing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final clientsAsync = ref.watch(clientListNotifierProvider);

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // En-tête épuré — fond neutre, sans bandeau dégradé ni cercles
          // décoratifs ; la hauteur reste suffisante pour ne pas passer sous
          // la flèche retour.
          SliverAppBar(
            expandedHeight: 140,
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: theme.colorScheme.surface,
            leading: IconButton(
              icon: Icon(Icons.arrow_back_ios_new_rounded,
                  color: theme.colorScheme.onSurface, size: 20),
              onPressed: () => Navigator.of(context).pop(),
            ),
            flexibleSpace: FlexibleSpaceBar(
              background: SafeArea(
                bottom: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 0, 16, 16),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.end,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  'Clients',
                                  style: theme.textTheme.headlineMedium?.copyWith(
                                    color: theme.colorScheme.onSurface,
                                    fontWeight: FontWeight.w700,
                                    letterSpacing: -0.3,
                                  ),
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  'Gérez votre carnet de clients',
                                  style: theme.textTheme.bodyMedium?.copyWith(
                                    color: theme.colorScheme.onSurfaceVariant,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          SoftIconButton(
                            icon: _includeArchived
                                ? Icons.visibility_off_outlined
                                : Icons.visibility_outlined,
                            tooltip: _includeArchived
                                ? 'Masquer les archivés'
                                : 'Voir les archivés',
                            onPressed: () {
                              setState(() => _includeArchived = !_includeArchived);
                              ref
                                  .read(clientListNotifierProvider.notifier)
                                  .refresh(includeArchived: _includeArchived);
                            },
                          ),
                          const SizedBox(width: 6),
                          SoftIconButton(
                icon: Icons.sync_rounded,
                tooltip: 'Synchroniser',
                loading: _isSyncing,
                onPressed: _syncWithFeedback,
              ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),

          // ── Search bar — bordure fine plutôt qu'ombre portée ───────────
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Container(
                decoration: BoxDecoration(
                  color: theme.colorScheme.surface,
                  borderRadius: BorderRadius.circular(AppTheme.radiusL),
                  border: Border.all(color: theme.colorScheme.outlineVariant),
                ),
                child: TextField(
                  controller: _searchController,
                  decoration: InputDecoration(
                    hintText: 'Rechercher un client…',
                    hintStyle: TextStyle(
                      color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.7),
                    ),
                    prefixIcon: Icon(Icons.search_rounded,
                        color: theme.colorScheme.onSurfaceVariant),
                    suffixIcon: _searchController.text.isNotEmpty
                        ? IconButton(
                            icon: const Icon(Icons.clear_rounded),
                            onPressed: () {
                              _searchController.clear();
                              setState(() {});
                              ref
                                  .read(clientListNotifierProvider.notifier)
                                  .refresh(includeArchived: _includeArchived);
                            },
                          )
                        : null,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(AppTheme.radiusL),
                      borderSide: BorderSide.none,
                    ),
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    filled: false,
                    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                  ),
                  onChanged: (q) {
                    setState(() {});
                    ref.read(clientListNotifierProvider.notifier).search(q);
                  },
                ),
              ),
            ),
          ),

          // ── List ─────────────────────────────────────────────────────
          clientsAsync.when(
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => SliverFillRemaining(
              child: AppErrorWidget(
                error: e,
                title: 'Erreur de chargement',
                onRetry: () => ref.read(clientListNotifierProvider.notifier).refresh(),
              ),
            ),
            data: (clients) => clients.isEmpty
                ? SliverFillRemaining(
                    child: _EmptyState(onAdd: () => context.push('/clients/new')),
                  )
                : SliverPadding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 100),
                    sliver: SliverList(
                      delegate: SliverChildBuilderDelegate(
                        (_, i) => Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: _ClientCard(client: clients[i]),
                        ),
                        childCount: clients.length,
                      ),
                    ),
                  ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        heroTag: 'client_fab',
        onPressed: () => context.push('/clients/new'),
        icon: const Icon(Icons.person_add_rounded),
        label: const Text('Nouveau client', style: TextStyle(fontWeight: FontWeight.w600)),
      ),
    );
  }
}

// ── Empty state ────────────────────────────────────────────────────────────────

class _EmptyState extends StatelessWidget {
  final VoidCallback onAdd;
  const _EmptyState({required this.onAdd});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(28),
              decoration: BoxDecoration(
                color: AppTheme.softTint(theme.brightness),
                shape: BoxShape.circle,
              ),
              child: Icon(
                Icons.people_outline_rounded,
                size: 64,
                color: theme.colorScheme.primary,
              ),
            ),
            const SizedBox(height: 24),
            Text(
              'Aucun client pour l\'instant',
              style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 8),
            Text(
              'Ajoutez vos clients pour les retrouver facilement.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            FilledButton.icon(
              onPressed: onAdd,
              icon: const Icon(Icons.person_add_rounded),
              label: const Text('Ajouter un client'),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Client card ────────────────────────────────────────────────────────────────

class _ClientCard extends ConsumerWidget {
  final ClientModel client;
  const _ClientCard({required this.client});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final primary = theme.colorScheme.primary;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => context.push('/clients/${client.id}', extra: client),
        onLongPress: client.archived ? null : () => _confirmArchive(context, ref),
        borderRadius: BorderRadius.circular(AppTheme.radiusL),
        // Rangée de liste : bordure fine plutôt qu'une ombre portée.
        child: Ink(
          decoration: BoxDecoration(
            color: theme.colorScheme.surface,
            borderRadius: BorderRadius.circular(AppTheme.radiusL),
            border: Border.all(
              color: theme.colorScheme.outlineVariant.withAlpha(90),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                // Avatar initiale — pastille pâle plutôt qu'un dégradé saturé.
                Container(
                  width: 52, height: 52,
                  decoration: BoxDecoration(
                    color: client.archived
                        ? theme.colorScheme.surfaceContainerHighest
                        : AppTheme.softTint(theme.brightness),
                    shape: BoxShape.circle,
                  ),
                  child: Center(
                    child: Text(
                      client.name.isNotEmpty ? client.name[0].toUpperCase() : '?',
                      style: TextStyle(
                        color: client.archived
                            ? theme.colorScheme.onSurfaceVariant
                            : primary,
                        fontWeight: FontWeight.w700,
                        fontSize: 20,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              client.name,
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.w600,
                                color: client.archived
                                    ? theme.colorScheme.onSurfaceVariant
                                    : null,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (client.archived)
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                              decoration: BoxDecoration(
                                color: theme.colorScheme.surfaceContainerHighest,
                                borderRadius: BorderRadius.circular(20),
                              ),
                              child: Text(
                                'Archivé',
                                style: theme.textTheme.labelSmall?.copyWith(
                                  color: theme.colorScheme.onSurfaceVariant,
                                ),
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          Icon(Icons.phone_outlined, size: 14,
                              color: theme.colorScheme.onSurfaceVariant),
                          const SizedBox(width: 4),
                          Text(
                            client.phone,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ],
                      ),
                      if (client.email != null && client.email!.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Row(
                          children: [
                            Icon(Icons.email_outlined, size: 14,
                                color: theme.colorScheme.onSurfaceVariant),
                            const SizedBox(width: 4),
                            Expanded(
                              child: Text(
                                client.email!,
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: theme.colorScheme.onSurfaceVariant,
                                ),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ],
                  ),
                ),
                Icon(
                  Icons.chevron_right_rounded,
                  color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.4),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _confirmArchive(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (dialogCtx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: const Text('Archiver le client ?'),
        content: Text('${client.name} sera masqué de la liste active.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogCtx), child: const Text('Annuler')),
          FilledButton(
            onPressed: () {
              Navigator.pop(dialogCtx);
              ref.read(clientListNotifierProvider.notifier).archive(client.id);
            },
            child: const Text('Archiver'),
          ),
        ],
      ),
    );
  }
}
