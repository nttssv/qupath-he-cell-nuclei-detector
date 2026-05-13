import qupath.lib.io.PathIO
import qupath.lib.common.ColorTools
import qupath.lib.gui.dialogs.Dialogs as PrototypeDialogs
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.regions.RegionRequest

import com.google.gson.JsonParser
import java.awt.BasicStroke
import java.awt.Font
import java.awt.RenderingHints
import java.awt.Color as AwtColor
import java.awt.geom.AffineTransform
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.regex.Pattern
import javax.imageio.ImageIO

import static qupath.lib.gui.scripting.QPEx.*

def prototypeHome = System.getenv("PROTOTYPE1_HOME")
if (prototypeHome == null || prototypeHome.isBlank()) {
    throw new IllegalStateException("Set PROTOTYPE1_HOME to the cloned qupath-he-cell-nuclei-detector folder before running Prototype 1.")
}
def prototypeRoot = new File(prototypeHome)
def defaultPipelineScript = new File(prototypeRoot, "python/single_image_pathology_prototype.py")
if (!defaultPipelineScript.exists()) {
    defaultPipelineScript = new File(prototypeRoot, "code/single_image_pathology_prototype.py")
}
def defaultPython = System.getProperty("os.name").toLowerCase().contains("win")
        ? new File(prototypeRoot, ".venv/Scripts/python.exe")
        : new File(prototypeRoot, ".venv/bin/python")
def pythonExe = new File(System.getenv("PROTOTYPE1_PYTHON") ?: defaultPython.getAbsolutePath())
def pipelineScript = new File(System.getenv("PROTOTYPE1_PIPELINE") ?: defaultPipelineScript.getAbsolutePath())
def outputRoot = new File(System.getenv("PROTOTYPE1_OUTPUT") ?: new File(prototypeRoot, "qupath_extension_runs").getAbsolutePath())

if (!prototypeRoot.exists()) {
    throw new FileNotFoundException("Missing Prototype 1 folder: " + prototypeRoot)
}
if (!pythonExe.exists()) {
    throw new FileNotFoundException("Missing Prototype 1 Python runtime: " + pythonExe)
}
if (!pipelineScript.exists()) {
    throw new FileNotFoundException("Missing Prototype 1 pipeline script: " + pipelineScript)
}

def imageData = getCurrentImageData()
if (imageData == null) {
    throw new IllegalStateException("Open an image before running Prototype 1.")
}

def params = new ParameterList()
        .addTitleParameter("Prototype 1 output")
        .addBooleanParameter("importCells", "Import cell boundaries", true, "Import clear/compact/uncertain cell region boundaries.")
        .addBooleanParameter("importNuclei", "Import nuclei", true, "Import nuclear objects.")
        .addBooleanParameter("importAsDetections", "Display like InstanSeg detections", true, "Convert imported polygons to detection objects so they display similarly to InstanSeg results.")
        .addBooleanParameter("selectImported", "Select imported objects after run", false, "Select imported Prototype 1 objects after import.")
        .addBooleanParameter("exportBeforeAfter", "Export before/after PNGs", false, "Save one raw ROI PNG and one ROI PNG with Prototype 1 overlays for each selected annotation.")
        .addBooleanParameter("showStatisticsDashboard", "Show statistics dashboard", true, "Create and display a visual summary of parenchyme, mesenchyme, nuclear, and cytoplasm measurements.")

boolean canShowDialog = false
try {
    canShowDialog = getQuPath() != null
} catch (Throwable ignored) {
    canShowDialog = false
}

if (canShowDialog) {
    def ok = PrototypeDialogs.showParameterDialog("Prototype 1", params)
    if (!ok) {
        return
    }
}

boolean importCells = params.getBooleanParameterValue("importCells")
boolean importNuclei = params.getBooleanParameterValue("importNuclei")
boolean importAsDetections = params.getBooleanParameterValue("importAsDetections")
boolean selectImported = params.getBooleanParameterValue("selectImported")
boolean exportBeforeAfter = params.getBooleanParameterValue("exportBeforeAfter")
boolean showStatisticsDashboard = params.getBooleanParameterValue("showStatisticsDashboard")

def hierarchy = imageData.getHierarchy()
def selected = hierarchy.getSelectionModel().getSelectedObjects()
        .findAll { it.hasROI() && it.isAnnotation() }

