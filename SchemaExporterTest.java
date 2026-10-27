import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Garde-fou : le dernier schema commite (latest-x.x.x.json5) doit rester synchronise avec le
 * modele Java et les descriptions des DTO.
 *
 * Pour creer le nouveau schema (uniquement si le modele a change, nom automatique) :
 *   mvn test -Dtest=SchemaExporterTest -Dschema.update=true
 * Pour choisir la version a la main :
 *   mvn test -Dtest=SchemaExporterTest -Dschema.update=true -Dschema.version=1.14.0
 */
class SchemaExporterTest {

    @Test
    void latest_schema_should_match_java_model() throws Exception {
        Path dir = SchemaExporter.SCHEMA_DIR;

        // Mode mise a jour : on cree le nouveau fichier (si besoin) et on s'arrete
        if (Boolean.getBoolean("schema.update")) {
            Optional<Path> written = SchemaExporter.exportIfChanged(
                    dir, System.getProperty(SchemaExporter.VERSION_PROPERTY));
            written.ifPresent(file -> System.out.println("Schema ecrit : " + file));
            return;
        }

        // Mode controle (CI) : echec si le modele a change sans nouveau schema commite
        assertFalse(SchemaExporter.hasChanged(dir),
                "Le schema Mongo est desynchronise du modele. "
                        + "Relancer avec -Dschema.update=true puis commiter le nouveau latest-x.x.x.json5");
    }
}
