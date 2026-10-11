import 'package:flutter/material.dart';

import '../../domain/model/store_type.dart';

/// Icon shown next to a store type (no decorative emoji).
IconData storeTypeIcon(StoreType type) => switch (type) {
      StoreType.store => Icons.storefront_outlined,
      StoreType.warehouse => Icons.warehouse_outlined,
    };
