# Troubleshooting

## Extension menu does not appear

- Confirm the JAR is in the QuPath extensions folder.
- Restart QuPath completely.
- Check QuPath log for extension loading errors.

## Python runtime missing

Set:

```bash
export PROTOTYPE1_PYTHON="/absolute/path/to/python"
```

The Python environment must have packages in `requirements.txt`.

## No `_um2` measurements

The image likely has no micron pixel calibration in QuPath. The extension will still add pixel measurements.

## Nothing appears after running

Check:

- Detection visibility is enabled in QuPath.
- `Display like InstanSeg detections` was checked.
- The selected ROI was not too large.
- `prototype1_qupath_run.log` for the run folder.

## QuPath looks slow during a large run

The `Prototype 1 status` window should continue reporting the current stage. The longest stage is usually Python segmentation; the final import stage can briefly pause QuPath if thousands of cell and nucleus objects are added at once.

For large regions, run smaller ROIs or disable `Import nuclei` when you only need the dashboard and summary measurements.

## ROI too large

Use smaller annotations. The extension rejects regions larger than `4096 x 4096` pixels by default.
