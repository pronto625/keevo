import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/widget/app_error_widget.dart';
import '../../../../core/widget/soft_icon_badge.dart';
import '../provider/employee_provider.dart';
import '../widget/employee_card.dart';

/// TeamPage — Story 3.5, AC8.
///
/// Shows all employees (ACTIVE first, INACTIVE at bottom).
/// Owner can add employees, reassign stores, deactivate.
class TeamPage extends ConsumerStatefulWidget {
  const TeamPage({super.key});

  @override
  ConsumerState<TeamPage> createState() => _TeamPageState();
}

class _TeamPageState extends ConsumerState<TeamPage> {
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final employeesAsync = ref.watch(employeeListProvider);

    return Scaffold(
      backgroundColor: theme.colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          // ── Header — fond neutre, sans dégradé ──────────────────────
          SliverAppBar(
            expandedHeight: 140,
            pinned: true,
            elevation: 0,
            scrolledUnderElevation: 0,
            backgroundColor: theme.colorScheme.surface,
            foregroundColor: theme.colorScheme.onSurface,
            flexibleSpace: FlexibleSpaceBar(
              // Titre calé en bas : poussée depuis Paramètres, la page a
              // une flèche retour en haut à gauche qu'il ne doit pas couvrir.
              background: SafeArea(
                bottom: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.end,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Paramètres',
                        style: TextStyle(
                          color: theme.colorScheme.onSurfaceVariant,
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                          letterSpacing: 0.5,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Équipe',
                        style: TextStyle(
                          color: theme.colorScheme.onSurface,
                          fontSize: 26,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -0.3,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),

          // ── Employee list ───────────────────────────────────────────
          employeesAsync.when(
            loading: () => const SliverFillRemaining(
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (err, _) => SliverFillRemaining(
              child: AppErrorWidget(
                error: err,
                title: 'Erreur de chargement',
                onRetry: () =>
                    ref.read(employeeListProvider.notifier).refresh(),
              ),
            ),
            data: (employees) {
              if (employees.isEmpty) {
                return SliverFillRemaining(
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const SoftIconBadge(
                          icon: Icons.group_outlined,
                          size: 72,
                        ),
                        const SizedBox(height: 12),
                        Text('Aucun employé',
                            style: theme.textTheme.titleMedium),
                        const SizedBox(height: 8),
                        Text(
                          'Appuyez sur « Ajouter » pour créer un employé.',
                          style: theme.textTheme.bodyMedium
                              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                );
              }
              return SliverPadding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 100),
                sliver: SliverList(
                  delegate: SliverChildBuilderDelegate(
                    (context, index) => Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: EmployeeCard(
                        employee: employees[index],
                        onActionComplete: () => ref
                            .invalidate(employeeListProvider),
                      ),
                    ),
                    childCount: employees.length,
                  ),
                ),
              );
            },
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/settings/team/new'),
        icon: const Icon(Icons.person_add_rounded),
        label: const Text('Ajouter'),
        backgroundColor: theme.colorScheme.primary,
        foregroundColor: theme.colorScheme.onPrimary,
      ),
    );
  }
}
