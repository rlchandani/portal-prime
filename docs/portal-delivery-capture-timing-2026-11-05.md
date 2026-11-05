# Delivery evidence capture timing

The offline delivery-summary helper now reports elapsed minutes from the original issue start and whether capture was within or past the target and hard limit. Build duration alone does not describe the whole work unit.

The two new lines are explicitly **at evidence capture**. Exact aware timestamps decide the deadline labels before elapsed minutes are rounded. Deadlines are inclusive. A capture one microsecond past the target prints15.00 minutes and `target past`; one microsecond past the hard limit prints30.00 and `hard limit past`. Equivalent UTC-offset timestamps produce the same duration and labels.

Saved delivery status remains readable after a deadline; the formatter reports timing without rewriting recorded delivery outcomes. Capture time does not establish completion, commit, promotion or fresh health. Existing validation, escaping, fixed diagnostics and exclusive output publication remain unchanged.

Six new whole-CLI cases fail on the unchanged helper while ten existing controls pass. The frozen16 methods then all pass; the existing Python CI wildcard reports331 passing tests. Hosted CI execution is unobserved. Root exact reversal proves the prior helper and ten test bodies are retained.

Generate a new summary using the existing command:

```sh
python3 -B scripts/portal_delivery_summary.py PUBLIC_RECEIPT.json --output NEW_SUMMARY.md
```

The saved example at `mocks/portal-delivery-capture-timing-20261105/example-1.199.md` shows12.18 minutes at capture, within both limits. It differs from the original summary only by the two timing lines. Existing summaries and receipts are preserved.

`mocks/portal-delivery-capture-timing-20261105/verification.json` records source/test/example hashes and scoped acceptance. Independent source review passed; root rehashed its input bindings. No app build, room installation or device/provider/account operation occurred. Rooms retain previous verified1.199/code193, with no fresh device check; public release manifest1.83 remains.

Original start **2026-11-05 04:25:31 UTC**, discovery cap **04:30:31**, target **04:40:31**, hard limit **04:55:31**. Earlier scopes/clocks and whole product goal remain open.
