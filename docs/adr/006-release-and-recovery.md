# ADR 006 — Promote immutable releases and review recovery across resources

Status: Accepted (implemented baseline). Reviewed: 2026-09-22.

## Context and decision

Build and scan three images, publish digest references tied to the verified workflow run, and promote the same release
manifest across environments. Deployment validates provenance and target, saves a Terraform plan in restricted storage,
and renders a Helm preview before the configured environment approval gate. Apply checks the reviewed plan hash and
workflow binding; stale state fails instead of silently replanning.

Treat backups, retained messages, inbox/outbox state, and object generations as one recovery problem. Retain acknowledged
source messages for seven days and canonical state without automatic deletion until a coordinated policy is approved.
Separate environment roots/state and prevent-destroy controls reduce accidental cross-environment/data destruction.

## Alternatives and consequences

Rebuilding per environment changes the artifact under evaluation. Mutable tags obscure the deployed version. Applying a
fresh plan after approval changes the action reviewed. Automatically reverting infrastructure after an application failure
can compound data loss. These workflows instead require a fresh reviewed deployment for recovery/rollback.

Cloud Run Terraform and GKE Helm updates are sequential, not one transaction. Helm atomic rollback cannot revert Cloud
Run or schema changes. Required reviewers, federation trust, private runner groups, registry retention, and operational
permissions remain external setup; YAML names do not enforce them. Provenance checks are not signed image attestations.

A database restore can lose effects already acknowledged by the broker. Replay must preserve event IDs and reconcile
canonical objects with restored registrations. Retention cannot resurrect already-discarded messages or reconstruct
all lost business records. Regional SQL HA and local logical restore tests do not prove regional disaster recovery.

Revisit when signed attestations/admission policy, progressive delivery, regional recovery, or stricter tenant isolation
become requirements. Maintain backward-compatible schemas/events for the supported rollback window.

## Evidence

[CI/CD setup](../cicd.md), [production recovery](../production.md),
[deployment checks](../../scripts/ci/deployment.py), and
[message retention resources](../../infrastructure/terraform/modules/platform/messaging.tf).
