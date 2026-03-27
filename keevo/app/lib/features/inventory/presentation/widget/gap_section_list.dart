import 'package:flutter/material.dart';

import '../../domain/model/inventory_gap_row_model.dart';
import 'gap_row_tile.dart';

/// GapSectionList — section header + list of GapRowTile items.
///
/// Used for both "Manquants" (red) and "Surplus" (amber) sections.
/// Story 6.3 — AC1, AC2.
class GapSectionList extends StatelessWidget {
  final String title;
  final List<InventoryGapRowModel> rows;
  final Color titleColor;
  final ValueChanged<InventoryGapRowModel>? onRowTap;

  const GapSectionList({
    super.key,
    required this.title,
    required this.rows,
    required this.titleColor,
    this.onRowTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Text(
            '$title (${rows.length})',
            style: theme.textTheme.titleSmall?.copyWith(
              color: titleColor,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        ...rows.map((row) => GapRowTile(
              row: row,
              onTap: onRowTap != null ? () => onRowTap!(row) : null,
            )),
      ],
    );
  }
}
