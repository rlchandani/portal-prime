# Built receiver preview.2 — preserved source evidence

See [the current validation record](../../../music-receiver-preview-validation.md). Apply the consolidated patch to public Snapdroid commit `28529b546c48b7eeec5f0ae90b7ec330c4828dcb`. Its build expects the separately obtained pinned public arm64 native binary recorded in `native-input.json`; no binary, keystore or private key is committed. Upstream GPL and the added MIT notice remain in the patch. This is a public-release-based alternative; its native bytes differ from the actual installed Family receiver.

`RECEIPT.md` records the build worker's result before the parent re-signed and ran a query-only Family probe. The current validation record supersedes its then-open signing/query facts. Audio execution, focus handoff, target mapping, production integration and licensing/reproducible native distribution gates remain open.
