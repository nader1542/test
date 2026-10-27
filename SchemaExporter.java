import io.swagger.v3.oas.annotations.media.Schema;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.schema.MongoJsonSchema;
import org.springframework.data.mongodb.core.schema.MongoJsonSchemaCreator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Genere le $jsonSchema MongoDB de la collection "actors" a partir du modele Java (Actor)
 * et reprend les descriptions deja presentes sur le DTO (@Schema(description = "...")).
 *
 * NB : cette classe doit vivre dans un module qui voit a la fois le modele (core)
 * et les DTO (business).
 */
public final class SchemaExporter {

    public static final Path TARGET =
            Path.of("src/main/resources/schema/mongodb/actors/1.0.0.json5");

    /** Champs obligatoires (ceux du schema actuel). */
    private static final List<String> REQUIRED =
            List.of("_id", "type", "role", "status", "countryDatabase");

    private static final JsonWriterSettings WRITER = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(true)
            .indentCharacters("    ")
            .build();

    private SchemaExporter() {
    }

    // ------------------------------------------------------------------ API

    public static Document generate() {
        MongoMappingContext ctx = new MongoMappingContext();
        ctx.setSimpleTypeHolder(new MongoCustomConversions(List.of()).getSimpleTypeHolder());
        ctx.setInitialEntitySet(Set.of(Actor.class));
        ctx.afterPropertiesSet();

        MongoJsonSchema schema = MongoJsonSchemaCreator.create(ctx).createSchemaFor(Actor.class);
        Document root = schema.toDocument();
        Document jsonSchema = root.get("$jsonSchema", Document.class);

        enrich(jsonSchema, Actor.class, ActorDto.class);
        addRootExtras(jsonSchema);
        return root;
    }

    public static String generateAsString() {
        return generate().toJson(WRITER) + System.lineSeparator();
    }

    public static void export(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, generateAsString(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        Path target = args.length > 0 ? Path.of(args[0]) : TARGET;
        export(target);
        System.out.println("Schema ecrit dans " + target.toAbsolutePath());
    }

    // ------------------------------------------------------- post-traitement

    private static void addRootExtras(Document jsonSchema) {
        jsonSchema.put("required", new ArrayList<>(REQUIRED));

        Document props = jsonSchema.get("properties", Document.class);
        if (props != null && !props.containsKey("_class")) {
            props.put("_class", new Document("bsonType", "string"));
        }
    }

    /**
     * Parcourt le schema en parallele de la classe du modele et de la classe DTO correspondante.
     * dtoClass peut etre null (pas de DTO equivalent) : on tente alors @Schema du modele.
     */
    private static void enrich(Document node, Class<?> modelClass, Class<?> dtoClass) {
        Document props = node.get("properties", Document.class);
        if (props == null) {
            return;
        }
        for (String propName : new ArrayList<>(props.keySet())) {
            Document prop = props.get(propName, Document.class);
            Field modelField = findModelField(modelClass, propName);
            if (prop == null || modelField == null) {
                continue;
            }

            Field dtoField = dtoClass == null ? null : findDtoField(dtoClass, modelField.getName());

            String description = descriptionOf(dtoField);
            if (description == null) {
                description = descriptionOf(modelField); // fallback : @Schema sur le modele
            }
            if (description != null) {
                prop.put("description", description);
            }

            Object bsonType = prop.get("bsonType");
            if ("object".equals(bsonType)) {
                enrich(prop, modelField.getType(), dtoField == null ? null : dtoField.getType());
            } else if ("array".equals(bsonType)) {
                Document items = prop.get("items", Document.class);
                Class<?> modelElement = elementType(modelField.getGenericType());
                Class<?> dtoElement = dtoField == null ? null : elementType(dtoField.getGenericType());
                if (items != null && modelElement != null) {
                    enrich(items, modelElement, dtoElement);
                }
            }
        }
    }

    private static String descriptionOf(Field field) {
        if (field == null) {
            return null;
        }
        Schema schema = field.getAnnotation(Schema.class);
        return schema != null && !schema.description().isBlank() ? schema.description() : null;
    }

    /** Champ du modele : par nom Java ou par nom Mongo (@Field). */
    private static Field findModelField(Class<?> type, String mongoName) {
        return findField(type, f -> f.getName().equals(mongoName) || mongoName(f).equals(mongoName));
    }

    /** Champ du DTO : par nom Java ou par @Schema(name = ...). */
    private static Field findDtoField(Class<?> type, String javaName) {
        return findField(type, f -> {
            if (f.getName().equals(javaName)) {
                return true;
            }
            Schema s = f.getAnnotation(Schema.class);
            return s != null && s.name().equals(javaName);
        });
    }

    private static Field findField(Class<?> type, Predicate<Field> matcher) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && matcher.test(f)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String mongoName(Field f) {
        org.springframework.data.mongodb.core.mapping.Field a =
                f.getAnnotation(org.springframework.data.mongodb.core.mapping.Field.class);
        if (a != null) {
            String v = !a.name().isEmpty() ? a.name() : a.value();
            if (!v.isEmpty()) {
                return v;
            }
        }
        return f.getName();
    }

    private static Class<?> elementType(Type generic) {
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments().length == 1) {
            Type arg = p.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) {
                return c;
            }
            if (arg instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
                return raw;
            }
        }
        return null;
    }
}
