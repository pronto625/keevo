/// French percentage display: decimal comma and a non-breaking space before
/// the sign, e.g. `25,0 %` (never `25.0%`).
String formatPercentFr(num value, {int decimals = 1}) =>
    '${value.toStringAsFixed(decimals).replaceAll('.', ',')} %';
