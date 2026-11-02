# Explicit fleet screen return implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restore an explicitly chosen Immortal screen after a verified fleet self-replacement without assigning a default launcher.

**Architecture:** Extend the existing host helper, reusing `/remote/key` and its fleet authentication. Keep installation proof and screen dispatch separate.

**Tech Stack:** Python standard library; existing Android HTTP API.

**Spec:** `docs/superpowers/specs/2026-11-02-deploy-screen-return-design.md`.

## Global constraints

Original issue deadline: 2026-11-02T22:56:30.941464+00:00, including cleanup.
Default is leave; no preferences, APK, credentials or accessibility changes.
Family functional QA only; no automatic retries or GitHub publication.

## Review focus

Wrong/truthy acknowledgments, partial responses, uncertain dispatch, failed
installation, and nonself targets must not produce a false screen-return success.

## Task 1: Bounded, explicit return

Files: `provisioning/room-fleet.py`, `provisioning/test_room_fleet.py`.
Interfaces: `deploy(..., after_install="leave")`;
`deploy_screen_return(room, action)` raises safe `FleetError` on uncertainty.

- [x] Add and run failing transport, ordering, parser and failure-isolation regressions.
- [x] Implement the optional argument, self-target guard and bounded strict dispatch.
- [x] Run the complete room-helper suite and independently review the changed code.
- [x] Replace Family's exact current APK through the changed helper; verify installed bytes, visible return and unchanged defaults/configuration.
- [x] Record limitations, remove owned secrets, update the roadmap and commit focused paths.
