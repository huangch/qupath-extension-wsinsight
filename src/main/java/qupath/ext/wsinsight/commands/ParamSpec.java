package qupath.ext.wsinsight.commands;

import java.util.List;
import java.util.Objects;

/**
 * Declarative specification for a single WSInsight CLI parameter, used to
 * auto-render a JavaFX form in {@link GenericCommandDialog}.
 */
public final class ParamSpec {

    public enum Kind { STRING, INT, DOUBLE, BOOL_FLAG, PATH, CHOICE }

    /** CLI option name, e.g. "--wsi-dir". Use {@code null} for positional args. */
    public final String flag;
    /**
     * Secondary "off" form of a boolean flag, e.g. {@code --no-pin-memory},
     * or {@code null} when the flag has none. Required to express "off" for a
     * flag whose CLI default is on, where omitting the flag means "on".
     */
    public final String offFlag;
    public final String label;
    public final String help;
    public final Kind kind;
    public final String defaultValue;
    public final List<String> choices;
    /** If true and {@link #kind} == PATH, the value will be translated via PathMapper before being passed to wsinsight. */
    public final boolean translatePath;
    /** If true, the option is required; dialog refuses to launch when blank. */
    public final boolean required;
    /** Optional group key referencing a {@code GroupSpec} entry in the schema. {@code null} if ungrouped. */
    public final String group;
    /** When true, this main-grid param starts a new column (column 2). */
    public final boolean columnBreak;
    /** Token count the CLI expects; >1 for click tuple types like {@code type=(int, int)}. */
    public final int nargs;

    private ParamSpec(Builder b) {
        this.flag = b.flag;
        this.offFlag = b.offFlag;
        this.label = Objects.requireNonNull(b.label);
        this.help = b.help == null ? "" : b.help;
        this.kind = Objects.requireNonNull(b.kind);
        this.defaultValue = b.defaultValue == null ? "" : b.defaultValue;
        this.choices = b.choices == null ? List.of() : List.copyOf(b.choices);
        this.translatePath = b.translatePath;
        this.required = b.required;
        this.group = b.group;
        this.columnBreak = b.columnBreak;
        this.nargs = Math.max(1, b.nargs);
    }

    /**
     * Condition under which a group (or single param) is visible in the dialog.
     * Either {@link #equals} or {@link #isSet} is consulted; if both are null
     * the condition is always {@code true}.
     */
    public static final class VisibleWhen {
        /** Flag whose current value is inspected, e.g. "--ncomp". */
        public final String flag;
        /** Required textual value (case-insensitive) for the flag, or {@code null}. */
        public final String equals;
        /** If non-null, compares "set-ness" (non-blank and not "false") of the flag's value to this. */
        public final Boolean isSet;

        public VisibleWhen(String flag, String equals, Boolean isSet) {
            this.flag = flag;
            this.equals = equals;
            this.isSet = isSet;
        }

        public boolean test(String currentValue) {
            String v = currentValue == null ? "" : currentValue;
            if (isSet != null) {
                boolean set = !v.isBlank() && !"false".equalsIgnoreCase(v);
                return isSet == set;
            }
            if (equals != null) {
                return equals.equalsIgnoreCase(v);
            }
            return true;
        }
    }

    public static Builder builder() { return new Builder(); }

    public static ParamSpec stringOpt(String flag, String label, String defaultValue, String help) {
        return builder().flag(flag).label(label).kind(Kind.STRING).defaultValue(defaultValue).help(help).build();
    }

    public static ParamSpec intOpt(String flag, String label, String defaultValue, String help) {
        return builder().flag(flag).label(label).kind(Kind.INT).defaultValue(defaultValue).help(help).build();
    }

    public static ParamSpec boolFlag(String flag, String label, boolean defaultOn, String help) {
        return builder().flag(flag).label(label).kind(Kind.BOOL_FLAG)
                .defaultValue(Boolean.toString(defaultOn)).help(help).build();
    }

    public static ParamSpec path(String flag, String label, boolean translate, boolean required, String help) {
        return builder().flag(flag).label(label).kind(Kind.PATH)
                .translatePath(translate).required(required).help(help).build();
    }

    public static ParamSpec choice(String flag, String label, String defaultValue, List<String> choices, String help) {
        return builder().flag(flag).label(label).kind(Kind.CHOICE)
                .defaultValue(defaultValue).choices(choices).help(help).build();
    }

    public static final class Builder {
        private String flag;
        private String offFlag;
        private String label;
        private String help;
        private Kind kind;
        private String defaultValue;
        private List<String> choices;
        private boolean translatePath;
        private boolean required;
        private String group;
        private boolean columnBreak;
        private int nargs = 1;

        public Builder flag(String v) { this.flag = v; return this; }
        public Builder offFlag(String v) { this.offFlag = v; return this; }
        public Builder label(String v) { this.label = v; return this; }
        public Builder help(String v) { this.help = v; return this; }
        public Builder kind(Kind v) { this.kind = v; return this; }
        public Builder defaultValue(String v) { this.defaultValue = v; return this; }
        public Builder choices(List<String> v) { this.choices = v; return this; }
        public Builder translatePath(boolean v) { this.translatePath = v; return this; }
        public Builder required(boolean v) { this.required = v; return this; }
        public Builder group(String v) { this.group = v; return this; }
        public Builder columnBreak(boolean v) { this.columnBreak = v; return this; }
        public Builder nargs(int v) { this.nargs = v; return this; }
        public ParamSpec build() { return new ParamSpec(this); }
    }
}
