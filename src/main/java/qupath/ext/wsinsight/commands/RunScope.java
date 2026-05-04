package qupath.ext.wsinsight.commands;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Captures what the user wants WSInsight to process: either a single image
 * or a batch of project images. Responsible for computing the common parent
 * directory that will be bind-mounted at {@code /slides} and for writing the
 * {@code image-list://} manifest when a multi-slide batch is requested.
 */
final class RunScope {

    private static final Logger logger = LoggerFactory.getLogger(RunScope.class);

    enum Kind { CURRENT_IMAGE, PROJECT_ALL, PROJECT_SELECTION }

    private final Kind kind;
    private final List<File> slideFiles;
    private final File slidesMountRoot;

    private RunScope(Kind kind, List<File> slideFiles, File slidesMountRoot) {
        this.kind = kind;
        this.slideFiles = Collections.unmodifiableList(slideFiles);
        this.slidesMountRoot = slidesMountRoot;
    }

    Kind kind() { return kind; }
    List<File> slideFiles() { return slideFiles; }
    File slidesMountRoot() { return slidesMountRoot; }

    /** True if this scope carries an explicit slide list (i.e. not preference-driven). */
    boolean hasExplicitSlides() { return !slideFiles.isEmpty(); }

    /**
     * If this scope selects more than one slide, write an
     * {@code image-list://}-compatible manifest under {@code resultsDir} and
     * return the corresponding container URI (e.g.
     * {@code image-list:///results/_wsinsight_slides.txt}). Returns {@code null}
     * when zero or one slide is selected (caller should use {@code --wsi-dir /slides}).
     */
    String writeImageListIfNeeded(File resultsDir) throws IOException {
        if (slideFiles.size() <= 1) return null;
        File listFile = new File(resultsDir, "_wsinsight_slides.txt");
        Path mountRoot = slidesMountRoot.toPath().toAbsolutePath();
        try (BufferedWriter w = Files.newBufferedWriter(listFile.toPath(), StandardCharsets.UTF_8)) {
            for (File slide : slideFiles) {
                Path abs = slide.toPath().toAbsolutePath();
                Path rel = mountRoot.relativize(abs);
                // Use forward slashes: the container is Linux regardless of host.
                w.write("/slides/" + rel.toString().replace(File.separatorChar, '/'));
                w.newLine();
            }
        }
        return "image-list:///results/_wsinsight_slides.txt";
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /**
     * Scope built from the currently-open image. Returns {@code null} if the
     * image's URI is not a local file.
     */
    static RunScope fromCurrentImage(QuPathGUI gui) {
        if (gui == null) return null;
        ImageData<?> data = gui.getImageData();
        if (data == null) return null;
        File slide = slideFileFromImageData(data);
        if (slide == null) return null;
        File parent = slide.getParentFile();
        if (parent == null) return null;
        return new RunScope(Kind.CURRENT_IMAGE, List.of(slide), parent);
    }

    /**
     * Scope covering every image in the project whose URI resolves to a local
     * file. Returns {@code null} if no entries are usable.
     */
    static RunScope fromProjectAll(Project<?> project) {
        if (project == null) return null;
        return fromProjectEntries(Kind.PROJECT_ALL, project.getImageList());
    }

    /** Scope covering a user-selected subset of project entries. */
    static RunScope fromProjectSelection(Collection<? extends ProjectImageEntry<?>> entries) {
        if (entries == null || entries.isEmpty()) return null;
        return fromProjectEntries(Kind.PROJECT_SELECTION, entries);
    }

    private static RunScope fromProjectEntries(
            Kind kind, Collection<? extends ProjectImageEntry<?>> entries) {
        List<File> files = new ArrayList<>();
        for (ProjectImageEntry<?> entry : entries) {
            File slide = slideFileFromEntry(entry);
            if (slide != null) files.add(slide);
        }
        if (files.isEmpty()) return null;
        File root = commonParent(files);
        if (root == null) return null;
        return new RunScope(kind, files, root);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Extract a local slide file from an open {@link ImageData}, or null. */
    private static File slideFileFromImageData(ImageData<?> data) {
        try {
            ImageServer<?> server = data.getServer();
            if (server == null) return null;
            for (URI uri : server.getURIs()) {
                File f = fileFromUri(uri);
                if (f != null) return f;
            }
        } catch (Throwable t) {
            logger.debug("Unable to extract slide file from ImageData: {}", t.toString());
        }
        return null;
    }

    /** Extract a local slide file from a project entry, or null. */
    private static File slideFileFromEntry(ProjectImageEntry<?> entry) {
        try {
            for (URI uri : entry.getURIs()) {
                File f = fileFromUri(uri);
                if (f != null) return f;
            }
            logger.info("WSInsight: skipping non-file project entry '{}'", entry.getImageName());
        } catch (IOException e) {
            logger.warn("WSInsight: failed to read URIs for entry '{}': {}",
                    entry.getImageName(), e.getMessage());
        }
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

    /** Deepest directory that is an ancestor of every file in the list. */
    private static File commonParent(List<File> files) {
        if (files.isEmpty()) return null;
        Path common = files.get(0).toPath().toAbsolutePath().getParent();
        for (int i = 1; i < files.size() && common != null; i++) {
            Path p = files.get(i).toPath().toAbsolutePath().getParent();
            common = commonAncestor(common, p);
        }
        return common == null ? null : common.toFile();
    }

    private static Path commonAncestor(Path a, Path b) {
        if (a == null || b == null) return null;
        Path candidate = a;
        while (candidate != null && !b.startsWith(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate;
    }
}