if (selected.isEmpty()) {
    throw new IllegalStateException("Select one or more annotation ROI objects before running Prototype 1.")
}

def server = imageData.getServer()
def pixelCalibration = server.getPixelCalibration()
boolean hasMicronCalibration = pixelCalibration != null && pixelCalibration.hasPixelSizeMicrons()
double pixelWidthMicrons = hasMicronCalibration ? pixelCalibration.getPixelWidthMicrons() : Double.NaN
double pixelHeightMicrons = hasMicronCalibration ? pixelCalibration.getPixelHeightMicrons() : Double.NaN
double pixelAreaMicrons = hasMicronCalibration ? pixelWidthMicrons * pixelHeightMicrons : Double.NaN
def imageName = server.getMetadata().getName()
        .replaceAll(/\.[^.]+$/, "")
        .replaceAll(/[^A-Za-z0-9_.-]/, "_")
def timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())
def runRoot = new File(outputRoot, imageName + "_" + timestamp)
def tileDir = new File(runRoot, "exported_tiles")
def processedRoot = new File(runRoot, "processed")
def previewDir = new File(runRoot, "before_after_png")
def dashboardDir = new File(runRoot, "statistics_dashboard")
tileDir.mkdirs()
processedRoot.mkdirs()
if (exportBeforeAfter) {
    previewDir.mkdirs()
}
if (showStatisticsDashboard) {
    dashboardDir.mkdirs()
}

def logFile = new File(runRoot, "prototype1_qupath_run.log")
logFile << "Prototype 1 QuPath run\n"
logFile << "Image: ${server.getMetadata().getName()}\n"
logFile << "Selected annotations: ${selected.size()}\n"
logFile << "Import cells: ${importCells}\n"
logFile << "Import nuclei: ${importNuclei}\n"
logFile << "Import as detections: ${importAsDetections}\n"
logFile << "Select imported: ${selectImported}\n\n"
logFile << "Export before/after PNGs: ${exportBeforeAfter}\n"
logFile << "Show statistics dashboard: ${showStatisticsDashboard}\n"
if (hasMicronCalibration) {
    logFile << "Pixel calibration: ${pixelWidthMicrons} um x ${pixelHeightMicrons} um = ${pixelAreaMicrons} um^2 per pixel\n\n"
} else {
    logFile << "Pixel calibration: unavailable in microns; only pixel area measurements will be added.\n\n"
}

def maxRegionPixels = 4096L * 4096L
def importedTotal = 0
def processed = []
def importedObjects = []
def dashboardFiles = []

