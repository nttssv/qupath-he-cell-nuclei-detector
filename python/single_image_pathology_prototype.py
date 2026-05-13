"""Single-image adrenal adenoma morphology prototype.

This is a deliberately small, debuggable pipeline for validating feature
extraction on one H&E image before scaling to WSI or cohort processing.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import ndimage
from skimage import color, feature, filters, io, measure, morphology, segmentation
from skimage.segmentation import watershed


@dataclass(frozen=True)
class PrototypeConfig:
    """Configurable thresholds for one-image validation."""

    # Tissue and nuclei
    tissue_min_saturation: float = 0.035
    tissue_max_brightness: float = 0.97
    tissue_min_area_px: int = 500
    nucleus_h_threshold: float = 0.46
    nucleus_max_brightness: float = 0.72
    nucleus_min_area_px: int = 18
    nucleus_max_area_px: int = 900
    nucleus_split_min_distance_px: int = 4

    # Cytoplasm and vacuolation
    cytoplasm_min_brightness: float = 0.48
    cytoplasm_max_eosin: float = 0.72
    cytoplasm_max_hematoxylin: float = 0.78
    cytoplasm_max_saturation: float = 0.78
    vacuole_min_brightness: float = 0.72
    vacuole_max_eosin: float = 0.34
    vacuole_max_saturation: float = 0.40
    vacuole_max_hematoxylin: float = 0.36
    vacuole_min_area_px: int = 40

    # Membrane inference
    texture_sigma_px: float = 2.0
    multiscale_sigmas: tuple[float, ...] = (0.0, 1.2, 2.4)
    membrane_score_threshold: float = 0.62
    weak_membrane_score_threshold: float = 0.44
    membrane_eosin_threshold: float = 0.46
    membrane_saturation_threshold: float = 0.22
    membrane_max_hematoxylin: float = 0.55
    vacuole_suppression_px: int = 2
    vacuole_edge_suppression: float = 0.35

    # Cell region watershed
    seed_min_distance_px: int = 24
    seed_min_distance_from_boundary_px: float = 8.0
    min_cell_area_px: int = 220
    max_cell_area_px: int = 9000
    min_clear_vacuolated_fraction: float = 0.08

    # Phenotype classification
    clear_min_brightness: float = 0.72
    clear_max_eosin: float = 0.48
    clear_max_saturation: float = 0.34
    compact_min_eosin: float = 0.26
    compact_max_brightness: float = 0.82
    compact_max_vacuolation_score: float = 0.65
    uncertain_min_cell_area_px: int = 120

    # Nucleus assignment
    nucleus_assignment_max_distance_px: float = 10.0

    # QuPath export
    qupath_geojson_max_points: int = 250


def robust_normalize(image: np.ndarray, low_pct: float = 1.0, high_pct: float = 99.0) -> np.ndarray:
    image = np.asarray(image, dtype=np.float32)
    low = float(np.percentile(image, low_pct))
    high = float(np.percentile(image, high_pct))
    if high <= low:
        return np.zeros(image.shape, dtype=np.uint8)
    return np.clip((image - low) / (high - low) * 255, 0, 255).astype(np.uint8)


def robust_unit(image: np.ndarray, low_pct: float = 1.0, high_pct: float = 99.0) -> np.ndarray:
    image = np.asarray(image, dtype=np.float32)
    low = float(np.percentile(image, low_pct))
    high = float(np.percentile(image, high_pct))
    if high <= low:
        return np.zeros(image.shape, dtype=np.float32)
    return np.clip((image - low) / (high - low), 0.0, 1.0)


def load_image(image_path: str | Path) -> np.ndarray:
    """Step 1: load one local RGB H&E image from disk."""

    image = io.imread(image_path)
    if image.ndim == 2:
        image = color.gray2rgb(image)
    if image.ndim == 3 and image.shape[-1] == 4:
        image = image[..., :3]
    if image.dtype != np.uint8:
        image = robust_normalize(image)
    return image


def rgb_float(image_rgb: np.ndarray) -> np.ndarray:
    rgb = np.asarray(image_rgb, dtype=np.float32)
    if rgb.max() > 1.0:
        rgb = rgb / 255.0
    return np.clip(rgb, 0.0, 1.0)


def he_features(image_rgb: np.ndarray) -> dict[str, np.ndarray]:
    """Compute H&E-oriented features used by all downstream steps."""

    rgb = rgb_float(image_rgb)
    hsv = color.rgb2hsv(rgb)
    hed = color.separate_stains((rgb * 255).astype(np.uint8), color.hed_from_rgb)
    return {
        "rgb": rgb,
        "hematoxylin": robust_unit(hed[:, :, 0]),
        "eosin": robust_unit(hed[:, :, 1]),
        "brightness": rgb.mean(axis=2),
        "saturation": hsv[:, :, 1],
        "blue_red_delta": rgb[:, :, 2] - rgb[:, :, 0],
    }


def clean_binary(mask: np.ndarray, *, min_area: int = 0, closing_px: int = 0, opening_px: int = 0) -> np.ndarray:
    out = np.asarray(mask, dtype=bool)
    if opening_px > 0:
        out = morphology.opening(out, morphology.disk(opening_px))
    if closing_px > 0:
        out = morphology.closing(out, morphology.disk(closing_px))
    if min_area > 0:
        labels = measure.label(out)
        kept = np.zeros_like(out, dtype=bool)
        for region in measure.regionprops(labels):
            if region.area >= min_area:
                kept[labels == region.label] = True
        out = kept
    return np.asarray(out, dtype=bool)


def tissue_mask(features: dict[str, np.ndarray], config: PrototypeConfig) -> np.ndarray:
    mask = (features["saturation"] >= config.tissue_min_saturation) | (
        features["brightness"] <= config.tissue_max_brightness
    )
    return clean_binary(mask, min_area=config.tissue_min_area_px, closing_px=2)


def split_nuclei(nucleus_pixels: np.ndarray, config: PrototypeConfig) -> np.ndarray:
    if not np.any(nucleus_pixels):
        return np.zeros(nucleus_pixels.shape, dtype=np.int32)

    distance = ndimage.distance_transform_edt(nucleus_pixels)
    maxima = morphology.local_maxima(distance) & (distance >= config.nucleus_split_min_distance_px)
    markers, count = ndimage.label(maxima)
    labels = measure.label(nucleus_pixels) if count == 0 else watershed(-distance, markers, mask=nucleus_pixels)

    nuclei = np.zeros_like(labels, dtype=np.int32)
    next_label = 1
    for region in measure.regionprops(labels):
        if config.nucleus_min_area_px <= region.area <= config.nucleus_max_area_px:
            nuclei[labels == region.label] = next_label
            next_label += 1
    return nuclei


def detect_nuclei(features: dict[str, np.ndarray], tissue: np.ndarray, config: PrototypeConfig) -> tuple[np.ndarray, pd.DataFrame]:
    """Step 2: detect nuclei and return instance mask plus morphometry."""

    nucleus_pixels = (
        tissue
        & (features["hematoxylin"] >= config.nucleus_h_threshold)
        & (features["brightness"] <= config.nucleus_max_brightness)
    )
    nucleus_pixels = clean_binary(nucleus_pixels, min_area=config.nucleus_min_area_px, opening_px=1, closing_px=1)
    nuclei_mask = split_nuclei(nucleus_pixels, config)

    rows = []
    for region in measure.regionprops(nuclei_mask):
        perimeter = float(region.perimeter)
        circularity = float(4 * np.pi * region.area / (perimeter * perimeter)) if perimeter > 0 else 0.0
        rows.append(
            {
                "nucleus_id": int(region.label),
                "centroid_x": float(region.centroid[1]),
                "centroid_y": float(region.centroid[0]),
                "area": float(region.area),
                "perimeter": perimeter,
                "circularity": circularity,
                "eccentricity": float(region.eccentricity),
            }
        )
    return nuclei_mask, pd.DataFrame(rows)


def local_texture(image: np.ndarray, sigma: float) -> np.ndarray:
    image = np.asarray(image, dtype=np.float32)
    mean = ndimage.gaussian_filter(image, sigma=sigma)
    mean_square = ndimage.gaussian_filter(image * image, sigma=sigma)
    return np.sqrt(np.maximum(mean_square - mean * mean, 0.0))


def local_entropy(image: np.ndarray, radius: int = 5) -> np.ndarray:
    """Fast local entropy approximation using binned local probabilities."""

    image = np.clip(np.asarray(image, dtype=np.float32), 0.0, 1.0)
    bins = np.clip((image * 8).astype(np.int32), 0, 7)
    window = 2 * radius + 1
    entropy = np.zeros(image.shape, dtype=np.float32)
    for bin_id in range(8):
        probability = ndimage.uniform_filter((bins == bin_id).astype(np.float32), size=window)
        positive = probability > 0
        entropy[positive] -= probability[positive] * np.log2(probability[positive])
    return np.clip(entropy / np.log2(8), 0.0, 1.0)


def multiscale_sobel(image: np.ndarray, sigmas: tuple[float, ...]) -> np.ndarray:
    gradients = []
    for sigma in sigmas:
        smoothed = ndimage.gaussian_filter(image, sigma=sigma) if sigma > 0 else image
        gradients.append(filters.sobel(smoothed))
    return robust_unit(np.maximum.reduce(gradients))


def detect_vacuolated_cytoplasm(
    features: dict[str, np.ndarray],
    tissue: np.ndarray,
    config: PrototypeConfig,
) -> tuple[np.ndarray, np.ndarray]:
    """Detect broad cytoplasm and lipid-rich vacuolated clear-cell compartments."""

    cytoplasm = (
        tissue
        & (features["brightness"] >= config.cytoplasm_min_brightness)
        & (features["eosin"] <= config.cytoplasm_max_eosin)
        & (features["hematoxylin"] <= config.cytoplasm_max_hematoxylin)
        & (features["saturation"] <= config.cytoplasm_max_saturation)
    )
    cytoplasm = morphology.remove_small_holes(cytoplasm, max_size=128)

    vacuolated = (
        cytoplasm
        & (features["brightness"] >= config.vacuole_min_brightness)
        & (features["eosin"] <= config.vacuole_max_eosin)
        & (features["saturation"] <= config.vacuole_max_saturation)
        & (features["hematoxylin"] <= config.vacuole_max_hematoxylin)
    )
    vacuolated = clean_binary(vacuolated, min_area=config.vacuole_min_area_px, opening_px=1)
    return np.asarray(cytoplasm, dtype=bool), vacuolated


def infer_membranes(
    features: dict[str, np.ndarray],
    cytoplasm: np.ndarray,
    vacuolated: np.ndarray,
    config: PrototypeConfig,
) -> dict[str, np.ndarray]:
    """Step 3: infer weak membranes while suppressing intracellular vacuole edges."""

    brightness_gradient = multiscale_sobel(features["brightness"], config.multiscale_sigmas)
    eosin_gradient = multiscale_sobel(features["eosin"], config.multiscale_sigmas)
    hematoxylin_gradient = multiscale_sobel(features["hematoxylin"], config.multiscale_sigmas)
    texture = local_texture(features["brightness"], sigma=config.texture_sigma_px)
    entropy = local_entropy(features["brightness"], radius=5)
    coarse_gradient = filters.sobel(ndimage.gaussian_filter(features["brightness"], sigma=max(config.multiscale_sigmas)))

    membrane_score = robust_unit(
        0.32 * brightness_gradient
        + 0.28 * eosin_gradient
        + 0.16 * hematoxylin_gradient
        + 0.14 * robust_unit(texture)
        + 0.10 * robust_unit(coarse_gradient)
    )
    weak_membrane_score = robust_unit(
        0.35 * membrane_score
        + 0.30 * robust_unit(entropy)
        + 0.20 * robust_unit(eosin_gradient)
        + 0.15 * robust_unit(coarse_gradient)
    )

    vacuole_interior = morphology.erosion(vacuolated, morphology.disk(config.vacuole_suppression_px))
    membrane_score = membrane_score.copy()
    weak_membrane_score = weak_membrane_score.copy()
    membrane_score[vacuole_interior] *= config.vacuole_edge_suppression
    weak_membrane_score[vacuole_interior] *= config.vacuole_edge_suppression

    pink_ridges = (
        (features["eosin"] >= config.membrane_eosin_threshold)
        & (features["saturation"] >= config.membrane_saturation_threshold)
        & (features["hematoxylin"] <= config.membrane_max_hematoxylin)
    )
    strong_membrane = cytoplasm & ~vacuole_interior & (
        (membrane_score >= config.membrane_score_threshold) | pink_ridges
    )
    weak_membrane = cytoplasm & ~vacuole_interior & (
        weak_membrane_score >= config.weak_membrane_score_threshold
    )
    strong_membrane = morphology.dilation(strong_membrane, morphology.disk(1))

    return {
        "membrane_score": membrane_score,
        "weak_membrane_score": weak_membrane_score,
        "strong_membrane": np.asarray(strong_membrane, dtype=bool),
        "weak_membrane": np.asarray(weak_membrane, dtype=bool),
        "entropy": entropy,
    }


def segment_cell_regions(
    cytoplasm: np.ndarray,
    vacuolated: np.ndarray,
    membrane_data: dict[str, np.ndarray],
    config: PrototypeConfig,
) -> tuple[np.ndarray, np.ndarray]:
    """Infer morphology-aware cell regions without Voronoi or nucleus expansion."""

    watershed_mask = cytoplasm & ~membrane_data["strong_membrane"]
    distance = ndimage.distance_transform_edt(watershed_mask)
    seed_source = clean_binary(vacuolated & watershed_mask, min_area=config.vacuole_min_area_px)
    coords = feature.peak_local_max(
        distance,
        min_distance=config.seed_min_distance_px,
        threshold_abs=config.seed_min_distance_from_boundary_px,
        labels=seed_source.astype(np.uint8),
        exclude_border=False,
    )
    markers = np.zeros(cytoplasm.shape, dtype=np.int32)
    for marker_id, (row, col) in enumerate(coords, start=1):
        markers[row, col] = marker_id
    if np.any(markers):
        markers = ndimage.grey_dilation(markers, size=(3, 3))

    if markers.max() == 0:
        return np.zeros(cytoplasm.shape, dtype=np.int32), np.zeros(cytoplasm.shape, dtype=np.int32)

    elevation = robust_unit(0.78 * membrane_data["membrane_score"] + 0.22 * membrane_data["weak_membrane_score"])
    raw = watershed(elevation, markers, mask=watershed_mask, watershed_line=True)

    accepted = np.zeros_like(raw, dtype=np.int32)
    uncertain = np.zeros_like(raw, dtype=np.int32)
    next_accepted = 1
    next_uncertain = 1
    for region in measure.regionprops(raw):
        pixels = raw == region.label
        if config.min_cell_area_px <= region.area <= config.max_cell_area_px:
            accepted[pixels] = next_accepted
            next_accepted += 1
        elif region.area >= config.uncertain_min_cell_area_px:
            uncertain[pixels] = next_uncertain
            next_uncertain += 1
    return accepted, uncertain


def assign_nuclei_to_regions(
    cell_regions: np.ndarray,
    nuclei_mask: np.ndarray,
    config: PrototypeConfig,
) -> dict[int, list[int]]:
    assignments = {int(region.label): [] for region in measure.regionprops(cell_regions)}
    if cell_regions.max() == 0 or nuclei_mask.max() == 0:
        return assignments

    distances, nearest = ndimage.distance_transform_edt(cell_regions == 0, return_indices=True)
    for nucleus in measure.regionprops(nuclei_mask):
        nucleus_pixels = nuclei_mask == nucleus.label
        overlapping = cell_regions[nucleus_pixels]
        overlapping = overlapping[overlapping > 0]
        if overlapping.size:
            labels, counts = np.unique(overlapping, return_counts=True)
            cell_id = int(labels[int(np.argmax(counts))])
        else:
            row, col = (int(round(v)) for v in nucleus.centroid)
            if not (0 <= row < cell_regions.shape[0] and 0 <= col < cell_regions.shape[1]):
                continue
            if distances[row, col] > config.nucleus_assignment_max_distance_px:
                continue
            cell_id = int(cell_regions[int(nearest[0, row, col]), int(nearest[1, row, col])])
        if cell_id > 0:
            assignments.setdefault(cell_id, []).append(int(nucleus.label))
    return assignments


def phenotype_from_features(row: dict, config: PrototypeConfig) -> str:
    """Step 4: classify clear, compact, or uncertain phenotype."""

    clear_like = (
        (row["vacuolation_score"] >= config.min_clear_vacuolated_fraction or row["mean_intensity"] >= config.clear_min_brightness)
        and row["mean_eosin"] <= config.clear_max_eosin
        and row["mean_saturation"] <= config.clear_max_saturation
    )
    compact_like = (
        (
            row["mean_eosin"] >= config.compact_min_eosin
            and row["vacuolation_score"] <= config.compact_max_vacuolation_score
        )
        or (
            row["mean_intensity"] <= config.compact_max_brightness
            and row["vacuolation_score"] < config.compact_max_vacuolation_score
        )
    )
    if compact_like and row["mean_eosin"] >= config.compact_min_eosin:
        return "compact"
    if clear_like and not compact_like:
        return "clear"
    if compact_like and not clear_like:
        return "compact"
    return "uncertain"


def extract_region_features(
    features: dict[str, np.ndarray],
    cell_regions: np.ndarray,
    uncertain_regions: np.ndarray,
    nuclei_mask: np.ndarray,
    nuclei_table: pd.DataFrame,
    vacuolated: np.ndarray,
    membrane_data: dict[str, np.ndarray],
    config: PrototypeConfig,
) -> pd.DataFrame:
    """Step 5: extract one row per detected cell/region."""

    assignments = assign_nuclei_to_regions(cell_regions, nuclei_mask, config)
    nuclei_lookup = nuclei_table.set_index("nucleus_id").to_dict(orient="index") if not nuclei_table.empty else {}
    rows = []

    for region in measure.regionprops(cell_regions):
        cell_pixels = cell_regions == region.label
        nucleus_labels = assignments.get(int(region.label), [])
        nuclear_area = float(sum(nuclei_lookup[label]["area"] for label in nucleus_labels if label in nuclei_lookup))
        cytoplasm_area = float(region.area)
        cell_area = cytoplasm_area + nuclear_area
        nc_ratio = nuclear_area / cytoplasm_area if cytoplasm_area > 0 else 0.0
        vacuolation_score = float(np.count_nonzero(vacuolated[cell_pixels])) / max(cytoplasm_area, 1.0)

        row = {
            "region_id": int(region.label),
            "source": "cell",
            "cell_area": cell_area,
            "cytoplasm_area": cytoplasm_area,
            "nuclear_area": nuclear_area,
            "nuclei_count": len(nucleus_labels),
            "nucleus_ids": ",".join(str(label) for label in nucleus_labels),
            "n_c_ratio": nc_ratio,
            "vacuolation_score": vacuolation_score,
            "mean_intensity": float(np.mean(features["brightness"][cell_pixels])),
            "mean_eosin": float(np.mean(features["eosin"][cell_pixels])),
            "mean_saturation": float(np.mean(features["saturation"][cell_pixels])),
            "texture_entropy": float(np.mean(membrane_data["entropy"][cell_pixels])),
            "local_density": float(np.mean(ndimage.gaussian_filter((cell_regions > 0).astype(np.float32), sigma=18)[cell_pixels])),
            "centroid_x": float(region.centroid[1]),
            "centroid_y": float(region.centroid[0]),
        }
        row["phenotype"] = phenotype_from_features(row, config)
        rows.append(row)

    for region in measure.regionprops(uncertain_regions):
        pixels = uncertain_regions == region.label
        rows.append(
            {
                "region_id": int(region.label),
                "source": "uncertain_fragment",
                "phenotype": "uncertain",
                "cell_area": float(region.area),
                "cytoplasm_area": float(region.area),
                "nuclear_area": 0.0,
                "nuclei_count": 0,
                "nucleus_ids": "",
                "n_c_ratio": 0.0,
                "vacuolation_score": float(np.count_nonzero(vacuolated[pixels])) / max(float(region.area), 1.0),
                "mean_intensity": float(np.mean(features["brightness"][pixels])),
                "mean_eosin": float(np.mean(features["eosin"][pixels])),
                "mean_saturation": float(np.mean(features["saturation"][pixels])),
                "texture_entropy": float(np.mean(membrane_data["entropy"][pixels])),
                "local_density": 0.0,
                "centroid_x": float(region.centroid[1]),
                "centroid_y": float(region.centroid[0]),
            }
        )

    return pd.DataFrame(rows)


def phenotype_masks(cell_regions: np.ndarray, table: pd.DataFrame) -> dict[str, np.ndarray]:
    masks = {
        "clear": np.zeros_like(cell_regions, dtype=np.int32),
        "compact": np.zeros_like(cell_regions, dtype=np.int32),
        "uncertain": np.zeros_like(cell_regions, dtype=np.int32),
    }
    if table.empty:
        return masks
    counters = {key: 1 for key in masks}
    for row in table[table["source"] == "cell"].to_dict(orient="records"):
        phenotype = row["phenotype"]
        if phenotype not in masks:
            phenotype = "uncertain"
        pixels = cell_regions == int(row["region_id"])
        masks[phenotype][pixels] = counters[phenotype]
        counters[phenotype] += 1
    return masks


def blend_overlay(image_rgb: np.ndarray, masks: list[tuple[np.ndarray, tuple[int, int, int], float]]) -> np.ndarray:
    overlay = image_rgb.copy().astype(np.float32)
    for mask, rgb, alpha in masks:
        pixels = np.asarray(mask, dtype=bool)
        color_array = np.asarray(rgb, dtype=np.float32)
        overlay[pixels] = (1.0 - alpha) * overlay[pixels] + alpha * color_array
    return np.clip(overlay, 0, 255).astype(np.uint8)


def boundary_overlay(image_rgb: np.ndarray, masks: list[tuple[np.ndarray, tuple[int, int, int]]]) -> np.ndarray:
    overlay = image_rgb.copy()
    for mask, rgb in masks:
        boundaries = segmentation.find_boundaries(np.asarray(mask, dtype=np.int32), mode="outer")
        overlay[boundaries] = np.asarray(rgb, dtype=np.uint8)
    return overlay


def save_image(path: Path, image: np.ndarray) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    if image.dtype != np.uint8:
        image = robust_normalize(image)
    io.imsave(path, image, check_contrast=False)
    return path


def json_safe(value):
    if isinstance(value, (np.integer, np.floating)):
        value = value.item()
    if isinstance(value, float) and np.isnan(value):
        return None
    return value


def contour_polygon_from_region(
    label_mask: np.ndarray,
    label: int,
    *,
    x_offset: int = 0,
    y_offset: int = 0,
    max_points: int = 250,
) -> list[list[float]] | None:
    rows, cols = np.where(label_mask == label)
    if rows.size == 0:
        return None

    row0, row1 = int(rows.min()), int(rows.max()) + 1
    col0, col1 = int(cols.min()), int(cols.max()) + 1
    object_mask = label_mask[row0:row1, col0:col1] == label
    padded = np.pad(object_mask.astype(np.uint8), 1, mode="constant")
    contours = measure.find_contours(padded, 0.5)
    if not contours:
        return None

    contour = max(contours, key=len)
    if max_points and len(contour) > max_points:
        keep = np.linspace(0, len(contour) - 1, max_points, dtype=int)
        contour = contour[keep]

    contour_rows = np.clip(contour[:, 0] - 1 + row0, 0, label_mask.shape[0] - 1)
    contour_cols = np.clip(contour[:, 1] - 1 + col0, 0, label_mask.shape[1] - 1)
    coords = [
        [float(col + x_offset), float(row + y_offset)]
        for row, col in zip(contour_rows, contour_cols, strict=False)
    ]
    if coords and coords[0] != coords[-1]:
        coords.append(coords[0])
    return coords


def row_to_qupath_properties(row: dict, class_name: str, object_kind: str) -> dict:
    properties = {
        "objectType": "annotation",
        "qupath_class": class_name,
        "prototype_object_kind": object_kind,
    }
    for key, value in row.items():
        properties[key] = json_safe(value)
    return properties


def add_mask_features(
    features: list[dict],
    label_mask: np.ndarray,
    table: pd.DataFrame,
    *,
    class_name_from_row,
    object_kind: str,
    id_prefix: str,
    x_offset: int,
    y_offset: int,
    max_points: int,
) -> None:
    if table.empty:
        return

    for row in table.to_dict(orient="records"):
        label = int(row["region_id"] if "region_id" in row else row["nucleus_id"])
        coords = contour_polygon_from_region(
            label_mask,
            label,
            x_offset=x_offset,
            y_offset=y_offset,
            max_points=max_points,
        )
        if not coords or len(coords) < 4:
            continue

        class_name = class_name_from_row(row)
        features.append(
            {
                "type": "Feature",
                "id": f"{id_prefix}_{label}",
                "geometry": {"type": "Polygon", "coordinates": [coords]},
                "properties": row_to_qupath_properties(row, class_name, object_kind),
            }
        )


def write_qupath_import_script(script_path: Path, geojson_path: Path) -> Path:
    script_path.parent.mkdir(parents=True, exist_ok=True)
    script = f"""import qupath.lib.io.PathIO
