/// French agreement of a counted noun: singular for 0 and 1 (« 1 unité »,
/// « 0 unité »), plural from 2 (« 3 unités ») — never « 1 unités ».
String frCount(num count, String singular, String plural) =>
    '$count ${count.abs() < 2 ? singular : plural}';

/// « 1 unité » / « 5 unités ».
String unitsLabel(num count) => frCount(count, 'unité', 'unités');
