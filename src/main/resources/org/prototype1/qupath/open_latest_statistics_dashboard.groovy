def prototypeHome = System.getenv("PROTOTYPE1_HOME")
if (prototypeHome == null || prototypeHome.isBlank()) {
    throw new IllegalStateException("Set PROTOTYPE1_HOME to the cloned qupath-he-cell-nuclei-detector folder before opening dashboards.")
}

def prototypeRoot = new File(prototypeHome)
def outputRoot = new File(System.getenv("PROTOTYPE1_OUTPUT") ?: new File(prototypeRoot, "qupath_extension_runs").getAbsolutePath())
def latestDashboard = findLatestDashboard(outputRoot)
if (latestDashboard == null) {
    throw new FileNotFoundException("No statistics dashboard PNG found under: " + outputRoot)
}

showDashboardWindow(latestDashboard)
print "Latest Prototype 1 statistics dashboard: ${latestDashboard}"

def findLatestDashboard(File outputRoot) {
    if (!outputRoot.exists()) {
        return null
    }
    def files = []
    outputRoot.eachDir { runDir ->
        def dashboardDir = new File(runDir, "statistics_dashboard")
        if (dashboardDir.exists()) {
            dashboardDir.listFiles()?.findAll { it.isFile() && it.name.endsWith("_statistics_dashboard.png") }?.each {
                files.add(it)
            }
        }
    }
    return files ? files.max { it.lastModified() } : null
}

def showDashboardWindow(File dashboardFile) {
    try {
        def image = new javafx.scene.image.Image(dashboardFile.toURI().toString())
        def view = new javafx.scene.image.ImageView(image)
        view.setPreserveRatio(true)
        view.setFitWidth(Math.min(1100.0, image.getWidth()))
        def scroll = new javafx.scene.control.ScrollPane(view)
        scroll.setFitToWidth(true)
        scroll.setPrefViewportWidth(Math.min(1150.0, image.getWidth()))
        scroll.setPrefViewportHeight(Math.min(820.0, image.getHeight()))
        def stage = new javafx.stage.Stage()
        stage.setTitle("Prototype 1 Statistics Dashboard")
        stage.setScene(new javafx.scene.Scene(scroll))
        stage.setResizable(true)
        stage.show()
    } catch (Throwable ignored) {
        try {
            java.awt.Desktop.getDesktop().open(dashboardFile)
        } catch (Throwable ignoredAgain) {
            return
        }
    }
}

