# Captured ART profile verification

`scripts/portal_profile_validation.py` is an offline wrapper for the existing
private SDK mapper and comparator. It repairs the output-directory mismatch
that stopped the Clock native runner before its UI checks: both SDK tools
require fresh output below their own private tool directory, whereas the
runner supplied its repository diagnostics directory.

The wrapper creates a unique child directory under the supplied tool directory,
runs both mappings and the full comparison there, then archives validated audits
at the caller's separate evidence directory. Existing evidence directories,
even empty ones, are rejected. The archived profile snapshots do not depend on
the private scratch directory remaining available.

## Invocation

The caller supplies frozen APK and reference-profile identities from its native
capture, rather than calculating a new expected identity after a failed check.
For example:

```bash
python3 -B scripts/portal_profile_validation.py \
  --tool-root /private/tmp/accepted-apk-profile-coverage-p3zjcwgx \
  --apk /absolute/path/to/frozen.apk \
  --sha256 <expected-apk-sha256> \
  --reference-profile /absolute/path/to/captured/reference.prof \
  --reference-sha256 <expected-reference-sha256> \
  --output-dir /absolute/path/to/new/evidence \
  --timeout-seconds 120
```

The existing mapper requires Java 21 and its previously provisioned local
ProfileTranscoder dependency. The wrapper neither installs dependencies nor
connects to a device or network. Its external provider and decoder artifacts
must still satisfy the caller's frozen-input admission checks. Tool script
hashes are recorded and checked for changes during this invocation; the mapper
also records the decoder JAR hash in each audit's original `commands.json`.

Use the remaining time from the original issue deadline, reserving time for
closure and any device restoration. Every subprocess shares the same wrapper
deadline; timeout or interruption kills its decoder process group. This is not
a new issue clock and must not reopen an expired Alarm or Clock trial.

## Verification contract

- The explicit APK and ART v010 reference bytes must match their expected hashes.
  An invalid explicit reference never falls back to the APK's bundled profile.
- Each map must pass the existing decoder, DEX mapping, inventory, snapshot hash,
  count and H/S/P flag consistency checks through `compare_profiles.load_audit`.
- The bundled and reference inventories must match in every resolved method row,
  class row, H/S/P flag and DEX checksum. Different frozen APKs cannot match.
- SDK `load_audit` returns five fields: proof, DEX checksums, method rows, class rows,
  and raw inventory-file hashes. Use `assert_profile_inventory_equivalent` for the
  canonical pair comparison, including the admitted counts when the caller has them.
  Do not compare `bundled[1:] == reference[1:]`: raw TSV hashes can differ because
  Android names the primary DEX profile key `base.apk`, while the bundled input names
  it `classes.dex`. The SDK normalizes that key; all other row metadata remains part
  of equality. Both raw hash dictionaries are recorded as `rawInventorySha256` and
  retain their own integrity role. A hash is never replaced with the other side's hash.
  This native gate retains its existing nonempty DEX/method/class inventories and
  positive hot application-method requirements; it is not a general method-only profile checker.
- The legacy six-symbol Settings coverage checklist is retained and reported
  separately. Mapper exit 1 is accepted only when its complete audit is valid
  and the full pair comparison succeeds. `legacyCoverageGatePassed: false`
  remains false; this wrapper does not claim that checklist passed.
- Archiving changes only matching JSON `snapshotPath` metadata. Binary snapshots,
  inventory files, checksums, descriptors and flags remain identical. Original
  command records are retained verbatim, and per-file original/archive hashes
  document the relocation. The archive is loaded again through the SDK validator.
- Successful and failed attempts retain `profile-validation.json` and tool logs.
  A failed attempt never emits a `VERIFIED` report. No existing proof is overwritten.

The native caller still owns device provenance, current installed-APK hash,
DEX metadata, compilation filter, collector state, window ownership and all UI
checks. Offline equality proves resolved methods/classes/flags, not raw profile
or inline-cache equality, individual method compilation, execution, UI behaviour
or performance. It cannot turn a parked source change into a shipped fix.

## Native caller integration

After a successful `validate_profiles` result, use the shared comparison instead of
adding filename-sensitive raw-hash equality:

```python
counts = assert_profile_inventory_equivalent(
    load_audit(output / "bundled"),
    load_audit(output / "reference"),
    admitted_apk_sha256,
    admitted_profile_counts,
)
```

The caller still pins both inventory files independently in its frozen evidence.
Historical admissions and helpers remain immutable. A future driver must preserve
its baseline's validator bytes in an immutable snapshot if the old admission pinned
an earlier validator; it must not silently substitute the new script into that proof.

The concrete false rejection was reproduced against the captured Photo Clock
candidate and its accepted rollback: both had equal canonical inventories and only
the `profile_key` column changed. The counts were 8,674 methods / 2,577 classes and
8,667 methods / 2,576 classes respectively. Offline verification does not reopen
that parked device trial or prove its unexecuted Settings checks.

The filename rewrite is documented in AndroidX's
[ProfileTranscoder implementation](https://android.googlesource.com/platform/frameworks/support/+/e32d7e45d2b7d50888276ac58767b362bc46c538%5E%21/).

## Tests and bounded verification

```bash
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
```

The focused regressions cover private work-directory placement, unique runs,
historical evidence preservation, invalid input hashes, exclusive v010 inputs,
size boundaries, selective metadata relocation and process-group deadlines.
The canonical-equivalence extension adds 28 tests, bringing the focused profile-tool
suite to 44 tests. It covers filename-sensitive raw hashes, altered rows/flags/checksums,
strict integer proofs, incorrect input identities, malformed method fields and expected counts.
The final full-wrapper replay verifies both captured pairs with no device operations.

The recorded local integration run also used the captured accepted APK and its
own reference profile, rejected a reference captured from the different Clock
APK, and revalidated both archives while the original private scratch directory
was temporarily unavailable. App source and installed APKs were unchanged.