import qupath.lib.objects.classes.PathClass
import java.util.regex.Pattern

/*
 * Import single-image prototype annotations.
 * The GeoJSON intentionally stores class names as a plain `qupath_class`
 * property to avoid PathClass JSON parsing issues.
 * This script avoids the optional Groovy JSON module because some QuPath
 * builds do not include it.
 */

def geojsonFile = new File("{geojson_path.resolve()}")
if (!geojsonFile.exists()) {{
    throw new FileNotFoundException("Missing GeoJSON: " + geojsonFile)
}}

def text = geojsonFile.getText("UTF-8")
def matcher = Pattern.compile('"qupath_class"\\\\s*:\\\\s*"([^"]*)"').matcher(text)
def classes = []
while (matcher.find()) {{
    classes.add(matcher.group(1))
}}

def resolvePathClass(String className) {{
    try {{
        return getPathClass(className)
    }} catch (Throwable ignored) {{
        return PathClass.getInstance(className)
    }}
}}

def objects = PathIO.readObjects(geojsonFile.toPath())
if (classes.size() != objects.size()) {{
    print "Warning: found " + classes.size() + " classes for " + objects.size() + " objects"
}}
objects.eachWithIndex {{ obj, idx ->
    def className = idx < classes.size() ? classes[idx] : "Unclassified"
    obj.setPathClass(resolvePathClass(className as String))
}}

