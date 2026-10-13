# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Start here

`AGENTS.md` in the repo root is a detailed architecture map of the Android app (app start-up flow,
editor stack, `ShaderRenderer`, persistence, uniforms/textures, preferences, sensors/camera/audio,
services, import/export, and a "common change scenarios" cookbook). Read it before making non-trivial
changes to `app/` — this file only summarizes build/test commands and adds what AGENTS.md doesn't cover
(the docs-site subproject, CI).

## What this repo contains

Two independent projects:
- `app/` — the Android app itself (Enki Shader Editor), package
  `enk.danwills.android.enkishadereditor`. Java 17, Gradle Kotlin DSL, no Jetpack Compose.
- `docs-site/` — a separate Astro static site (the project's public docs/landing page), deployed to
  GitHub Pages by `.github/workflows/deploy-docs.yml` on pushes touching `docs-site/**` or `FAQ.md`.

## Commands

### Android app (run from repo root)
- `./gradlew assembleDebug` or `make debug` — build debug APK.
- `make install` / `make start` / `make uninstall` — adb install/launch/uninstall the debug build.
- `./gradlew assembleRelease` / `make release` — release build (minified, resource-shrunk; requires
  `ANDROID_KEY_*` signing env vars). `make bundle` builds the AAB instead.
- `./gradlew lintDebug` or `make lint` — Android lint.
- `make infer` — runs Facebook Infer static analysis over a clean assembleDebug.
- `make avocado` — optimizes vector drawables under `app/src/main/res` in place.
- `make meminfo` / `make glxinfo` — adb dumpsys helpers for memory/gfx debugging on a connected device.
- There are no automated unit/instrumentation tests in this project. Verify changes by building,
  installing, and manually exercising the app (see AGENTS.md §12 for what to check: compile errors,
  battery/render-mode switching, notification permission flow, etc.).

### docs-site (run from `docs-site/`)
- `npm install`
- `npm run dev` — local Astro dev server.
- `npm run build` — static build (what CI runs).
- `npm run preview` — preview the built output.

## Conventions

- Indentation is tabs, not spaces; `.editorconfig` is authoritative if a contributor enables
  EditorConfig support in their IDE — otherwise follow Android Studio's default Java formatting.
- Java import order and most formatting choices are encoded in `.editorconfig`; don't fight it.
- Commit style: one feature/fix per commit, squash feature branches when merging to `master`
  (`git merge <branch> --squash`), write a proper commit message.
- String resources: when adding/changing a string, update it for all supported languages under
  `app/src/main/res/values-*`, not just the default `values`.
- Release prep touches `CHANGELOG.md`, `fastlane/metadata/**/changelogs`, and the version in
  `app/build.gradle.kts` together — see AGENTS.md §13.