selected.eachWithIndex { obj, index ->
    def roi = obj.getROI()
    int x0 = Math.max(0, Math.floor(roi.getBoundsX()) as int)
    int y0 = Math.max(0, Math.floor(roi.getBoundsY()) as int)
    int x1 = Math.min(server.getWidth(), Math.ceil(roi.getBoundsX() + roi.getBoundsWidth()) as int)
    int y1 = Math.min(server.getHeight(), Math.ceil(roi.getBoundsY() + roi.getBoundsHeight()) as int)
    int w = x1 - x0
    int h = y1 - y0

    if (w <= 0 || h <= 0) {
        logFile << "Skipping empty ROI ${index + 1}\n"
        return
    }
    if ((long) w * (long) h > maxRegionPixels) {
        throw new IllegalArgumentException("ROI ${index + 1} is too large (${w} x ${h}). Use a smaller annotation, ideally tile-sized.")
    }

    def tileFile = new File(tileDir, "${imageName}_roi${index + 1}_x${x0}_y${y0}_w${w}_h${h}.png")
    def request = RegionRequest.createInstance(server.getPath(), 1.0, x0, y0, w, h)
    writeImageRegion(server, request, tileFile.getAbsolutePath())
    logFile << "Exported ROI ${index + 1}: ${tileFile}\n"

    def command = [
            pythonExe.getAbsolutePath(),
            pipelineScript.getAbsolutePath(),
            tileFile.getAbsolutePath(),
            "--output-root",
            processedRoot.getAbsolutePath(),
            "--x-offset",
            Integer.toString(x0),
            "--y-offset",
            Integer.toString(y0)
    ]
    logFile << "Command: ${command.join(' ')}\n"

    def process = new ProcessBuilder(command)
            .directory(prototypeRoot)
            .redirectErrorStream(true)
            .start()
    def processOutput = process.getInputStream().getText("UTF-8")
    int exitCode = process.waitFor()
    logFile << processOutput << "\n"

    if (exitCode != 0) {
        throw new RuntimeException("Prototype 1 failed for ROI ${index + 1}; see log: ${logFile}")
    }

    def matcher = Pattern.compile("QuPath GeoJSON:\\s*(.+)").matcher(processOutput)
    if (!matcher.find()) {
        throw new RuntimeException("Prototype 1 completed but did not report a GeoJSON path for ROI ${index + 1}; see log: ${logFile}")
    }
    def geojsonFile = new File(matcher.group(1).trim())
    if (!geojsonFile.isAbsolute()) {
        geojsonFile = new File(prototypeRoot, matcher.group(1).trim())
    }
    if (!geojsonFile.exists()) {
        throw new FileNotFoundException("Prototype 1 GeoJSON not found: " + geojsonFile)
    }

    def summary = readPrototypeSummary(geojsonFile)
    def objects = readPrototypeObjects(geojsonFile, importCells, importNuclei, importAsDetections, pixelAreaMicrons)
    addObjects(objects)
    addPrototypeSummaryMeasurements(obj, summary, pixelWidthMicrons, pixelHeightMicrons, pixelAreaMicrons)
    if (exportBeforeAfter) {
        def beforeFile = new File(previewDir, "${imageName}_roi${index + 1}_before.png")
        def afterFile = new File(previewDir, "${imageName}_roi${index + 1}_after.png")
        Files.copy(tileFile.toPath(), beforeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        writeAnnotatedPreview(tileFile, afterFile, objects, x0, y0)
        logFile << "Before PNG: ${beforeFile}\n"
        logFile << "After PNG: ${afterFile}\n"
    }
    if (showStatisticsDashboard && !summary.isEmpty()) {
        def dashboardFile = new File(dashboardDir, "${imageName}_roi${index + 1}_statistics_dashboard.png")
        writeStatisticsDashboard(dashboardFile, summary, index + 1, server.getMetadata().getName(), pixelAreaMicrons)
        dashboardFiles.add(dashboardFile)
        logFile << "Statistics dashboard PNG: ${dashboardFile}\n"
    }
    importedObjects.addAll(objects)
    importedTotal += objects.size()
    processed.add([roi: index + 1, tile: tileFile, geojson: geojsonFile, objects: objects.size()])
    logFile << "Imported ${objects.size()} objects from ${geojsonFile}\n\n"
}

fireHierarchyUpdate()
if (selectImported && !importedObjects.isEmpty()) {
    hierarchy.getSelectionModel().selectObjects(importedObjects)
}
if (canShowDialog && !dashboardFiles.isEmpty()) {
    showStatisticsDashboardDialog(dashboardFiles[0])
}
print "Prototype 1 complete. Imported ${importedTotal} objects from ${processed.size()} ROI(s). Output: ${runRoot}"

def readPrototypeObjects(File geojsonFile, boolean includeCells, boolean includeNuclei, boolean asDetections, double pixelAreaMicrons) {
    def features = readGeoJsonProperties(geojsonFile)
    def objects = PathIO.readObjects(geojsonFile.toPath())
    def output = []
    objects.eachWithIndex { obj, idx ->
        def properties = idx < features.size() ? (features[idx] ?: [:]) : [:]
        def className = properties.qupath_class ?: "Unclassified"
        boolean isNucleus = className == "Nucleus"
        if (isNucleus && !includeNuclei) {
            return
        }
        if (!isNucleus && !includeCells) {
            return
        }

        def pathClass = resolvePrototypePathClass(className as String)
        def displayObject = obj
        if (asDetections) {
            displayObject = PathObjects.createDetectionObject(obj.getROI(), pathClass, obj.getMeasurementList())
            displayObject.getMetadata().putAll(obj.getMetadata())
        } else {
            displayObject.setPathClass(pathClass)
        }
        addNumericPropertiesAsMeasurements(displayObject, properties, pixelAreaMicrons)
        displayObject.setColor(colorForPrototypeClass(className as String))
        output.add(displayObject)
    }
    return output
}

def addNumericPropertiesAsMeasurements(def pathObject, Map properties, double pixelAreaMicrons) {
    def measurements = pathObject.getMeasurementList()
    properties.each { key, value ->
        if (value instanceof Number) {
            measurements.put("Prototype1: ${key}" as String, value.doubleValue())
            if (isValidPixelAreaMicrons(pixelAreaMicrons) && isPixelAreaMeasurement(key as String)) {
                measurements.put("Prototype1: ${convertedAreaName(key as String)}" as String, value.doubleValue() * pixelAreaMicrons)
            }
        }
    }
    measurements.close()
}

def writeAnnotatedPreview(File beforeFile, File afterFile, List objects, int xOffset, int yOffset) {
    def source = ImageIO.read(beforeFile)
    if (source == null) {
        throw new IOException("Unable to read exported ROI image: " + beforeFile)
    }
    def preview = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB)
    def graphics = preview.createGraphics()
    try {
        graphics.drawImage(source, 0, 0, null)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setStroke(new BasicStroke(Math.max(2.0f, Math.round(Math.max(source.getWidth(), source.getHeight()) / 512.0f) as float)))
        objects.findAll { getPrototypeClassName(it) != "Nucleus" }.each { drawPrototypeObject(graphics, it, xOffset, yOffset, false) }
        objects.findAll { getPrototypeClassName(it) == "Nucleus" }.each { drawPrototypeObject(graphics, it, xOffset, yOffset, true) }
    } finally {
        graphics.dispose()
    }
    ImageIO.write(preview, "PNG", afterFile)
}

