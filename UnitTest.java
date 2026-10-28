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
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests unitaires de SchemaExporter.
 *
 * Aucun test n'ecrit dans le vrai dossier src/main/resources : les fichiers sont crees dans un
 * dossier temporaire (@TempDir). Le schema, lui, est calcule depuis le vrai modele (Actor,
 * ActorDto, mappers) : le test doit donc etre lance depuis la racine du module rdv-api
 * (ce que font Maven et IntelliJ par defaut), car SchemaExporter lit ActorMapper.java par un
 * chemin relatif.
 *
 * Trois groupes :
 *   1. contenu du schema genere (structure, required, descriptions issues du DTO) ;
 *   2. creation du fichier : seulement si le modele a change, nom automatique ou manuel ;
 *   3. cas limites (fichiers illisibles, formats differents, version invalide).
 */
class SchemaExporterUnitTest {

    /** Meme format que le fichier ecrit, mais compact : sert a verifier la comparaison semantique. */
    private static final JsonWriterSettings COMPACT = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(false)
            .build();

    // =====================================================================
    // 1. Contenu du schema genere
    // =====================================================================

    @Test
    void generated_schema_has_root_structure_and_required_fields() {
        Document root = SchemaExporter.generate();

        Document schema = root.get("$jsonSchema", Document.class);
        assertNotNull(schema, "Le document racine doit contenir $jsonSchema");
        assertEquals("object", schema.getString("bsonType"));

        // Champs obligatoires imposes par SchemaExporter
        assertEquals(List.of("_id", "type", "role", "status", "countryDatabase"),
                schema.getList("required", String.class));

        // Le champ technique _class est ajoute a la main
        Document properties = schema.get("properties", Document.class);
        assertNotNull(properties);
        assertEquals("string", properties.get("_class", Document.class).getString("bsonType"));
    }

    @Test
    void generated_schema_uses_bson_type_everywhere() {
        // Spring Data emet "type": "string|array|object" ; SchemaExporter doit tout convertir en bsonType
        Document root = SchemaExporter.generate();
        assertNoJsonSchemaType(root, "$");
    }

    @Test
    void generated_schema_describes_activities_as_array_of_objects() {
        Document properties = rootProperties();

        Document activities = properties.get("activities", Document.class);
        assertNotNull(activities, "Le champ activities doit etre dans le schema");
        assertEquals("array", activities.getString("bsonType"));

        Document items = activities.get("items", Document.class);
        assertNotNull(items);
        assertEquals("object", items.getString("bsonType"));

        // On descend bien dans les elements de la liste (ActorInActivity)
        Document itemProperties = items.get("properties", Document.class);
        assertNotNull(itemProperties);
        assertEquals("object", itemProperties.get("personalIdentity", Document.class).getString("bsonType"));
    }

    @Test
    void descriptions_are_taken_from_dto_schema_annotations_through_mapper() throws Exception {
        Document properties = rootProperties();

        // ActorMapper : @Mapping(target = "id", source = "actorId") -> Actor.id (_id dans Mongo)
        assertEquals(dtoDescription("actorId"),
                properties.get("_id", Document.class).getString("description"));

        // ActorMapper : @Mapping(target = "type", source = "actorType")
        assertEquals(dtoDescription("actorType"),
                properties.get("type", Document.class).getString("description"));
    }

    @Test
    void generation_is_deterministic() {
        // Indispensable : la comparaison "le modele a-t-il change ?" repose sur ce point
        assertEquals(SchemaExporter.generate(), SchemaExporter.generate());
    }

    @Test
    void generate_as_string_is_valid_json_ending_with_a_line_break() {
        String text = SchemaExporter.generateAsString();

        assertTrue(text.endsWith(System.lineSeparator()));
        Document parsed = Document.parse(text);
        assertTrue(parsed.containsKey("$jsonSchema"));
    }

    // =====================================================================
    // 2. Creation du fichier
    // =====================================================================

