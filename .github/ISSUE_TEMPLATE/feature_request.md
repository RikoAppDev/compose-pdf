---
name: Feature request
about: Propose a new DSL element, layout capability, or improvement
title: "[feature] "
labels: enhancement
---

## Problem

What can't you express today, or what is awkward?

## Proposed API

How would it look in the DSL? Sketch the call site.

```kotlin
// proposed usage
```

## Constraints to keep in mind

The library guarantees identical, integer-deterministic output across JVM/Android/iOS with
selectable vector text. Please note how the proposal stays within those constraints (no floats in
layout, no platform APIs in shared code).
