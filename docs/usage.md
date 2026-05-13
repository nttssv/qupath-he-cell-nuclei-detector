# Usage

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

## Clearing Objects

In QuPath script editor:

```groovy
clearAllObjects()
```

To keep annotation ROIs and only remove detections:

```groovy
clearDetections()
```
