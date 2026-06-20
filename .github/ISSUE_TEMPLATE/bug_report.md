---
name: Bug report
about: Report incorrect output, a crash, or a cross-platform difference
title: "[bug] "
labels: bug
---

## Description

A clear description of the problem.

## Reproduction

A minimal DSL snippet that produces the issue:

```kotlin
pdfDocument(PageConfig(margin = 36.dp)) {
    // …
}.render(regular, bold)
```

## Expected vs actual

- **Expected:**
- **Actual:**

## Environment

- compose-pdf version:
- Platform(s): [ ] JVM  [ ] Android  [ ] iOS
- If it differs **between platforms**, describe how (this is the highest-priority kind of bug).

## Attachments

If possible attach the generated PDF (or a screenshot) and the font used.
