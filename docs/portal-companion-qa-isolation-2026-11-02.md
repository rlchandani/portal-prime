# Companion QA source isolation — 2 November 2026

The companion's previous numeric acceptance changed seven generated boot/modem
files in the source AVD despite preserving its backing disks. A new disposable
runner in the isolated Toolkit checkout fixes that QA isolation gap.

`qa/isolated_emulator.py` clones the full stopped AVD, registers a unique owned
name, isolates writable image paths and Android homes, and checks original
contents and metadata before/after. Cleanup targets only the owned process
group and clone directories. Logs and receipts remain private.

**23 offline guards passed.** A real macOS/API 34 clone boot passed native
unpaired companion readiness and exact installed-APK readback at 360 × 800 dp.
The original AVD directory had **zero changed entries**, replacing the earlier
seven generated-file changes. Normal owned shutdown and clone removal passed;
Family's USB/Wi-Fi transports remained.

Companion remains **1.7.1-music-preview / code 13**, 14,722,589 bytes, SHA-256
`b5a0feb0b0486c53553e3b8126417afa0fd45eae340efd116ab539283ddc10f4`.
Production source remains `3ae098044a0eaf4f42dadf2fff8184f3aa5a5646`.
Both Portal APKs remain the accepted **1.72 / code 66**; no app or Portal
configuration update was performed.

The default-path real run preceded the last custom-image mapping correction.
The corrected custom mapping is verified by the added filesystem regression,
with all 23 checks passing; the actual default mapping/flags are unchanged.
Real interrupted/timeout cleanup, custom-image boot, other hosts, fresh pairing,
cold reconnect, physical Pixel, playback and general performance remain open.
Source preservation covers the `.avd` directory, not every file on the host.

Toolkit usage/receipt: `qa/README.md` and
`docs/companion-qa-isolation-validation-2026-11-02.md` in
`~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit`.
Private evidence: `/private/tmp/portal-companion-qa-isolation-20261102/`.
Slot: **11:40:49–12:10:49 UTC**, including cleanup.
The broader product goal remains active.
