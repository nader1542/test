package com.rcibs.rdv.api.business.corp.schema;

import com.rcibs.rdv.api.business.corp.service.actors.dto.ActorDto;
import io.swagger.v3.oas.annotations.media.Schema;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test unitaire unique de SchemaExporter : un seul scenario qui deroule, dans l'ordre, la vie
 * d'un dossier de schemas (vide -> premier schema -> modele modifie -> version manuelle -> erreurs).
 *
 * Il couvre : generate() (structure Spring Data, bsonType, required, _class, descriptions des DTO
 * via les @Mapping de ActorMapper et le scan des mappers), generateAsString(), hasChanged(),
 * exportIfChanged() avec version automatique ou manuelle, la recherche du dernier fichier
 * latest-x.x.x.json5, la comparaison de versions et la comparaison semantique des schemas.
 * Non couvert : main(), qui ecrit dans le vrai dossier src/main/resources.
 *
 * Les fichiers sont crees dans un dossier temporaire : rien n'est ecrit dans le projet.
 * A lancer depuis la racine du module rdv-api (SchemaExporter lit ActorMapper.java par chemin relatif).
 */
class SchemaExporterUnitTest {

    /** Schema sur une seule ligne : sert a verifier que seul le contenu compte, pas la mise en forme. */
    private static final JsonWriterSettings COMPACT = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(false)
            .build();

    @Test
    void schema_exporter_full_scenario(@TempDir Path dir) throws Exception {

        // ------------------------------------------------------------------
        // 1. Contenu du schema genere depuis le vrai modele
        // ------------------------------------------------------------------
        Document root = SchemaExporter.generate();
        assertEquals(root, SchemaExporter.generate(), "La generation doit etre deterministe");

        Document schema = root.get("$jsonSchema", Document.class);
        assertNotNull(schema, "Le document racine doit contenir $jsonSchema");
        assertEquals("object", schema.getString("bsonType"));
        assertEquals(List.of("_id", "type", "role", "status", "countryDatabase"),
                schema.getList("required", String.class));
        assertNoJsonSchemaType(root, "$");   // tous les "type" convertis en "bsonType"

        Document properties = schema.get("properties", Document.class);
        assertNotNull(properties);
        assertEquals("string", properties.get("_class", Document.class).getString("bsonType"));

        // Tableau d'objets : on descend bien dans les elements (ActorInActivity)
        Document activities = properties.get("activities", Document.class);
        assertEquals("array", activities.getString("bsonType"));
        Document items = activities.get("items", Document.class);
        assertEquals("object", items.getString("bsonType"));
        assertEquals("object", items.get("properties", Document.class)
                .get("personalIdentity", Document.class).getString("bsonType"));

        // Descriptions reprises des @Schema du DTO grace aux @Mapping de ActorMapper
        assertEquals(dtoDescription("actorId"),
                properties.get("_id", Document.class).getString("description"));
        assertEquals(dtoDescription("actorType"),
                properties.get("type", Document.class).getString("description"));

        // Le texte genere est du JSON valide qui se termine par un retour a la ligne
        String text = SchemaExporter.generateAsString();
        assertTrue(text.endsWith(System.lineSeparator()));
        assertTrue(Document.parse(text).containsKey("$jsonSchema"));

        // ------------------------------------------------------------------
        // 2. Dossier vide : premier schema en 1.0.0, puis plus rien tant que le modele est stable
        // ------------------------------------------------------------------
        assertTrue(SchemaExporter.hasChanged(dir), "Aucun fichier : il faut en generer un");
        assertTrue(SchemaExporter.hasChanged(dir.resolve("inexistant")), "Dossier absent : idem");

        Path first = SchemaExporter.exportIfChanged(dir).orElseThrow();
        assertEquals("latest-1.0.0.json5", first.getFileName().toString());
        assertEquals(text, read(first), "Le fichier contient exactement le schema genere");

        assertFalse(SchemaExporter.hasChanged(dir));
        assertTrue(SchemaExporter.exportIfChanged(dir).isEmpty(), "Modele inchange : aucun nouveau fichier");
        assertEquals(List.of("latest-1.0.0.json5"), fileNames(dir));

        // La mise en forme ne compte pas : meme schema sur une ligne = pas de modification
        write(first, SchemaExporter.generate().toJson(COMPACT));
        assertFalse(SchemaExporter.hasChanged(dir));
        assertTrue(SchemaExporter.exportIfChanged(dir).isEmpty());

        // ------------------------------------------------------------------
        // 3. Modele modifie : nouveau fichier en version automatique (patch + 1)
        //    Le "dernier" fichier doit etre choisi numeriquement : 1.13.0 > 1.9.0 > 1.0.0
        // ------------------------------------------------------------------
        write(dir.resolve("latest-1.9.0.json5"), "{}");
        Path v1130 = dir.resolve("latest-1.13.0.json5");
        write(v1130, "{}");                                  // schema different du modele actuel
        write(dir.resolve("1.0.6.json5"), "{}");             // ancien format de nom : ignore
        write(dir.resolve("banner.txt"), "texte");           // autre fichier : ignore

        assertTrue(SchemaExporter.hasChanged(dir));
        Path auto = SchemaExporter.exportIfChanged(dir).orElseThrow();
        assertEquals("latest-1.13.1.json5", auto.getFileName().toString());
        assertEquals("{}", read(v1130), "Les fichiers existants ne sont jamais modifies");
        assertEquals(text, read(auto));
        assertFalse(SchemaExporter.hasChanged(dir));

        // ------------------------------------------------------------------
        // 4. Version manuelle
        // ------------------------------------------------------------------
        // Modele inchange : la version manuelle ne cree rien (mais une version mal formee est refusee)
        assertTrue(SchemaExporter.exportIfChanged(dir, "9.9.9").isEmpty());
        assertFalse(Files.exists(dir.resolve("latest-9.9.9.json5")));
        assertThrows(IllegalArgumentException.class, () -> SchemaExporter.exportIfChanged(dir, "1.14"));

        // Modele modifie : la version manuelle est utilisee (espaces ignores)
        write(auto, "{}");
        Path manual = SchemaExporter.exportIfChanged(dir, "  1.14.0 ").orElseThrow();
        assertEquals("latest-1.14.0.json5", manual.getFileName().toString());

        // ------------------------------------------------------------------
        // 5. Versions refusees : aucun fichier ne doit etre cree
        // ------------------------------------------------------------------
        write(manual, "{}");                                 // le modele "a encore change"
        List<String> before = fileNames(dir);
        for (String refused : List.of(
                "1.14.0",                 // egale a la derniere : ecraserait un schema
                "1.13.9",                 // inferieure : "latest" ne serait plus le dernier
                "0.99.99",
                "1.14",                   // mal formees
                "v1.14.1",
                "1.14.1-SNAPSHOT",
                "-Dschema.update=true")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaExporter.exportIfChanged(dir, refused),
                    "Version acceptee a tort : " + refused);
        }
        assertEquals(before, fileNames(dir));

