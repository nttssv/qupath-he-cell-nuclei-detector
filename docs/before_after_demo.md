# Before and After Demo

This example shows one QuPath ROI before and after running the Prototype 1 H&E cell and nuclei detector.

## Side-by-Side View

| Before: raw H&E ROI | After: Prototype 1 overlay |
|---|---|
| <img src="../assets/before_after/demo_roi_before.png" alt="Raw H&E ROI before Prototype 1 annotation" width="420"> | <img src="../assets/before_after/demo_roi_after.png" alt="H&E ROI after Prototype 1 cell and nuclei overlay" width="420"> |

## Interpretation

- Blue objects represent nuclei.
- Green outlines/fills represent clear-cell boundary regions.
- Magenta regions represent compact-cell regions.
- Orange regions represent uncertain regions.
- Red regions represent mesenchyme/stroma-style regions.

The extension can generate these files automatically when `Export before/after PNGs` is checked in the QuPath dialog.

Generated files are written under:

```text
qupath_extension_runs/<image>_<timestamp>/before_after_png/
```