    @Test
    void first_export_in_empty_directory_creates_version_1_0_0(@TempDir Path dir) throws IOException {
        Optional<Path> created = SchemaExporter.exportIfChanged(dir);

        assertTrue(created.isPresent());
        assertEquals("latest-1.0.0.json5", created.get().getFileName().toString());
        // Le contenu du fichier est exactement le schema genere
        assertEquals(SchemaExporter.generateAsString(), Files.readString(created.get(), StandardCharsets.UTF_8));
    }

    @Test
    void second_export_without_model_change_creates_nothing(@TempDir Path dir) throws IOException {
        SchemaExporter.exportIfChanged(dir);

        Optional<Path> second = SchemaExporter.exportIfChanged(dir);

        assertTrue(second.isEmpty(), "Aucun nouveau fichier si le modele n'a pas change");
        assertEquals(List.of("latest-1.0.0.json5"), fileNames(dir));
    }

    @Test
    void model_change_creates_next_patch_version_and_keeps_old_file(@TempDir Path dir) throws IOException {
        // Un ancien schema different du modele actuel = "le modele a change depuis"
        Path old = dir.resolve("latest-1.13.0.json5");
        write(old, "{}");

        Optional<Path> created = SchemaExporter.exportIfChanged(dir);

        assertEquals("latest-1.13.1.json5", created.orElseThrow().getFileName().toString());
        assertEquals("{}", read(old), "Le fichier existant ne doit jamais etre modifie");
        assertEquals(List.of("latest-1.13.0.json5", "latest-1.13.1.json5"), fileNames(dir));
    }

    @Test
    void next_version_is_based_on_highest_numeric_version(@TempDir Path dir) throws IOException {
        // 1.9.0 > 1.0.7 mais < 1.13.0 : un tri alphabetique donnerait 1.9.0
        write(dir.resolve("latest-1.0.7.json5"), "{}");
        write(dir.resolve("latest-1.9.0.json5"), "{}");
        write(dir.resolve("latest-1.13.0.json5"), "{}");
        // Fichiers ignores : anciens schemas et fichiers qui ne suivent pas le modele de nom
        write(dir.resolve("1.0.6.json5"), "{}");
        write(dir.resolve("banner.txt"), "texte");

        Optional<Path> created = SchemaExporter.exportIfChanged(dir);

        assertEquals("latest-1.13.1.json5", created.orElseThrow().getFileName().toString());
    }

    @Test
    void manual_version_is_used_when_model_changed(@TempDir Path dir) throws IOException {
        write(dir.resolve("latest-1.13.0.json5"), "{}");

        Optional<Path> created = SchemaExporter.exportIfChanged(dir, "1.14.0");

        assertEquals("latest-1.14.0.json5", created.orElseThrow().getFileName().toString());
    }

    @Test
    void manual_version_is_trimmed(@TempDir Path dir) throws IOException {
        write(dir.resolve("latest-1.13.0.json5"), "{}");

        Optional<Path> created = SchemaExporter.exportIfChanged(dir, "  1.14.0 ");

        assertEquals("latest-1.14.0.json5", created.orElseThrow().getFileName().toString());
    }

    @Test
    void blank_or_null_version_means_automatic(@TempDir Path dir) throws IOException {
        write(dir.resolve("latest-2.0.0.json5"), "{}");

        Optional<Path> created = SchemaExporter.exportIfChanged(dir, "   ");

        assertEquals("latest-2.0.1.json5", created.orElseThrow().getFileName().toString());
    }

    @Test
    void manual_version_is_ignored_when_model_did_not_change(@TempDir Path dir) throws IOException {
        SchemaExporter.exportIfChanged(dir);

        Optional<Path> created = SchemaExporter.exportIfChanged(dir, "9.9.9");

        assertTrue(created.isEmpty());
        assertEquals(List.of("latest-1.0.0.json5"), fileNames(dir));
    }

    @Test
    void has_changed_is_true_without_any_file_then_false_once_exported(@TempDir Path dir) throws IOException {
        assertTrue(SchemaExporter.hasChanged(dir), "Aucun fichier : il faut en generer un");

        SchemaExporter.exportIfChanged(dir);

        assertFalse(SchemaExporter.hasChanged(dir));
    }