addObjects(objects)
fireHierarchyUpdate()
print "Imported " + objects.size() + " objects from " + geojsonFile
"""
    script_path.write_text(script, encoding="utf-8")
    return script_path


def export_qupath_geojson(
    output_dir: Path,
    cell_regions: np.ndarray,
    uncertain_regions: np.ndarray,
    nuclei_mask: np.ndarray,
    region_table: pd.DataFrame,
    nuclei_table: pd.DataFrame,
    config: PrototypeConfig,
    *,
    x_offset: int = 0,
    y_offset: int = 0,
) -> dict[str, Path]:
    """Export one QuPath-safe GeoJSON plus an import helper script."""

    qupath_dir = output_dir / "qupath"
    qupath_dir.mkdir(parents=True, exist_ok=True)
    features: list[dict] = []

    cells = region_table[region_table["source"] == "cell"].copy() if not region_table.empty else pd.DataFrame()
    uncertain = (
        region_table[region_table["source"] == "uncertain_fragment"].copy()
        if not region_table.empty
        else pd.DataFrame()
    )

    def cell_class(row: dict) -> str:
        phenotype = row.get("phenotype", "uncertain")
        return {
            "clear": "Clear cell",
            "compact": "Compact cell",
            "uncertain": "Uncertain cell",
        }.get(phenotype, "Uncertain cell")

    add_mask_features(
        features,
        cell_regions,
        cells,
        class_name_from_row=cell_class,
        object_kind="cell_region",
        id_prefix="cell",
        x_offset=x_offset,
        y_offset=y_offset,
        max_points=config.qupath_geojson_max_points,
    )
    add_mask_features(
        features,
        uncertain_regions,
        uncertain,
        class_name_from_row=lambda row: "Uncertain region",
        object_kind="uncertain_region",
        id_prefix="uncertain",
        x_offset=x_offset,
        y_offset=y_offset,
        max_points=config.qupath_geojson_max_points,
    )
    add_mask_features(
        features,
        nuclei_mask,
        nuclei_table,
        class_name_from_row=lambda row: "Nucleus",
        object_kind="nucleus",
        id_prefix="nucleus",
        x_offset=x_offset,
        y_offset=y_offset,
        max_points=config.qupath_geojson_max_points,
    )

    geojson = {
        "type": "FeatureCollection",
        "name": "single_image_pathology_prototype_annotations",
        "features": features,
    }
    geojson_path = qupath_dir / "single_image_annotations_qupath.geojson"
    geojson_path.write_text(json.dumps(geojson, indent=2), encoding="utf-8")
    script_path = write_qupath_import_script(qupath_dir / "import_single_image_annotations.groovy", geojson_path)
    return {"geojson": geojson_path, "import_script": script_path}


def save_visualizations(
    output_dir: Path,
    image_rgb: np.ndarray,
    nuclei_mask: np.ndarray,
    cell_regions: np.ndarray,
    uncertain_regions: np.ndarray,
    phenotype_mask_map: dict[str, np.ndarray],
    cytoplasm: np.ndarray,
    vacuolated: np.ndarray,
    membrane_data: dict[str, np.ndarray],
) -> dict[str, Path]:
    """Step 6.1: generate overlays for nuclei, cell boundaries, classes, and debug."""

    overlay_dir = output_dir / "overlays"
    paths = {
        "original": save_image(overlay_dir / "original.png", image_rgb),
        "nuclei_mask": save_image(overlay_dir / "nuclei_mask.png", (nuclei_mask > 0).astype(np.uint8) * 255),
        "nuclei_contours": save_image(
            overlay_dir / "nuclei_contours.png",
            boundary_overlay(image_rgb, [(nuclei_mask, (255, 255, 0))]),
        ),
        "vacuolated_cytoplasm": save_image(
            overlay_dir / "vacuolated_cytoplasm.png",
            blend_overlay(image_rgb, [(vacuolated, (0, 220, 220), 0.45)]),
        ),
        "weak_membranes": save_image(
            overlay_dir / "weak_membranes.png",
            blend_overlay(image_rgb, [(membrane_data["weak_membrane"], (255, 180, 0), 0.55)]),
        ),
        "cell_boundaries": save_image(
            overlay_dir / "cell_boundaries.png",
            boundary_overlay(image_rgb, [(cell_regions, (0, 180, 0)), (uncertain_regions, (255, 0, 0)), (nuclei_mask, (255, 255, 0))]),
        ),
    }

    phenotype_overlay = blend_overlay(
        image_rgb,
        [
            (phenotype_mask_map["clear"] > 0, (0, 220, 0), 0.35),
            (phenotype_mask_map["compact"] > 0, (0, 80, 255), 0.38),
            ((phenotype_mask_map["uncertain"] > 0) | (uncertain_regions > 0), (255, 180, 0), 0.35),
        ],
    )
    phenotype_overlay = boundary_overlay(
        phenotype_overlay,
        [
            (cell_regions, (0, 120, 0)),
            (uncertain_regions, (255, 80, 0)),
            (nuclei_mask, (255, 255, 0)),
        ],
    )
    paths["phenotype_overlay"] = save_image(overlay_dir / "phenotype_overlay.png", phenotype_overlay)
    paths["cytoplasm_mask"] = save_image(overlay_dir / "cytoplasm_mask.png", cytoplasm.astype(np.uint8) * 255)
    paths["membrane_score"] = save_image(overlay_dir / "membrane_score.png", membrane_data["membrane_score"])
    paths["texture_entropy"] = save_image(overlay_dir / "texture_entropy.png", membrane_data["entropy"])
    return paths


def safe_fraction(numerator: float, denominator: float) -> float:
    return float(numerator / denominator) if denominator > 0 else 0.0


def parse_nucleus_ids(value) -> list[int]:
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return []
    return [int(part) for part in str(value).split(",") if part.strip().isdigit()]


def summary_statistics(
    region_table: pd.DataFrame,
    nuclei_table: pd.DataFrame,
    tissue: np.ndarray | None = None,
) -> dict:
    """Step 6.3: concise quantitative summary."""

    cells = region_table[region_table["source"] == "cell"].copy() if not region_table.empty else pd.DataFrame()
    uncertain = (
        region_table[region_table["source"] == "uncertain_fragment"].copy()
        if not region_table.empty and "source" in region_table
        else pd.DataFrame()
    )
    total = max(len(cells), 1)
    phenotype = cells["phenotype"] if "phenotype" in cells else pd.Series(dtype=object)
    tissue_area = float(np.count_nonzero(tissue)) if tissue is not None else 0.0

    cell_cytoplasm_area = float(cells["cytoplasm_area"].sum()) if "cytoplasm_area" in cells else 0.0
    uncertain_cytoplasm_area = float(uncertain["cytoplasm_area"].sum()) if "cytoplasm_area" in uncertain else 0.0
    cytoplasm_area = cell_cytoplasm_area + uncertain_cytoplasm_area
    nuclear_area = float(cells["nuclear_area"].sum()) if "nuclear_area" in cells else 0.0
    parenchyme_area = cytoplasm_area + nuclear_area
    if tissue_area <= 0:
        tissue_area = parenchyme_area
    mesenchyme_area = max(tissue_area - parenchyme_area, 0.0)

    assigned_nucleus_ids: set[int] = set()
    if "nucleus_ids" in cells:
        for value in cells["nucleus_ids"]:
            assigned_nucleus_ids.update(parse_nucleus_ids(value))
    assigned_nuclei = (
        nuclei_table[nuclei_table["nucleus_id"].isin(assigned_nucleus_ids)]
        if assigned_nucleus_ids and not nuclei_table.empty
        else pd.DataFrame()
    )

    clear_cytoplasm_area = (
        float(cells.loc[cells["phenotype"] == "clear", "cytoplasm_area"].sum())
        if "phenotype" in cells and "cytoplasm_area" in cells
        else 0.0
    )
    compact_cytoplasm_area = (
        float(cells.loc[cells["phenotype"] == "compact", "cytoplasm_area"].sum())
        if "phenotype" in cells and "cytoplasm_area" in cells
        else 0.0
    )
    uncertain_cytoplasm_total = (
        float(cells.loc[cells["phenotype"] == "uncertain", "cytoplasm_area"].sum())
        if "phenotype" in cells and "cytoplasm_area" in cells
        else 0.0
    ) + uncertain_cytoplasm_area

    return {
        "cell_count": int(len(cells)),
        "uncertain_region_count": int((region_table["phenotype"] == "uncertain").sum())
        if not region_table.empty and "phenotype" in region_table
        else 0,
        "nuclei_count": int(len(nuclei_table)),
        "assigned_nuclei_count": int(len(assigned_nucleus_ids)),
        "tissue_area_px": tissue_area,
        "parenchyme_area_px": parenchyme_area,
        "mesenchyme_area_px": mesenchyme_area,
        "parenchyme_proportion_of_tissue": safe_fraction(parenchyme_area, tissue_area),
        "mesenchyme_proportion_of_tissue": safe_fraction(mesenchyme_area, tissue_area),
        "parenchyme_nuclear_area_px": nuclear_area,
        "parenchyme_cytoplasm_area_px": cytoplasm_area,
        "nuclear_proportion_of_parenchyme": safe_fraction(nuclear_area, parenchyme_area),
        "cytoplasm_proportion_of_parenchyme": safe_fraction(cytoplasm_area, parenchyme_area),
        "parenchyme_n_c_ratio": safe_fraction(nuclear_area, cytoplasm_area),
        "clear_cytoplasm_area_px": clear_cytoplasm_area,
        "compact_cytoplasm_area_px": compact_cytoplasm_area,
        "uncertain_cytoplasm_area_px": uncertain_cytoplasm_total,
        "clear_cytoplasm_proportion": safe_fraction(clear_cytoplasm_area, cytoplasm_area),
        "compact_cytoplasm_proportion": safe_fraction(compact_cytoplasm_area, cytoplasm_area),
        "uncertain_cytoplasm_proportion": safe_fraction(uncertain_cytoplasm_total, cytoplasm_area),
        "mean_cell_size_px": float(cells["cell_area"].mean()) if not cells.empty else 0.0,
        "mean_cytoplasm_size_px": float(cells["cytoplasm_area"].mean()) if not cells.empty else 0.0,
        "mean_nuclear_size_px": float(assigned_nuclei["area"].mean()) if not assigned_nuclei.empty else 0.0,
        "mean_cell_area": float(cells["cell_area"].mean()) if not cells.empty else 0.0,
        "mean_nuclear_area": float(nuclei_table["area"].mean()) if not nuclei_table.empty else 0.0,
        "mean_n_c_ratio": float(cells["n_c_ratio"].mean()) if not cells.empty else 0.0,
        "clear_region_count_proportion": float((phenotype == "clear").sum() / total),
        "compact_region_count_proportion": float((phenotype == "compact").sum() / total),
        "uncertain_region_count_proportion": float((phenotype == "uncertain").sum() / total),
    }


def create_output_dir(output_root: str | Path, image_path: Path) -> Path:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_dir = Path(output_root) / f"{image_path.stem}_{timestamp}"
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / "overlays").mkdir(exist_ok=True)
    (output_dir / "csv").mkdir(exist_ok=True)
    (output_dir / "summary").mkdir(exist_ok=True)
    (output_dir / "qupath").mkdir(exist_ok=True)
    return output_dir


def run_single_image_pipeline(
    image_path: str | Path,
    *,
    output_root: str | Path = "single_image_prototype_runs",
    config: PrototypeConfig | None = None,
    x_offset: int = 0,
    y_offset: int = 0,
) -> dict:
    """End-to-end prototype for one existing pathology image."""

    config = config or PrototypeConfig()
    image_path = Path(image_path)
    image_rgb = load_image(image_path)
    features = he_features(image_rgb)
    tissue = tissue_mask(features, config)

    nuclei_mask, nuclei_table = detect_nuclei(features, tissue, config)
    cytoplasm, vacuolated = detect_vacuolated_cytoplasm(features, tissue, config)
    membrane_data = infer_membranes(features, cytoplasm, vacuolated, config)
    cell_regions, uncertain_regions = segment_cell_regions(cytoplasm, vacuolated, membrane_data, config)
    region_table = extract_region_features(
        features,
        cell_regions,
        uncertain_regions,
        nuclei_mask,
        nuclei_table,
        vacuolated,
        membrane_data,
        config,
    )
    masks = phenotype_masks(cell_regions, region_table)
    summary = summary_statistics(region_table, nuclei_table, tissue)

    output_dir = create_output_dir(output_root, image_path)
    overlay_paths = save_visualizations(
        output_dir,
        image_rgb,
        nuclei_mask,
        cell_regions,
        uncertain_regions,
        masks,
        cytoplasm,
        vacuolated,
        membrane_data,
    )
    qupath_paths = export_qupath_geojson(
        output_dir,
        cell_regions,
        uncertain_regions,
        nuclei_mask,
        region_table,
        nuclei_table,
        config,
        x_offset=x_offset,
        y_offset=y_offset,
    )

    csv_dir = output_dir / "csv"
    summary_dir = output_dir / "summary"
    region_table.to_csv(csv_dir / "cell_region_features.csv", index=False)
    nuclei_table.to_csv(csv_dir / "nuclei_features.csv", index=False)
    pd.DataFrame([summary]).to_csv(summary_dir / "summary_statistics.csv", index=False)
    (summary_dir / "summary_statistics.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (summary_dir / "parameters.json").write_text(json.dumps(asdict(config), indent=2), encoding="utf-8")

    return {
        "image_path": str(image_path),
        "output_dir": output_dir,
        "summary": summary,
        "overlays": overlay_paths,
        "qupath": qupath_paths,
        "regions": region_table,
        "nuclei": nuclei_table,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "image_path",
        nargs="?",
        default="sample/tile_1488_x83811_y10131.png",
        type=Path,
        help="One local H&E adrenal adenoma image file.",
    )
    parser.add_argument("--output-root", type=Path, default=Path("single_image_prototype_runs"))
    parser.add_argument("--x-offset", type=int, default=0, help="Optional x offset for WSI-coordinate GeoJSON export.")
    parser.add_argument("--y-offset", type=int, default=0, help="Optional y offset for WSI-coordinate GeoJSON export.")
    args = parser.parse_args()

    result = run_single_image_pipeline(
        args.image_path,
        output_root=args.output_root,
        x_offset=args.x_offset,
        y_offset=args.y_offset,
    )
    print(json.dumps(result["summary"], indent=2))
    print(f"Output: {result['output_dir']}")
    print(f"QuPath GeoJSON: {result['qupath']['geojson']}")
    print(f"QuPath import script: {result['qupath']['import_script']}")


if __name__ == "__main__":
    main()
