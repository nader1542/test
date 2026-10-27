import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Garde-fou : le schema commite doit rester synchronise avec le modele Java.
 *
 * Pour regenerer le fichier :
 *   mvn test -Dtest=SchemaExporterTest -Dschema.update=true
 */
class SchemaExporterTest {

    @Test
    void committed_schema_should_match_java_model() throws Exception {
        Path file = SchemaExporter.TARGET;

        if (Boolean.getBoolean("schema.update") || Files.notExists(file)) {
            SchemaExporter.export(file);
            return;
        }

        // Document.parse accepte cles non quotees et quotes simples (format json5 du projet)
        Document expected = Document.parse(Files.readString(file));
        Document actual = SchemaExporter.generate();

        assertEquals(expected.toJson(), actual.toJson(),
                "Le schema Mongo est desynchronise du modele. "
                        + "Relancer avec -Dschema.update=true puis commiter 1.0.0.json5");
    }
}