    @Test
    void has_changed_is_true_when_latest_file_differs_from_model(@TempDir Path dir) throws IOException {
        SchemaExporter.exportIfChanged(dir);
        // Quelqu'un modifie le schema commite : il n'est plus synchronise avec le modele
        write(dir.resolve("latest-1.0.0.json5"), "{ \"$jsonSchema\": { \"bsonType\": \"object\" } }");

        assertTrue(SchemaExporter.hasChanged(dir));
    }

    @Test
    void has_changed_is_true_when_directory_does_not_exist(@TempDir Path dir) throws IOException {
        assertTrue(SchemaExporter.hasChanged(dir.resolve("inexistant")));
    }

    // =====================================================================
    // 3. Cas limites
    // =====================================================================

    @Test
    void comparison_ignores_formatting_and_quotes(@TempDir Path dir) throws IOException {
        // Meme schema, mais ecrit en une seule ligne : ce n'est pas une modification du modele
        String compact = SchemaExporter.generate().toJson(COMPACT);
        write(dir.resolve("latest-3.0.0.json5"), compact);

        assertTrue(SchemaExporter.exportIfChanged(dir).isEmpty());
        assertFalse(SchemaExporter.hasChanged(dir));
    }

    @Test
    void unreadable_latest_file_is_considered_different(@TempDir Path dir) throws IOException {
        write(dir.resolve("latest-2.0.0.json5"), "ceci n'est pas du json");

        Optional<Path> created = SchemaExporter.exportIfChanged(dir);

        assertEquals("latest-2.0.1.json5", created.orElseThrow().getFileName().toString());
    }

    @Test
    void invalid_manual_version_is_rejected(@TempDir Path dir) throws IOException {
        write(dir.resolve("latest-1.13.0.json5"), "{}");

        for (String invalid : List.of("1.14", "v1.14.0", "1.14.0-SNAPSHOT", "a.b.c", "-Dschema.update=true")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaExporter.exportIfChanged(dir, invalid),
                    "Version invalide acceptee : " + invalid);
        }
        assertEquals(List.of("latest-1.13.0.json5"), fileNames(dir), "Aucun fichier ne doit etre cree");
    }

    @Test
    void invalid_manual_version_is_rejected_even_without_model_change(@TempDir Path dir) throws IOException {
        SchemaExporter.exportIfChanged(dir);

        assertThrows(IllegalArgumentException.class, () -> SchemaExporter.exportIfChanged(dir, "1.14"));
    }

    @Test
    void manual_version_must_be_greater_than_latest(@TempDir Path dir) throws IOException {
        Path latest = dir.resolve("latest-1.13.0.json5");
        write(latest, "{}");

        // Egale : refuserait d'ecraser ; inferieure : latest ne serait plus le dernier
        assertThrows(IllegalArgumentException.class, () -> SchemaExporter.exportIfChanged(dir, "1.13.0"));
        assertThrows(IllegalArgumentException.class, () -> SchemaExporter.exportIfChanged(dir, "1.12.9"));
        assertThrows(IllegalArgumentException.class, () -> SchemaExporter.exportIfChanged(dir, "0.99.99"));

        assertEquals("{}", read(latest));
        assertEquals(List.of("latest-1.13.0.json5"), fileNames(dir));
    }

    @Test
    void manual_version_comparison_is_numeric_not_alphabetical(@TempDir Path dir) throws IOException {
        write(dir.resolve("latest-1.9.0.json5"), "{}");

        // 1.10.0 > 1.9.0 (alors que "1.10.0" < "1.9.0" alphabetiquement)
        Optional<Path> created = SchemaExporter.exportIfChanged(dir, "1.10.0");

        assertEquals("latest-1.10.0.json5", created.orElseThrow().getFileName().toString());
    }

    // =====================================================================
    // Outils
    // =====================================================================

    /** Proprietes de premier niveau du schema genere. */
    private static Document rootProperties() {
        return SchemaExporter.generate()
                .get("$jsonSchema", Document.class)
                .get("properties", Document.class);
    }

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