def drawPrototypeObject(def graphics, def pathObject, int xOffset, int yOffset, boolean nucleus) {
    def roi = pathObject.getROI()
    if (roi == null) {
        return
    }
    try {
        def shape = roi.getShape()
        if (shape == null) {
            return
        }
        def localShape = AffineTransform.getTranslateInstance(-xOffset, -yOffset).createTransformedShape(shape)
        def color = awtColorForPrototypeClass(getPrototypeClassName(pathObject), nucleus ? 95 : 38)
        graphics.setColor(color)
        graphics.fill(localShape)
        graphics.setColor(awtColorForPrototypeClass(getPrototypeClassName(pathObject), 230))
        graphics.draw(localShape)
    } catch (UnsupportedOperationException ignored) {
        return
    }
}

String getPrototypeClassName(def pathObject) {
    def pathClass = pathObject.getPathClass()
    if (pathClass == null) {
        return "Unclassified"
    }
    try {
        return pathClass.getName()
    } catch (Throwable ignored) {
        return pathClass.toString()
    }
}

def awtColorForPrototypeClass(String className, int alpha) {
    def rgb = colorForPrototypeClass(className)
    return new AwtColor((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, alpha)
}

def readPrototypeSummary(File geojsonFile) {
    def runDir = geojsonFile.getParentFile()?.getParentFile()
    def summaryFile = runDir == null ? null : new File(runDir, "summary/summary_statistics.json")
    if (summaryFile == null || !summaryFile.exists()) {
        return [:]
    }
    return readNumericJsonObject(summaryFile)
}

def addPrototypeSummaryMeasurements(def annotationObject, Map summary, double pixelWidthMicrons, double pixelHeightMicrons, double pixelAreaMicrons) {
    def measurements = annotationObject.getMeasurementList()
    summary.each { key, value ->
        if (value instanceof Number) {
            measurements.put("Prototype1 summary: ${key}" as String, value.doubleValue())
            if (isValidPixelAreaMicrons(pixelAreaMicrons) && isPixelAreaMeasurement(key as String)) {
                measurements.put("Prototype1 summary: ${convertedAreaName(key as String)}" as String, value.doubleValue() * pixelAreaMicrons)
            }
        }
    }
    if (isValidPixelAreaMicrons(pixelAreaMicrons)) {
        measurements.put("Prototype1 summary: pixel_width_um", pixelWidthMicrons)
        measurements.put("Prototype1 summary: pixel_height_um", pixelHeightMicrons)
        measurements.put("Prototype1 summary: pixel_area_um2", pixelAreaMicrons)
    }
    measurements.close()
}

def writeStatisticsDashboard(File outputFile, Map summary, int roiIndex, String imageLabel, double pixelAreaMicrons) {
    int width = 1500
    int height = 1080
    def image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    def graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setColor(new AwtColor(248, 250, 252))
        graphics.fillRect(0, 0, width, height)

        drawDashboardHeader(graphics, imageLabel, roiIndex, pixelAreaMicrons)

        drawMetricCards(graphics, summary, pixelAreaMicrons, 60, 150, 1380, 145)
        drawStackedAreaPanel(
                graphics,
                "Tissue composition",
                [
                        [label: "Parenchyme", value: numeric(summary, "parenchyme_area_px"), color: new AwtColor(46, 204, 113)],
                        [label: "Mesenchyme / stroma", value: numeric(summary, "mesenchyme_area_px"), color: new AwtColor(231, 76, 60)]
                ],
                pixelAreaMicrons,
                60,
                330,
                660,
                285
        )
        drawStackedAreaPanel(
                graphics,
                "Parenchymal compartment",
                [
                        [label: "Nuclear area", value: numeric(summary, "parenchyme_nuclear_area_px"), color: new AwtColor(30, 110, 255)],
                        [label: "Cytoplasm area", value: numeric(summary, "parenchyme_cytoplasm_area_px"), color: new AwtColor(0, 180, 120)]
                ],
                pixelAreaMicrons,
                780,
                330,
                660,
                285
        )
        drawStackedAreaPanel(
                graphics,
                "Cytoplasm phenotype area",
                [
                        [label: "Clear", value: numeric(summary, "clear_cytoplasm_area_px"), color: new AwtColor(0, 210, 80)],
                        [label: "Compact", value: numeric(summary, "compact_cytoplasm_area_px"), color: new AwtColor(220, 40, 220)],
                        [label: "Uncertain", value: numeric(summary, "uncertain_cytoplasm_area_px"), color: new AwtColor(255, 165, 0)]
                ],
                pixelAreaMicrons,
                60,
                660,
                660,
                300
        )
        drawRatioPanel(graphics, summary, 780, 660, 660, 300)
    } finally {
        graphics.dispose()
    }
    ImageIO.write(image, "PNG", outputFile)
}

