/// EmployeeActivity — FR67 activity of one employee (v1s-14-6), OWNER only.
///
/// Mirrors `GET /api/v1/employees/{id}/activity`. Amounts are XAF integers;
/// « today » / « this week » are computed server-side in WAT.
class EmployeeActivity {
  final String employeeId;
  final String userId;
  final DateTime? lastLoginAt;
  final DateTime? lastActivityAt;
  final int todaySalesCount;
  final int todaySalesAmount;
  final int weekSalesCount;
  final int weekSalesAmount;
  final bool activeSession;

  /// ACTIVE employee without activity for more than 48 h.
  final bool inactive;

  const EmployeeActivity({
    required this.employeeId,
    required this.userId,
    this.lastLoginAt,
    this.lastActivityAt,
    this.todaySalesCount = 0,
    this.todaySalesAmount = 0,
    this.weekSalesCount = 0,
    this.weekSalesAmount = 0,
    this.activeSession = false,
    this.inactive = false,
  });

  factory EmployeeActivity.fromJson(Map<String, dynamic> json) {
    DateTime? date(Object? v) =>
        v is String && v.isNotEmpty ? DateTime.tryParse(v) : null;
    int number(Object? v) => v is num ? v.toInt() : 0;
    return EmployeeActivity(
      employeeId: json['employeeId'] as String,
      userId: json['userId'] as String? ?? '',
      lastLoginAt: date(json['lastLoginAt']),
      lastActivityAt: date(json['lastActivityAt']),
      todaySalesCount: number(json['todaySalesCount']),
      todaySalesAmount: number(json['todaySalesAmount']),
      weekSalesCount: number(json['weekSalesCount']),
      weekSalesAmount: number(json['weekSalesAmount']),
      activeSession: json['activeSession'] as bool? ?? false,
      inactive: json['inactive'] as bool? ?? false,
    );
  }
}
