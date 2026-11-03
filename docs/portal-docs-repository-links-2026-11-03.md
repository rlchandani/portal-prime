# Repository references in the documentation site

The local **strict MkDocs build now passes with zero warnings**. The prior
commit failed on 433 warnings despite every referenced target existing in the
repository. This is a source and build correction; the site remains unpublished.
Portal 1.98 is unchanged.

The 433 warning occurrences covered 425 distinct link destinations in 83 documents.
Of the unique destinations, 377 refer to repository files, 47 to images and one to
another docs page. GitHub's repository view can resolve paths leaving `docs/`;
the generated site cannot resolve those paths as documentation files.

Existing tracked repository files now use the configured repository's GitHub
`blob/main` URLs. Images use its raw-content URLs so image destinations return
image bytes after publication. The one internal path, from Music Assistant
server readiness to the multi-room guide, is normalized to a relative docs link.
No MkDocs configuration, workflow, strict-validation severity, source code or
referenced evidence file was changed.

## Local verification

- A guarded rewrite operated only on destinations reported by the baseline
  build, after verifying that each resolved target exists and is Git-tracked.
- Comparing against commit `7d6d5399` proves that all 83 source documents differ
  only in the 425 mapped destinations. Independent review reversed those edits
  and recovered every original document byte-for-byte.
- All 120 link fragments are retained. All referenced file hashes are unchanged.
- Every corrected destination is present in the corresponding generated HTML.
  The docs-internal link resolves through MkDocs' normal page URL conversion.
- The unchanged `mkdocs build --strict` command completes with zero warnings
  in 1.88 seconds, using Material 9.5.39 and MkDocs 1.6.1. No warning was suppressed.
- No Android, browser-remote or fleet behavior changed. No devices were accessed
  during this documentation unit.

These counts describe the mechanical rewrite. The operating state was then
updated to record the delivery; the receipt identifies that bookkeeping change
separately from the mapped link corrections.

The sanitized verification receipt is stored at
`mocks/portal-widget-gallery-20261102/evidence/docs-repository-links-verification.json`.
It records source hashes, build evidence and the publication limit.

## Publication limit

Remote availability has not been checked. Of 316 unique external targets, 290 are
absent from the local `origin/main` snapshot
`e30f44056fa851ad9f856c1670ae1b139a2fdab7`. That cached snapshot does not establish
the current live GitHub state. Many targets belong to this unpublished product
branch; site/repository publication must occur before their URLs are accepted
as live.

Existing informational notices about workstation paths, two heading anchors
and a directory reference remain outside this 433-warning correction. This is
not an exhaustive link audit, hosted-CI result or published-site acceptance.

The original issue clock is **03:59:26.692799–04:29:26.692799 UTC on 3 November**,
including diagnosis during the catalog unit. No GitHub authentication, push,
site deployment, Home Assistant change or phone control occurred.