def drawDashboardHeader(def graphics, String imageLabel, int roiIndex, double pixelAreaMicrons) {
    graphics.setColor(new AwtColor(18, 24, 38))
    graphics.setFont(new Font("SansSerif", Font.BOLD, 38))
    graphics.drawString("Prototype 1 Morphometry Dashboard", 60, 70)
    graphics.setFont(new Font("SansSerif", Font.PLAIN, 20))
    graphics.setColor(new AwtColor(76, 86, 106))
    graphics.drawString("Image: ${imageLabel}   ROI: ${roiIndex}", 60, 108)
    def unitText = isValidPixelAreaMicrons(pixelAreaMicrons)
            ? "Area unit: um^2 (converted from calibrated QuPath pixel size)"
            : "Area unit: pixels (QuPath micron calibration unavailable)"
    graphics.drawString(unitText, 60, 134)
}

def drawMetricCards(def graphics, Map summary, double pixelAreaMicrons, int x, int y, int width, int height) {
    def cards = [
            [title: "Cells", value: formatInteger(numeric(summary, "cell_count")), subtitle: "detected regions"],
            [title: "Nuclei", value: formatInteger(numeric(summary, "nuclei_count")), subtitle: "${formatInteger(numeric(summary, "assigned_nuclei_count"))} assigned"],
            [title: "Parenchyme", value: formatPercent(numeric(summary, "parenchyme_proportion_of_tissue")), subtitle: formatArea(numeric(summary, "parenchyme_area_px"), pixelAreaMicrons)],
            [title: "Mesenchyme", value: formatPercent(numeric(summary, "mesenchyme_proportion_of_tissue")), subtitle: formatArea(numeric(summary, "mesenchyme_area_px"), pixelAreaMicrons)],
            [title: "N/C ratio", value: formatDecimal(numeric(summary, "parenchyme_n_c_ratio"), 3), subtitle: "parenchyme area ratio"]
    ]
    int gap = 18
    int cardWidth = ((width - gap * (cards.size() - 1)) / cards.size()) as int
    cards.eachWithIndex { card, idx ->
        int cx = x + idx * (cardWidth + gap)
        drawRoundedPanel(graphics, cx, y, cardWidth, height, new AwtColor(255, 255, 255))
        graphics.setColor(new AwtColor(89, 99, 120))
        graphics.setFont(new Font("SansSerif", Font.BOLD, 18))
        graphics.drawString(card.title as String, cx + 24, y + 36)
        graphics.setColor(new AwtColor(18, 24, 38))
        graphics.setFont(new Font("SansSerif", Font.BOLD, 34))
        graphics.drawString(card.value as String, cx + 24, y + 82)
        graphics.setColor(new AwtColor(100, 116, 139))
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 16))
        graphics.drawString(card.subtitle as String, cx + 24, y + 116)
    }
}

