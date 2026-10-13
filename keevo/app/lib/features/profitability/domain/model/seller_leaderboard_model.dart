/// Seller leaderboard models — FR44 (v1s-14-2), OWNER only.
///
/// Mirrors `SellerLeaderboardEntryDto` of `GET /api/v1/reporting/leaderboard`.
/// Amounts are XAF integers.
class SellerLeaderboardEntry {
  final int rank;

  /// User id of the seller (`sales.employee_id`).
  final String sellerId;
  final String sellerName;

  /// True when the seller is the tenant owner (« Propriétaire »).
  final bool owner;
  final int salesCount;
  final int totalRevenue;
  final int averageBasket;

  const SellerLeaderboardEntry({
    required this.rank,
    required this.sellerId,
    required this.sellerName,
    required this.owner,
    required this.salesCount,
    required this.totalRevenue,
    required this.averageBasket,
  });

  factory SellerLeaderboardEntry.fromJson(Map<String, dynamic> json) =>
      SellerLeaderboardEntry(
        rank: (json['rank'] as num?)?.toInt() ?? 0,
        sellerId: json['sellerId'] as String,
        sellerName: json['sellerName'] as String? ?? 'Vendeur inconnu',
        owner: json['owner'] as bool? ?? false,
        salesCount: (json['salesCount'] as num?)?.toInt() ?? 0,
        totalRevenue: (json['totalRevenue'] as num?)?.toInt() ?? 0,
        averageBasket: (json['averageBasket'] as num?)?.toInt() ?? 0,
      );

  SellerLeaderboardEntry withRank(int newRank) => SellerLeaderboardEntry(
        rank: newRank,
        sellerId: sellerId,
        sellerName: sellerName,
        owner: owner,
        salesCount: salesCount,
        totalRevenue: totalRevenue,
        averageBasket: averageBasket,
      );
}

/// Fetch key of the leaderboard (period + store). The ranking metric is not
/// part of it: the toggle re-ranks locally without re-fetching.
class SellerLeaderboardParams {
  final DateTime from;
  final DateTime to;

  /// null = all stores.
  final String? storeId;

  const SellerLeaderboardParams({
    required this.from,
    required this.to,
    this.storeId,
  });

  @override
  bool operator ==(Object other) =>
      other is SellerLeaderboardParams &&
      other.from == from &&
      other.to == to &&
      other.storeId == storeId;

  @override
  int get hashCode => Object.hash(from, to, storeId);
}

/// Result of a leaderboard read: the (unranked) entries plus where they came
/// from. [fromLocal] is true whenever the local Drift aggregation was served
/// (device offline OR backend unreachable) so the page can show the offline
/// banner instead of presenting possibly stale data as live.
class SellerLeaderboardResult {
  final List<SellerLeaderboardEntry> entries;
  final bool fromLocal;

  const SellerLeaderboardResult({
    required this.entries,
    required this.fromLocal,
  });
}
