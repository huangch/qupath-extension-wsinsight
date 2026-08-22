package qupath.ext.wsinsight.commands;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Finds WSInsight GeoJSON outputs under the host results root and imports
 * them back into the matching QuPath image(s).
 */
final class AutoImport {

    private static final Logger logger = LoggerFactory.getLogger(AutoImport.class);

    private static final List<String> GEOJSON_SUBDIRS = List.of(
            "export-geojson",
            "export-niche-regions-geojson",
            "model-outputs-geojson",
            "niche-outputs-geojson",
            "niche-outputs-geojson/cells",
            "niche-outputs-geojson/niches");

    private AutoImport() {}

    /** Scope-aware entry point. Heavy work runs on a background thread. */
    static void importResults(File resultsDir, RunScope scope, Project<?> project) {
        if (resultsDir == null || !resultsDir.isDirectory()) return;
        if (scope == null || !scope.hasExplicitSlides()) {
            importForOpenImage(resultsDir);
            return;
        }
        new Thread(() -> importForScope(resultsDir, scope, project),
                "wsinsight-auto-import").start();
    }

    private static void importForOpenImage(File resultsDir) {
        QuPathGUI qupath = QuPathGUI.getInstance();
        if (qupath == null) return;
        ImageData<?> imageData = qupath.getImageData();
        if (imageData == null) {
            logger.info("WSInsight: no image open and no scope provided - skipping auto-import.");
            return;
        }
        String stem = stemOf(qupath.getDisplayedImageName(castImageData(imageData)));
        if (stem == null || stem.isBlank()) return;
        List<PathObject> objects = loadObjectsForStem(resultsDir, stem);
        if (objects.isEmpty()) {
            notify(anyGeoJsonDirExists(resultsDir)
                    ? "No GeoJSON output matched '" + stem + "'."
                    : "The run wrote no GeoJSON. Re-run with --export-geojson enabled "
                          + "so results can be imported.");
            return;
        }
        Platform.runLater(() -> {
            imageData.getHierarchy().addObjects(objects);
            imageData.getHierarchy().resolveHierarchy();
            notify("Imported " + objects.size() + " object(s) into current image.");
        });
    }

