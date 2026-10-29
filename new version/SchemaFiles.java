package com.rcibs.rdv.api.business.corp.schema;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tout ce qui touche aux fichiers latest-x.x.x.json5 d'un dossier : retrouver le dernier,
 * comparer son contenu a un schema, en ecrire un nouveau.
 * Ne sait rien du modele Java : il ne manipule que des Document et des fichiers.
 */
final class SchemaFiles {

    /** Nom des fichiers "derniere version" : latest-<major>.<minor>.<patch>.json5. */
    private static final Pattern LATEST_FILE = Pattern.compile("latest-(\\d+\\.\\d+\\.\\d+)\\.json5");

    /** Format d'ecriture : JSON indente sur 4 espaces, nombres en notation "relaxed". */
    private static final JsonWriterSettings WRITER = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(true)
            .indentCharacters("    ")
            .build();

    /** Fichier latest-x.x.x.json5 existant et sa version. */
    record LatestFile(Path path, SchemaVersion version) {
    }

    private final Path dir;

    SchemaFiles(Path dir) {
        this.dir = dir;
    }

    /** Schema au format texte, tel qu'ecrit dans le fichier (termine par un retour a la ligne). */
    static String toText(Document schema) {
        return schema.toJson(WRITER) + System.lineSeparator();
    }

    /** Fichier latest-* ayant la plus grande version (ex. 1.13.0 > 1.9.0 > 1.0.7), s'il en existe. */
    Optional<LatestFile> findLatest() throws IOException {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(SchemaFiles::toLatestFile)
                    .flatMap(Optional::stream)
                    .max(Comparator.comparing(LatestFile::version));
        }
    }

    private static Optional<LatestFile> toLatestFile(Path path) {
        Matcher m = LATEST_FILE.matcher(path.getFileName().toString());
        if (!m.matches()) {
            return Optional.empty(); // 1.0.0.json5, banner.txt, ... : ignores
        }
        return Optional.of(new LatestFile(path, SchemaVersion.parse(m.group(1))));
    }

    /**
     * Le fichier contient-il le meme schema que celui genere ? Comparaison semantique : on relit les
     * deux en Document, donc l'indentation, l'ordre des cles et les guillemets n'ont pas d'importance.
     * Un fichier illisible est considere comme different (il sera "remplace" par un nouveau).
     */
    static boolean isSameAs(LatestFile latest, Document generated) {
        try {
            Document existing = Document.parse(Files.readString(latest.path(), StandardCharsets.UTF_8));
            // Le schema genere repasse par le texte pour avoir les memes types que le fichier relu
            Document normalized = Document.parse(generated.toJson(WRITER));
            return existing.equals(normalized);
        } catch (Exception e) {
            return false;
        }
    }

    /** Ecrit latest-<version>.json5 dans le dossier (cree au besoin) et retourne le fichier. */
    Path write(SchemaVersion version, Document schema) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("latest-" + version + ".json5");
        Files.writeString(file, toText(schema), StandardCharsets.UTF_8);
        return file;
    }
}
