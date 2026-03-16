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
    return Scaffold(
      appBar: AppBar(
        title: const Text('Journal d\'audit'),
        elevation: 0,
      ),
      body: const StockHistoryWidget(),
    );
  }
}