    private static void importForScope(File resultsDir, RunScope scope, Project<?> project) {
        QuPathGUI qupath = QuPathGUI.getInstance();
        File openSlide = openSlideFile(qupath);
        Map<File, ProjectImageEntry<?>> entryByFile = new HashMap<>();
        if (project != null) {
            for (ProjectImageEntry<?> entry : project.getImageList()) {
                File f = entryFile(entry);
                if (f != null) entryByFile.put(f, entry);
            }
        }

        int totalObjects = 0;
        int totalImages = 0;
        List<String> skipped = new ArrayList<>();
        for (File slide : scope.slideFiles()) {
            String stem = stemOf(slide.getName());
            List<PathObject> objects = loadObjectsForStem(resultsDir, stem);
            if (objects.isEmpty()) {
                skipped.add(slide.getName() + " (no GeoJSON match)");
                continue;
            }
            if (openSlide != null && sameFile(openSlide, slide) && qupath != null
                    && qupath.getImageData() != null) {
                final ImageData<?> data = qupath.getImageData();
                Platform.runLater(() -> {
                    data.getHierarchy().addObjects(objects);
                    data.getHierarchy().resolveHierarchy();
                });
                totalObjects += objects.size();
                totalImages += 1;
                continue;
            }
            ProjectImageEntry<?> entry = entryByFile.get(slide);
            if (entry == null) {
                skipped.add(slide.getName() + " (no matching project entry)");
                continue;
            }
            try {
                importIntoEntry(entry, objects);
                totalObjects += objects.size();
                totalImages += 1;
            } catch (IOException e) {
                logger.warn("WSInsight: failed to import into '{}': {}",
                        entry.getImageName(), e.getMessage());
                skipped.add(entry.getImageName() + " (" + e.getMessage() + ")");
            }
        }
        final int nObj = totalObjects;
        final int nImg = totalImages;
        final List<String> skippedFinal = skipped;
        final boolean noGeoJsonAtAll = !anyGeoJsonDirExists(resultsDir);
        Platform.runLater(() -> {
            if (nObj == 0) {
                notify(noGeoJsonAtAll
                        ? "The run wrote no GeoJSON. Re-run with --export-geojson enabled "
                          + "so results can be imported."
                        : "No GeoJSON outputs matched the selected slides.");
            } else {
                String msg = "Imported " + nObj + " object(s) into " + nImg + " image(s).";
                if (!skippedFinal.isEmpty()) msg += " Skipped: " + String.join(", ", skippedFinal) + ".";
                notify(msg);
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void importIntoEntry(ProjectImageEntry<?> entry, List<PathObject> objects)
            throws IOException {
        ImageData data = entry.readImageData();
        if (data == null) return;
        PathObjectHierarchy hierarchy = data.getHierarchy();
        hierarchy.addObjects(objects);
        hierarchy.resolveHierarchy();
        entry.saveImageData(data);
    }

    private static List<PathObject> loadObjectsForStem(File resultsDir, String stem) {
        if (stem == null || stem.isBlank()) return List.of();
        List<File> matches = findGeoJsonForStem(resultsDir, stem);
        if (matches.isEmpty()) return List.of();
        List<PathObject> all = new ArrayList<>();
        for (File f : matches) {
            try {
                List<PathObject> objs = PathIO.readObjects(f.toPath());
                if (objs != null) all.addAll(objs);
            } catch (IOException e) {
                logger.warn("WSInsight: failed to read {}: {}", f, e.getMessage());
            }
        }
        return all;
    }

    /** True when at least one known GeoJSON output directory is present. */
    private static boolean anyGeoJsonDirExists(File resultsDir) {
        for (String sub : GEOJSON_SUBDIRS) {
            if (new File(resultsDir, sub).isDirectory()) return true;
        }
        return false;
    }

    private static List<File> findGeoJsonForStem(File resultsDir, String stem) {
        List<File> out = new ArrayList<>();
        for (String sub : GEOJSON_SUBDIRS) {
            File dir = new File(resultsDir, sub);
            if (!dir.isDirectory()) continue;
            try (Stream<Path> walk = Files.list(dir.toPath())) {
                walk.filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(f -> matchesStem(f.getName(), stem))
                        .forEach(out::add);
            } catch (IOException e) {
                logger.debug("WSInsight: could not list {}: {}", dir, e.getMessage());
            }
        }
        return out;
    }

    private static boolean matchesStem(String filename, String stem) {
        String fn = filename.toLowerCase(Locale.ROOT);
        String st = stem.toLowerCase(Locale.ROOT);
        if (!(fn.endsWith(".geojson") || fn.endsWith(".json"))) return false;
        int dot = fn.lastIndexOf('.');
        String base = dot > 0 ? fn.substring(0, dot) : fn;
        return base.equals(st) || base.startsWith(st + ".") || base.startsWith(st + "_");
    }

    private static String stemOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static File entryFile(ProjectImageEntry<?> entry) {
        try {
            for (URI uri : entry.getURIs()) {
                File f = fileFromUri(uri);
                if (f != null) return f;
            }
        } catch (IOException ignored) { }
        return null;
    }

    private static File openSlideFile(QuPathGUI gui) {
        if (gui == null) return null;
        ImageData<?> data = gui.getImageData();
        if (data == null || data.getServer() == null) return null;
        try {
            for (URI uri : data.getServer().getURIs()) {
                File f = fileFromUri(uri);
                if (f != null) return f;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static File fileFromUri(URI uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if (scheme != null && !"file".equalsIgnoreCase(scheme)) return null;
        try {
            return new File(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    @SuppressWarnings("unchecked")
    private static ImageData<java.awt.image.BufferedImage> castImageData(ImageData<?> d) {
        return (ImageData<java.awt.image.BufferedImage>) d;
    }

    private static void notify(String msg) {
        try {
            Dialogs.showInfoNotification("WSInsight", msg);
        } catch (Throwable ignored) {
            logger.info("WSInsight: {}", msg);
        }
    }
}
