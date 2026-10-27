import io.swagger.v3.oas.annotations.media.Schema;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.schema.MongoJsonSchema;
import org.springframework.data.mongodb.core.schema.MongoJsonSchemaCreator;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Genere le $jsonSchema MongoDB de la collection "actors" a partir du modele Java (Actor)
 * et reprend les descriptions deja presentes sur les DTO (@Schema(description = "...")).
 *
 * Le lien modele -> DTO est decouvert via les mappers MapStruct (ActorMapper, AddressMapper,
 * ContactMapper, ...) : chaque methode de mapper relie une classe du modele a une classe DTO.
 *
 * NB : cette classe doit vivre dans un module qui voit a la fois le modele (core)
 * et les DTO / mappers (business).
 */
public final class SchemaExporter {

    public static final Path TARGET =
            Path.of("src/main/resources/schema/mongodb/actors/1.0.0.json5");

    /** Racine du code applicatif scannee pour trouver les mappers (*Mapper). */
    private static final String BASE_PACKAGE = "com.rcibs.rdv.api";

    /** Package des classes du modele Mongo (cote "entite" des mappers). */
    private static final String MODEL_PACKAGE = "com.rcibs.rdv.api.core.model";

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

        Map<Class<?>, Set<Class<?>>> registry = discoverMapperPairs();

        // Racine : ActorDto en premier, puis tout DTO que les mappers associent a Actor.
        Set<Class<?>> rootDtos = new LinkedHashSet<>();
        rootDtos.add(ActorDto.class);
        rootDtos.addAll(registry.getOrDefault(Actor.class, Set.of()));

        enrich(jsonSchema, Actor.class, rootDtos, registry);
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

    // ---------------------------------------------------- decouverte mappers

    /**
     * Scanne les interfaces/classes "*Mapper" et deduit, pour chaque methode, les couples
     * (classe du modele -> classes DTO) a partir du type de retour et des parametres.
     *
     * On scanne au lieu de lire @Mapper(uses = ...) car l'annotation MapStruct a une retention
     * CLASS : elle n'est pas lisible par reflexion a l'execution.
     */
    private static Map<Class<?>, Set<Class<?>>> discoverMapperPairs() {
        Map<Class<?>, Set<Class<?>>> result = new LinkedHashMap<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isIndependent(); // autorise les interfaces
                    }
                };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Mapper$")));

        ClassLoader classLoader = SchemaExporter.class.getClassLoader();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> mapper;
            try {
                mapper = ClassUtils.forName(definition.getBeanClassName(), classLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }

            for (Method method : mapper.getMethods()) {
                List<Type> types = new ArrayList<>();
                types.add(method.getGenericReturnType());
                types.addAll(Arrays.asList(method.getGenericParameterTypes()));

                Set<Class<?>> models = new LinkedHashSet<>();
                Set<Class<?>> dtos = new LinkedHashSet<>();
                for (Type type : types) {
                    Class<?> c = unwrap(type);
                    if (c == null) {
                        continue;
                    }
                    if (c.getName().startsWith(MODEL_PACKAGE)) {
                        models.add(c);
                    } else if (c.getName().startsWith(BASE_PACKAGE)) {
                        dtos.add(c);
                    }
                }
                for (Class<?> model : models) {
                    result.computeIfAbsent(model, k -> new LinkedHashSet<>()).addAll(dtos);
                }
            }
        }
        return result;
    }

    /** List<X>, Set<X>, Optional<X>, X[] -> X ; X -> X ; le reste -> null. */
    private static Class<?> unwrap(Type type) {
        if (type instanceof Class<?> c) {
            return c.isArray() ? unwrap(c.getComponentType()) : c;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            boolean wrapper = Iterable.class.isAssignableFrom(raw) || Optional.class.equals(raw);
            if (wrapper && p.getActualTypeArguments().length == 1) {
                return unwrap(p.getActualTypeArguments()[0]);
            }
            return raw;
        }
        return null;
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
     * Parcourt le schema en parallele de la classe du modele et des classes DTO candidates.
     * Candidats d'un noeud = DTO donnes par les mappers pour ce type de modele
     * + type du champ correspondant dans les DTO du noeud parent.
     */
    private static void enrich(Document node,
                               Class<?> modelClass,
                               Set<Class<?>> dtoCandidates,
                               Map<Class<?>, Set<Class<?>>> registry) {
        normalizeType(node);
        Document props = node.get("properties", Document.class);
        if (props == null) {
            return;
        }
        for (String propName : new ArrayList<>(props.keySet())) {
            Document prop = props.get(propName, Document.class);
            if (prop == null) {
                continue;
            }
            // Le creator Spring emet "type": "string|array|object" (JSON Schema) pour les types
            // simples et "bsonType" pour les autres : on uniformise AVANT de lire le type.
            normalizeType(prop);

            Field modelField = findModelField(modelClass, propName);
            if (modelField == null) {
                continue;
            }

            // Champs correspondants dans chacun des DTO candidats
            List<Field> dtoFields = new ArrayList<>();
            for (Class<?> dto : dtoCandidates) {
                Field f = findDtoField(dto, modelField.getName());
                if (f != null) {
                    dtoFields.add(f);
                }
            }

            String description = null;
            for (Field f : dtoFields) {
                description = descriptionOf(f);
                if (description != null) {
                    break;
                }
            }
            if (description == null) {
                description = descriptionOf(modelField); // fallback : @Schema sur le modele
            }
            if (description != null) {
                prop.put("description", description);
            }

            Object bsonType = prop.get("bsonType");
            if ("object".equals(bsonType)) {
                Set<Class<?>> children = new LinkedHashSet<>();
                for (Field f : dtoFields) {
                    addCandidate(children, f.getType());
                }
                children.addAll(registry.getOrDefault(modelField.getType(), Set.of()));
                enrich(prop, modelField.getType(), children, registry);

            } else if ("array".equals(bsonType)) {
                Document items = prop.get("items", Document.class);
                if (items == null) {
                    continue;
                }
                normalizeType(items);
                Class<?> modelElement = elementType(modelField.getGenericType());
                if (modelElement == null) {
                    continue;
                }
                Set<Class<?>> children = new LinkedHashSet<>();
                for (Field f : dtoFields) {
                    addCandidate(children, elementType(f.getGenericType()));
                }
                children.addAll(registry.getOrDefault(modelElement, Set.of()));
                enrich(items, modelElement, children, registry);
            }
        }
    }

    /** Ne garde que les classes applicatives (evite String, List, etc.). */
    private static void addCandidate(Set<Class<?>> candidates, Class<?> type) {
        if (type != null && type.getName().startsWith(BASE_PACKAGE)) {
            candidates.add(type);
        }
    }

    /**
     * Remplace "type": "xxx" par "bsonType": "xxx" (convention du schema existant),
     * en conservant l'ordre des cles. Ne touche qu'a un noeud de schema : une propriete
     * metier nommee "type" vit dans "properties" sous forme de Document, jamais de String.
     */
    private static void normalizeType(Document node) {
        if (node == null || !(node.get("type") instanceof String jsonType)) {
            return;
        }
        String bson = switch (jsonType) {
            case "integer" -> "int";
            case "number" -> "double";
            default -> jsonType;
        };
        Document rest = new Document(node);
        rest.remove("type");
        rest.remove("bsonType");
        node.clear();
        node.put("bsonType", bson);
        node.putAll(rest);
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