def drawStackedAreaPanel(def graphics, String title, List segments, double pixelAreaMicrons, int x, int y, int width, int height) {
    drawRoundedPanel(graphics, x, y, width, height, new AwtColor(255, 255, 255))
    graphics.setColor(new AwtColor(18, 24, 38))
    graphics.setFont(new Font("SansSerif", Font.BOLD, 24))
    graphics.drawString(title, x + 28, y + 42)

    double total = segments.collect { Math.max(0.0, it.value as double) }.sum() as double
    int barX = x + 28
    int barY = y + 74
    int barWidth = width - 56
    int barHeight = 48
    graphics.setColor(new AwtColor(226, 232, 240))
    graphics.fillRoundRect(barX, barY, barWidth, barHeight, 18, 18)

    int cursor = barX
    segments.eachWithIndex { segment, idx ->
        double fraction = total > 0 ? Math.max(0.0, segment.value as double) / total : 0.0
        int segmentWidth = idx == segments.size() - 1 ? (barX + barWidth - cursor) : Math.round(barWidth * fraction) as int
        graphics.setColor(segment.color as AwtColor)
        graphics.fillRoundRect(cursor, barY, Math.max(0, segmentWidth), barHeight, 18, 18)
        cursor += segmentWidth
    }

    int rowY = y + 158
    segments.each { segment ->
        graphics.setColor(segment.color as AwtColor)
        graphics.fillRoundRect(x + 30, rowY - 16, 20, 20, 6, 6)
        graphics.setColor(new AwtColor(51, 65, 85))
        graphics.setFont(new Font("SansSerif", Font.BOLD, 17))
        graphics.drawString(segment.label as String, x + 62, rowY)
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 16))
        def percent = total > 0 ? formatPercent((segment.value as double) / total) : "n/a"
        graphics.drawString("${percent}   ${formatArea(segment.value as double, pixelAreaMicrons)}", x + 300, rowY)
        rowY += 36
    }
}

def drawRatioPanel(def graphics, Map summary, int x, int y, int width, int height) {
    drawRoundedPanel(graphics, x, y, width, height, new AwtColor(255, 255, 255))
    graphics.setColor(new AwtColor(18, 24, 38))
    graphics.setFont(new Font("SansSerif", Font.BOLD, 24))
    graphics.drawString("Key ratios", x + 28, y + 42)

    def ratios = [
            [label: "Nuclear proportion of parenchyme", value: numeric(summary, "nuclear_proportion_of_parenchyme"), color: new AwtColor(30, 110, 255)],
            [label: "Cytoplasm proportion of parenchyme", value: numeric(summary, "cytoplasm_proportion_of_parenchyme"), color: new AwtColor(0, 180, 120)],
            [label: "Clear cytoplasm proportion", value: numeric(summary, "clear_cytoplasm_proportion"), color: new AwtColor(0, 210, 80)],
            [label: "Compact cytoplasm proportion", value: numeric(summary, "compact_cytoplasm_proportion"), color: new AwtColor(220, 40, 220)],
            [label: "Uncertain cytoplasm proportion", value: numeric(summary, "uncertain_cytoplasm_proportion"), color: new AwtColor(255, 165, 0)]
    ]

    int rowY = y + 82
    ratios.each { ratio ->
        graphics.setColor(new AwtColor(71, 85, 105))
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 16))
        graphics.drawString(ratio.label as String, x + 28, rowY)
        int barX = x + 300
        int barY = rowY - 16
        int barWidth = width - 390
        graphics.setColor(new AwtColor(226, 232, 240))
        graphics.fillRoundRect(barX, barY, barWidth, 18, 8, 8)
        graphics.setColor(ratio.color as AwtColor)
        graphics.fillRoundRect(barX, barY, Math.round(barWidth * clamp01(ratio.value as double)) as int, 18, 8, 8)
        graphics.setColor(new AwtColor(15, 23, 42))
        graphics.setFont(new Font("SansSerif", Font.BOLD, 15))
        graphics.drawString(formatPercent(ratio.value as double), x + width - 76, rowY)
        rowY += 40
    }
}

