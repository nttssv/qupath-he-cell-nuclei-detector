# Installation

## macOS

```bash
cd qupath_extension_release
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
./build_extension.sh
QUPATH_EXTENSIONS_DIR="$HOME/Library/Application Support/QuPath/extensions" ./install_to_qupath.sh
```

If QuPath uses a custom extensions folder, set `QUPATH_EXTENSIONS_DIR` to that folder before running the install script.

## Windows

Use PowerShell:

```powershell
cd qupath_extension_release
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Build the JAR with a JDK installed, then copy `dist/qupath-extension-prototype1.jar` into your QuPath extensions folder.

## Linux

```bash
cd qupath_extension_release
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
./build_extension.sh
./install_to_qupath.sh
```

## Runtime Environment Variables

Set these if QuPath cannot find Python or the pipeline:

```bash
export PROTOTYPE1_HOME="/path/to/qupath_extension_release"
export PROTOTYPE1_PYTHON="/path/to/qupath_extension_release/.venv/bin/python"
export PROTOTYPE1_PIPELINE="/path/to/qupath_extension_release/python/single_image_pathology_prototype.py"
```

`PROTOTYPE1_HOME` is required. Set it to the cloned repository root.
