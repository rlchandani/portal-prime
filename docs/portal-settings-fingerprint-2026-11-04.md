# Verify a saved settings/layout revision

`scripts/portal_settings_fingerprint.py` verifies a locally saved schema-v1 `/remote/snapshot` response using the same fingerprint as Android's `RemoteSnapshotPolicy`.

```bash
python3 -B scripts/portal_settings_fingerprint.py capture.json
```

Exit **0** means the computed and expected revisions match; **1** means a mismatch; **2** means invalid or unsupported input. Output contains only fingerprints or a fixed error code. The helper performs no network requests or device changes. Captures may contain personal configuration; keep them private.

The library's `revision(domains, layout)` accepts objects, arrays, strings, booleans, null and signed 64-bit integers. It preserves Android's UTF-16 key ordering, comma-space separators, compact colons, escaped slashes and literal Unicode. Floats, duplicate JSON keys and lone surrogates are rejected. This is fingerprint verification, not complete layout validation, device identity validation or proof that two captures contain identical settings.

All **315 Python checks** passed, including eleven new test methods with literal canonical examples and CLI exit/error checks. The helper matched four previously saved Android revisions: both rooms before and after 1.181. Its CLI also verified the saved Family snapshot. Independent source review accepted the helper and tests; all four reviewed source hashes were rechecked. No fresh device interaction occurred.

Original start **22:57:22UTC**, target **23:12:22UTC**, hard limit **23:27:22UTC**. Discovery selection exceeded its five-minute cap; the original clock was retained. Both rooms remain **1.181/code175**. Held screensaver issues retain their existing clocks. Sanitized evidence: `mocks/portal-settings-fingerprint-20261104/verification.json`.
