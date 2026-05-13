import java.awt.Desktop

def prototypeHome = System.getenv("PROTOTYPE1_HOME")
if (prototypeHome == null || prototypeHome.isBlank()) {
    throw new IllegalStateException("Set PROTOTYPE1_HOME to the cloned qupath-he-cell-nuclei-detector folder before opening the README.")
}
def extensionRoot = new File(prototypeHome)
def readme = new File(extensionRoot, "README.md")
if (!readme.exists()) {
    throw new FileNotFoundException("Missing Prototype 1 extension README: " + readme)
}
Desktop.getDesktop().open(readme)
