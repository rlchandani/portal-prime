#!/usr/bin/env bash
#
# Pre-release sanity check: Bash syntax, Windows ASCII, optional PowerShell parse.
# CI also parses under Windows PowerShell 5.1 (.github/workflows/provisioning.yml).
# Run this locally before a release to catch script syntax and encoding mistakes.
#
#   scripts/check-provisioning.sh
#
# Needs `pwsh` for the PowerShell check (macOS: `brew install powershell`).
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

echo "• provision.sh — bash syntax"
if bash -n provisioning/provision.sh; then echo "  OK"; else echo "  FAILED"; fail=1; fi

echo "• Windows scripts — pure ASCII"
windows_ascii_fail=0
for f in provisioning/provision.ps1 provisioning/*.bat scripts/test-provision-access.ps1; do
  [ -e "$f" ] || continue
  # Byte ranges work with BSD and GNU tr; pwsh 7 parsing alone misses this rule.
  non_ascii_bytes=$(LC_ALL=C tr -d '\000-\177' < "$f")
  if [ -n "$non_ascii_bytes" ]; then
    echo "  FAILED: $f contains non-ASCII bytes (Windows PowerShell 5.1 requires ASCII)."
    windows_ascii_fail=1
    fail=1
  fi
done
if [ "$windows_ascii_fail" -eq 0 ]; then echo "  OK"; fi

echo "• provision.ps1 — PowerShell parse"
if command -v pwsh >/dev/null 2>&1; then
  if pwsh -NoProfile -Command '
      $e=$null
      [void][System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path "provisioning/provision.ps1").Path, [ref]$null, [ref]$e)
      if ($e) { $e | ForEach-Object { Write-Error "  line $($_.Extent.StartLineNumber): $($_.Message)" }; exit 1 }'; then
    echo "  OK"
  else
    echo "  FAILED"; fail=1
  fi
else
  echo "  SKIPPED — pwsh not installed (brew install powershell)"
fi

[ "$fail" -eq 0 ] && echo "Provisioning checks passed." || { echo "Provisioning checks FAILED."; exit 1; }
