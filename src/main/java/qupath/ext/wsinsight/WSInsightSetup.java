package qupath.ext.wsinsight;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Singleton holding user preferences for the WSInsight QuPath extension.
 * <p>
 * Preferences are persisted via {@link PathPrefs} so they survive QuPath
 * restarts, and are exposed to each command class through getters.
 */
public class WSInsightSetup {

    private static final WSInsightSetup INSTANCE = new WSInsightSetup();

    private final StringProperty dockerBinaryProp =
            PathPrefs.createPersistentPreference("wsinsightDockerBinary", "docker");
    private final StringProperty dockerImageProp =
            PathPrefs.createPersistentPreference("wsinsightDockerImage", "huangchtw/wsinsight:latest");
    private final StringProperty gpusProp =
            PathPrefs.createPersistentPreference("wsinsightGpus", "all");
    private final StringProperty shmSizeProp =
            PathPrefs.createPersistentPreference("wsinsightShmSize", "32g");

    private final BooleanProperty useNativeProp =
            PathPrefs.createPersistentPreference("wsinsightUseNative", false);
    private final StringProperty nativeBinaryProp =
            PathPrefs.createPersistentPreference("wsinsightNativeBinary", "wsinsight");

    private final StringProperty wsiBackendProp =
            PathPrefs.createPersistentPreference("wsinsightWsiBackend", WSI_BACKEND_AUTO);

    private final StringProperty cliSchemaPathProp =
            PathPrefs.createPersistentPreference(
                    "wsinsightCliSchemaPath",
                    qupath.ext.wsinsight.commands.SchemaLoader.DEFAULT_PATH);

    private final StringProperty s3OptionsProp =
            PathPrefs.createPersistentPreference("wsinsightEnvS3Options", "");
    private final StringProperty cacheDirProp =
            PathPrefs.createPersistentPreference("wsinsightCacheDir", "");

    private final BooleanProperty useLocalModelsProp =
            PathPrefs.createPersistentPreference("wsinsightUseLocalModels", true);
    private final BooleanProperty experimentalProp =
            PathPrefs.createPersistentPreference("wsinsightExperimental", false);
    private final StringProperty gpusDetectedProp =
            PathPrefs.createPersistentPreference("wsinsightGpusDetected", "");

    /** Leave the slide-reading backend to wsinsight's own detection. */
    public static final String WSI_BACKEND_AUTO = "auto";

    private WSInsightSetup() {}

    public static WSInsightSetup getInstance() {
        return INSTANCE;
    }

    public StringProperty dockerBinaryProperty() { return dockerBinaryProp; }
    public StringProperty dockerImageProperty() { return dockerImageProp; }
    public StringProperty gpusProperty() { return gpusProp; }
    public StringProperty shmSizeProperty() { return shmSizeProp; }
    public BooleanProperty useNativeProperty() { return useNativeProp; }
    public StringProperty nativeBinaryProperty() { return nativeBinaryProp; }
    public StringProperty wsiBackendProperty() { return wsiBackendProp; }
    public StringProperty cliSchemaPathProperty() { return cliSchemaPathProp; }
    public StringProperty s3OptionsProperty() { return s3OptionsProp; }
    public StringProperty cacheDirProperty() { return cacheDirProp; }
    public BooleanProperty useLocalModelsProperty() { return useLocalModelsProp; }
    public BooleanProperty experimentalProperty() { return experimentalProp; }
    public StringProperty gpusDetectedProperty() { return gpusDetectedProp; }

    public String getDockerBinary() { return dockerBinaryProp.get(); }
    public String getDockerImage() { return dockerImageProp.get(); }
    public String getGpus() { return gpusProp.get(); }
    public String getShmSize() { return shmSizeProp.get(); }
    public boolean isUseNative() { return useNativeProp.get(); }
    public String getNativeBinary() { return nativeBinaryProp.get(); }
    public String getWsiBackend() { return wsiBackendProp.get(); }

    public String getCliSchemaPath() { return cliSchemaPathProp.get(); }
    public String getS3Options() { return s3OptionsProp.get(); }
    public String getCacheDir() { return cacheDirProp.get(); }
    public boolean isUseLocalModels() { return useLocalModelsProp.get(); }
    public boolean isExperimental() { return experimentalProp.get(); }
    public String getGpusDetected() { return gpusDetectedProp.get(); }
    public void setGpusDetected(String v) { gpusDetectedProp.set(v == null ? "" : v); }
}
