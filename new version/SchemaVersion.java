package com.rcibs.rdv.api.business.corp.schema;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version x.y.z d'un fichier de schema (latest-x.y.z.json5).
 * Sait se lire depuis un texte, se comparer numeriquement (1.9.0 < 1.13.0) et calculer la suivante.
 */
record SchemaVersion(int major, int minor, int patch) implements Comparable<SchemaVersion> {

    /** Version du tout premier fichier, quand aucun latest-* n'existe encore. */
    static final SchemaVersion INITIAL = new SchemaVersion(1, 0, 0);

    private static final Pattern FORMAT = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private static final Comparator<SchemaVersion> ORDER = Comparator
            .comparingInt(SchemaVersion::major)
            .thenComparingInt(SchemaVersion::minor)
            .thenComparingInt(SchemaVersion::patch);

    /** "1.14.0" (espaces ignores) -> version ; IllegalArgumentException si le format n'est pas x.y.z. */
    static SchemaVersion parse(String text) {
        Matcher m = FORMAT.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Version invalide '" + text + "' (attendu : x.y.z)");
        }
        return new SchemaVersion(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    /** Texte vide ou null = "pas de version imposee" ; sinon meme comportement que {@link #parse}. */
    static Optional<SchemaVersion> parseOptional(String text) {
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(parse(text));
    }

    /** Version automatique suivante : on incremente le patch (1.13.0 -> 1.13.1). */
    SchemaVersion nextPatch() {
        return new SchemaVersion(major, minor, patch + 1);
    }

    @Override
    public int compareTo(SchemaVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