        // ------------------------------------------------------------------
        // 6. Version vide = automatique ; fichier illisible = considere comme different
        // ------------------------------------------------------------------
        Path blank = SchemaExporter.exportIfChanged(dir, "   ").orElseThrow();
        assertEquals("latest-1.14.1.json5", blank.getFileName().toString());

        write(blank, "ceci n'est pas du json");
        assertTrue(SchemaExporter.hasChanged(dir));
        Path afterGarbage = SchemaExporter.exportIfChanged(dir).orElseThrow();
        assertEquals("latest-1.14.2.json5", afterGarbage.getFileName().toString());

        // Etat final : le dernier fichier est synchronise avec le modele
        assertFalse(SchemaExporter.hasChanged(dir));
    }

    // =====================================================================
    // Outils
    // =====================================================================

    /** Description @Schema d'un champ d'ActorDto, lue par reflexion (evite de la recopier ici). */
    private static String dtoDescription(String field) throws NoSuchFieldException {
        Schema schema = ActorDto.class.getDeclaredField(field).getAnnotation(Schema.class);
        assertNotNull(schema, "ActorDto." + field + " doit avoir @Schema");
        assertFalse(schema.description().isBlank(), "ActorDto." + field + " doit avoir une description");
        return schema.description();
    }

    /** Echoue si un noeud du schema a encore un "type": "xxx" (texte) au lieu de "bsonType". */
    private static void assertNoJsonSchemaType(Object node, String path) {
        if (node instanceof Document document) {
            for (Map.Entry<String, Object> entry : document.entrySet()) {
                // Une propriete metier nommee "type" est un Document (sous "properties"), pas un texte
                if ("type".equals(entry.getKey()) && entry.getValue() instanceof String) {
                    fail("\"type\" non converti en \"bsonType\" a " + path);
                }
                assertNoJsonSchemaType(entry.getValue(), path + "." + entry.getKey());
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                assertNoJsonSchemaType(list.get(i), path + "[" + i + "]");
            }
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Noms des fichiers du dossier, tries (pour comparer facilement). */
    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