def drawRoundedPanel(def graphics, int x, int y, int width, int height, AwtColor color) {
    graphics.setColor(new AwtColor(203, 213, 225))
    graphics.fill(new RoundRectangle2D.Double(x + 2, y + 3, width, height, 24, 24))
    graphics.setColor(color)
    graphics.fill(new RoundRectangle2D.Double(x, y, width, height, 24, 24))
}

double numeric(Map map, String key) {
    def value = map[key]
    return value instanceof Number ? value.doubleValue() : 0.0
}

double clamp01(double value) {
    return Math.max(0.0, Math.min(1.0, value))
}

String formatInteger(double value) {
    return String.format("%,.0f", value)
}

String formatDecimal(double value, int decimals) {
    return String.format("%.${decimals}f", value)
}

String formatPercent(double value) {
    return String.format("%.1f%%", value * 100.0)
}

String formatArea(double areaPx, double pixelAreaMicrons) {
    if (isValidPixelAreaMicrons(pixelAreaMicrons)) {
        return String.format("%,.0f um^2", areaPx * pixelAreaMicrons)
    }
    return String.format("%,.0f px^2", areaPx)
}

def showStatisticsDashboardDialog(File dashboardFile) {
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

boolean isValidPixelAreaMicrons(double pixelAreaMicrons) {
    return !Double.isNaN(pixelAreaMicrons) && !Double.isInfinite(pixelAreaMicrons) && pixelAreaMicrons > 0
}

boolean isPixelAreaMeasurement(String name) {
    return name == "area" || name.endsWith("_area") || name.endsWith("_area_px")
}

String convertedAreaName(String name) {
    if (name.endsWith("_px")) {
        return name.substring(0, name.length() - 3) + "_um2"
    }
    return name + "_um2"
}

def readGeoJsonProperties(File geojsonFile) {
    def propertiesList = []
    geojsonFile.withReader("UTF-8") { reader ->
        def root = JsonParser.parseReader(reader).getAsJsonObject()
        def features = root.getAsJsonArray("features")
        if (features == null) {
            return propertiesList
        }
        features.each { feature ->
            def properties = [:]
            def propertyObject = feature.getAsJsonObject().getAsJsonObject("properties")
            if (propertyObject != null) {
                propertyObject.entrySet().each { entry ->
                    properties[entry.key] = gsonValueToGroovy(entry.value)
                }
            }
            propertiesList.add(properties)
        }
    }
    return propertiesList
}

def readNumericJsonObject(File jsonFile) {
    def output = [:]
    jsonFile.withReader("UTF-8") { reader ->
        def root = JsonParser.parseReader(reader).getAsJsonObject()
        root.entrySet().each { entry ->
            def value = gsonValueToGroovy(entry.value)
            if (value instanceof Number) {
                output[entry.key] = value
            }
        }
    }
    return output
}

def gsonValueToGroovy(def value) {
    if (value == null || value.isJsonNull()) {
        return null
    }
    if (!value.isJsonPrimitive()) {
        return value.toString()
    }
    def primitive = value.getAsJsonPrimitive()
    if (primitive.isNumber()) {
        return primitive.getAsDouble()
    }
    if (primitive.isBoolean()) {
        return primitive.getAsBoolean()
    }
    return primitive.getAsString()
}

def resolvePrototypePathClass(String className) {
    def color = colorForPrototypeClass(className)
    def pathClass = PathClass.getInstance(className, color)
    pathClass.setColor(color)
    return pathClass
}

int colorForPrototypeClass(String className) {
    switch (className) {
        case "Nucleus":
            return ColorTools.makeRGB(30, 110, 255)
        case "Clear cell":
            return ColorTools.makeRGB(0, 210, 80)
        case "Compact cell":
            return ColorTools.makeRGB(220, 40, 220)
        case "Uncertain cell":
        case "Uncertain region":
            return ColorTools.makeRGB(255, 165, 0)
        case "Mesenchyme":
        case "Stroma":
            return ColorTools.makeRGB(230, 40, 40)
        default:
            return ColorTools.makeRGB(255, 220, 0)
    }
}
