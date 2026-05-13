# Usage

## Biological Scope

Prototype 1 is tuned for H&E-stained adrenal cortical tissue. It is designed to quantify adrenocortical parenchymal clear-cell and compact-cell morphology, nuclei, mesenchyme/stroma area, and nuclear-to-cytoplasm area balance.

For other tissues or stains, use the outputs as exploratory annotations and perform independent validation.

1. Open an image in QuPath.
2. Draw an annotation around a small ROI.
3. Select the annotation.
4. Run `Extensions > Prototype 1 > Run on selected annotation(s)`.
5. Choose import options in the dialog.
6. Open `Measure > Show detection measurements`.

## Recommended Options

- `Import cell boundaries`: on
- `Import nuclei`: on
- `Display like InstanSeg detections`: on
- `Select imported objects after run`: off unless debugging
- `Export before/after PNGs`: on when preparing examples or QC reports
- `Show statistics dashboard`: on when you want the parenchyme/mesenchyme/nuclear/cytoplasm visual summary

## Statistics Dashboard

The dashboard is generated as a PNG and, when QuPath is running with a GUI, shown in a QuPath dialog after the run.

The dashboard window is non-blocking, so the main QuPath interface remains interactive.

It summarizes:

- Parenchyme area and percentage
- Mesenchyme/stroma area and percentage
- Nuclear area versus cytoplasm area
- Nuclear/cytoplasm ratio
- Clear, compact, and uncertain cytoplasm area composition
- Cell and nuclei counts

Dashboard files are saved to:

```text
qupath_extension_runs/<image>_<timestamp>/statistics_dashboard/
```

To reopen the newest dashboard after closing it:

```text
Extensions > Prototype 1 > Open latest statistics dashboard
```

## Clearing Objects

In QuPath script editor:

```groovy
clearAllObjects()
```

To keep annotation ROIs and only remove detections:

```groovy
clearDetections()
```
