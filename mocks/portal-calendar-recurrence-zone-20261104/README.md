# Calendar recurrence timezone

Fifteen frozen tests use actual CalendarFeed.parse and generated ICS with
literal UTC starts/ends. Baseline thirteen expected AssertionErrors and two
controls establish timezone loss after DTSTART parsing; all fifteen pass after.

Six frozen actual-debug/API29 direct parser cases have four baseline literal
failures and two controls, then six passes with identical Java/DEX/driver/gates.
Both runs retire. Only generated String/long/int arguments enter the
isolated parser. TimeZone and all Locale defaults restore per case and globally.
No Context, fetch/read, provider or preference path is invoked by the fixture;
zero external-call fields describe source containment, not loader instrumentation.

Root owns compilation, device operations and retirement. Calendar UI,
live feeds, optimized/API28, Living visuals and device timezone settings are
separate acceptance scopes. Original11:24:52–11:54:52UTC retained; the11:39:52
target elapsed before selection.
