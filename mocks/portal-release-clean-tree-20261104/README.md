# Release cleanliness gate

Host-only verified change: refuse nonignored untracked files before release fetch,
version mutation or build. No release, device or phone operations occurred.

The frozen test is `scripts/test_portal_cut_release_clean_tree.py`.
Evidence records six expected baseline failures/four controls, ten candidate
passes, the full 264-test Python CI result, independent source review, preserved
queue suffix and the original issue clock. Full CI logs remain private; their
hashes bind the committed summaries.
