import qupath.lib.io.PathIO
import qupath.lib.common.ColorTools
import qupath.lib.gui.dialogs.Dialogs as PrototypeDialogs
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.plugins.parameters.ParameterList
import qupath.lib.regions.RegionRequest

import com.google.gson.JsonParser
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.Color as AwtColor
import java.awt.geom.AffineTransform
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
tileDir.mkdirs()
processedRoot.mkdirs()
if (exportBeforeAfter) {
    previewDir.mkdirs()
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
if (hasMicronCalibration) {
    logFile << "Pixel calibration: ${pixelWidthMicrons} um x ${pixelHeightMicrons} um = ${pixelAreaMicrons} um^2 per pixel\n\n"
} else {
    logFile << "Pixel calibration: unavailable in microns; only pixel area measurements will be added.\n\n"
}

def maxRegionPixels = 4096L * 4096L
def importedTotal = 0
def processed = []
def importedObjects = []

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

    def objects = readPrototypeObjects(geojsonFile, importCells, importNuclei, importAsDetections, pixelAreaMicrons)
    addObjects(objects)
    addPrototypeSummaryMeasurements(obj, geojsonFile, pixelWidthMicrons, pixelHeightMicrons, pixelAreaMicrons)
    if (exportBeforeAfter) {
        def beforeFile = new File(previewDir, "${imageName}_roi${index + 1}_before.png")
        def afterFile = new File(previewDir, "${imageName}_roi${index + 1}_after.png")
        Files.copy(tileFile.toPath(), beforeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        writeAnnotatedPreview(tileFile, afterFile, objects, x0, y0)
        logFile << "Before PNG: ${beforeFile}\n"
        logFile << "After PNG: ${afterFile}\n"
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

def addPrototypeSummaryMeasurements(def annotationObject, File geojsonFile, double pixelWidthMicrons, double pixelHeightMicrons, double pixelAreaMicrons) {
    def runDir = geojsonFile.getParentFile()?.getParentFile()
    def summaryFile = runDir == null ? null : new File(runDir, "summary/summary_statistics.json")
    if (summaryFile == null || !summaryFile.exists()) {
        return
    }

    def summary = readNumericJsonObject(summaryFile)
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
