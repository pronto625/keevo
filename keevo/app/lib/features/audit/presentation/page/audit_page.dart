import 'package:flutter/material.dart';

import '../widget/stock_history_widget.dart';

/// AuditPage — Journal d'audit complet du tenant (OWNER uniquement).
///
/// Wraps [StockHistoryWidget] without entity filters to display the full
/// tenant-wide audit log. Route: /audit (owner-only via router guard).
class AuditPage extends StatelessWidget {
  const AuditPage({super.key});

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
          'Journal d\'audit',
          style: TextStyle(
            color: cs.onSurface,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
          ),
        ),
      ),
      body: const StockHistoryWidget(),
    );
  }
}
