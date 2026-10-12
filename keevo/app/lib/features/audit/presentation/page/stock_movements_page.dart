import 'package:flutter/material.dart';

import '../widget/stock_history_widget.dart';

/// StockMovementsPage — « Mouvements de stock » (OWNER uniquement).
///
/// The former « Journal d'audit » screen, renamed in v1s-18-7: it wraps
/// [StockHistoryWidget] filtered on stock movements. The full action history
/// lives in [AuditLogPage]. Route: /audit/stock-movements (owner-only prefix).
class StockMovementsPage extends StatelessWidget {
  const StockMovementsPage({super.key});

  /// Entity type of the `STOCK_ADJUSTED` audit entries.
  static const stockMovementEntityType = 'StockMovement';

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        backgroundColor: cs.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        leading: Navigator.of(context).canPop()
            ? IconButton(
                icon: Icon(Icons.arrow_back_ios_new_rounded,
                    color: cs.onSurface, size: 20),
                tooltip: MaterialLocalizations.of(context).backButtonTooltip,
                onPressed: () => Navigator.maybePop(context),
              )
            : null,
        title: Text(
          'Mouvements de stock',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
      ),
      body: const StockHistoryWidget(entityType: stockMovementEntityType),
    );
  }
}
