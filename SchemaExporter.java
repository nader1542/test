import io.swagger.v3.oas.annotations.media.Schema;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.data.annotation.Id;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Genere le $jsonSchema MongoDB de la collection "actors" a partir du modele Java (Actor)
 * et reprend les descriptions deja presentes sur les DTO (@Schema(description = "...")).
 *
 * Comment retrouver le bon DTO / le bon champ :
 *  1. ActorMapper : on lit ses @Mapping (target/source) pour relier un chemin du modele
 *     (ex. "personalIdentity.firstName" dans ActorInActivity) a un champ de DTO plat
 *     (ex. ActorDto.firstName). Les annotations MapStruct ne sont pas visibles par reflexion
 *     (retention CLASS) : on parse donc le fichier source ActorMapper.java.
 *  2. Les autres mappers (AddressMapper, ContactMapper, ...) : decouverts par scan, ils relient
 *     une classe du modele a son DTO (champs de meme nom).
 *  3. Secours : champ de meme nom dans le DTO, puis @Schema sur le modele.
 *
 * NB : a lancer depuis la racine du module (chemins relatifs src/main/...).
 */
public final class SchemaExporter {

    public static final Path TARGET =
            Path.of("src/main/resources/schema/mongodb/actors/1.0.0.json5");

    /** Source de l'ActorMapper (parse pour lire les @Mapping). */
    private static final Path ACTOR_MAPPER_SOURCE = Path.of(
            "src/main/java/com/rcibs/rdv/api/business/corp/service/actors/mapper/ActorMapper.java");

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

    // Parsing de ActorMapper.java
    private static final Pattern MAPPING = Pattern.compile("@Mapping\\(([^)]*)\\)");
    private static final Pattern DECLARATION =
            Pattern.compile("([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^()]*)\\)\\s*;");
    private static final Pattern ATTR_TARGET = Pattern.compile("target\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_SOURCE = Pattern.compile("source\\s*=\\s*\"([^\"]+)\"");

    /** Champ de DTO (classe + nom) qui documente un champ du modele. */
    private record DtoRef(Class<?> dto, String field) {
    }

    private record MappingEntry(int start, String target, String source) {
    }

    /**
     * registry : classe du modele -> DTO associes (scan des mappers)
     * explicit : classe "racine" du modele (Actor, ActorInActivity) -> chemin -> champs de DTO
     */
    private record Context(Map<Class<?>, Set<Class<?>>> registry,
                           Map<Class<?>, Map<String, List<DtoRef>>> explicit) {
    }

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

        Context context = new Context(discoverMapperPairs(), parseActorMapper());

        Set<Class<?>> rootDtos = new LinkedHashSet<>();
        rootDtos.add(ActorDto.class);
        rootDtos.addAll(context.registry().getOrDefault(Actor.class, Set.of()));

        enrich(jsonSchema, Actor.class, rootDtos, context, Actor.class, "");
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

    // ------------------------------------------- lecture des @Mapping de ActorMapper

    private static Map<Class<?>, Map<String, List<DtoRef>>> parseActorMapper() {
        Map<Class<?>, Map<String, List<DtoRef>>> explicit = new LinkedHashMap<>();

        String source;
        try {
            source = Files.readString(ACTOR_MAPPER_SOURCE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("ActorMapper.java introuvable : "
                    + ACTOR_MAPPER_SOURCE.toAbsolutePath()
                    + " (lancer depuis la racine du module)", e);
        }
        // supprime les commentaires (// Enum, // Sub-resources, ...)
        source = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");

        List<MappingEntry> mappings = new ArrayList<>();
        Matcher mm = MAPPING.matcher(source);
        while (mm.find()) {
            String attributes = mm.group(1);
            String target = firstGroup(ATTR_TARGET, attributes);
            String src = firstGroup(ATTR_SOURCE, attributes);
            if (target != null && src != null) { // ignore = true sans source : sans interet
                mappings.add(new MappingEntry(mm.start(), target, src));
            }
        }

        // Les @Mapping precedent la methode abstraite qu'ils decrivent.
        Matcher dm = DECLARATION.matcher(source);
        int previousEnd = 0;
        while (dm.find()) {
            int from = previousEnd;
            int to = dm.start();
            previousEnd = dm.end();

            Method method = findMapperMethod(dm.group(2));
            if (method == null) {
                continue;
            }
            List<String> paramNames = parameterNames(dm.group(3));
            for (MappingEntry entry : mappings) {
                if (entry.start() >= from && entry.start() < to) {
                    register(explicit, method, paramNames, entry);
                }
            }
        }

        // Valeurs posees dans les @AfterMapping (enums) : pas de @Mapping a lire.
        Method toModel = findMapperMethod("toModel");
        if (toModel != null) {
            Class<?> activity = toModel.getReturnType();
            addExplicit(explicit, activity, "name", ActorDto.class, "activityName");
            addExplicit(explicit, activity, "activityRole", ActorDto.class, "activityRole");
            addExplicit(explicit, activity, "personalIdentity.title", ActorDto.class, "title");
            addExplicit(explicit, activity, "personalIdentity.civility", ActorDto.class, "civility");
            addExplicit(explicit, activity, "personalIdentity.rciUserId", ActorDto.class, "rciUserId");
        }
        addExplicit(explicit, Actor.class, "type", ActorDto.class, "actorType");

        return explicit;
    }

    private static void register(Map<Class<?>, Map<String, List<DtoRef>>> explicit,
                                 Method method,
                                 List<String> paramNames,
                                 MappingEntry entry) {
        Class<?> returned = unwrap(method.getGenericReturnType());
        Class<?>[] params = method.getParameterTypes();

        if (isModel(returned)) {
            // DTO -> modele : target = chemin modele, source = champ du DTO
            for (Class<?> param : params) {
                if (isDto(param)) {
                    addExplicit(explicit, returned, entry.target(), param, entry.source());
                }
            }
        } else if (isDto(returned)) {
            // modele -> DTO : target = champ du DTO, source = "<parametre>.<chemin modele>"
            int dot = entry.source().indexOf('.');
            if (dot < 0) {
                return;
            }
            int index = paramNames.indexOf(entry.source().substring(0, dot));
            if (index < 0 || index >= params.length || !isModel(params[index])) {
                return;
            }
            addExplicit(explicit, params[index], entry.source().substring(dot + 1),
                    returned, entry.target());
        }
    }

    private static void addExplicit(Map<Class<?>, Map<String, List<DtoRef>>> explicit,
                                    Class<?> modelClass, String path,
                                    Class<?> dto, String dtoField) {
        List<DtoRef> refs = explicit
                .computeIfAbsent(modelClass, k -> new LinkedHashMap<>())
                .computeIfAbsent(path, k -> new ArrayList<>());
        DtoRef ref = new DtoRef(dto, dtoField);
        if (!refs.contains(ref)) {
            refs.add(ref);
        }
    }

    private static Method findMapperMethod(String name) {
        for (Method m : ActorMapper.class.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** "Actor actor, @Context ReferenceService referenceService" -> [actor, referenceService] */
    private static List<String> parameterNames(String parameters) {
        List<String> names = new ArrayList<>();
        for (String parameter : parameters.split(",")) {
            String[] tokens = parameter.trim().split("\\s+");
            names.add(tokens[tokens.length - 1]);
        }
        return names;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isModel(Class<?> c) {
        return c != null && c.getName().startsWith(MODEL_PACKAGE);
    }

    private static boolean isDto(Class<?> c) {
        return c != null
                && c.getName().startsWith(BASE_PACKAGE)
                && !isModel(c)
                && (c.getName().contains(".dto.") || c.getSimpleName().endsWith("Dto"));
    }

    // ---------------------------------------------------- decouverte mappers

    /**
     * Scanne les interfaces/classes "*Mapper" et deduit, pour chaque methode, les couples
     * (classe du modele -> classes DTO) a partir du type de retour et des parametres.
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
                    if (isModel(c)) {
                        models.add(c);
                    } else if (isDto(c)) {
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
     * Parcourt le schema en parallele du modele.
     *
     * @param scope classe "racine" courante (Actor ou ActorInActivity) : les chemins des @Mapping
     *              sont relatifs a elle
     * @param path  chemin du noeud courant depuis scope (ex. "personalIdentity")
     */
    private static void enrich(Document node,
                               Class<?> modelClass,
                               Set<Class<?>> dtoCandidates,
                               Context ctx,
                               Class<?> scope,
                               String path) {
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

            String key = path.isEmpty() ? modelField.getName() : path + "." + modelField.getName();
            List<Field> dtoFields = resolveDtoFields(ctx, scope, key, dtoCandidates, modelField.getName());

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
                descend(prop, modelField.getType(), dtoFields, false, ctx, scope, key);
            } else if ("array".equals(bsonType)) {
                Document items = prop.get("items", Document.class);
                if (items == null) {
                    continue;
                }
                normalizeType(items);
                Class<?> modelElement = elementType(modelField.getGenericType());
                if (modelElement != null) {
                    descend(items, modelElement, dtoFields, true, ctx, scope, key);
                }
            }
        }
    }

    /** Champs de DTO documentant ce champ : d'abord via @Mapping, puis par nom identique. */
    private static List<Field> resolveDtoFields(Context ctx, Class<?> scope, String key,
                                                Set<Class<?>> dtoCandidates, String javaName) {
        List<Field> fields = new ArrayList<>();

        List<DtoRef> refs = ctx.explicit()
                .getOrDefault(scope, Map.of())
                .getOrDefault(key, List.of());
        for (DtoRef ref : refs) {
            Field f = findDtoField(ref.dto(), ref.field());
            if (f != null && !fields.contains(f)) {
                fields.add(f);
            }
        }
        for (Class<?> dto : dtoCandidates) {
            Field f = findDtoField(dto, javaName);
            if (f != null && !fields.contains(f)) {
                fields.add(f);
            }
        }
        return fields;
    }

    private static void descend(Document node, Class<?> childModel, List<Field> dtoFields,
                                boolean element, Context ctx, Class<?> scope, String key) {
        Set<Class<?>> children = new LinkedHashSet<>();
        for (Field f : dtoFields) {
            addCandidate(children, element ? elementType(f.getGenericType()) : f.getType());
        }
        children.addAll(ctx.registry().getOrDefault(childModel, Set.of()));

        // Une classe qui a ses propres @Mapping (ex. ActorInActivity) ouvre un nouveau scope.
        boolean newScope = ctx.explicit().containsKey(childModel);
        enrich(node, childModel, children, ctx,
                newScope ? childModel : scope,
                newScope ? "" : key);
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

    /** Champ du modele : par nom Java ou par nom Mongo (@Field, @Id -> _id). */
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
        if (f.isAnnotationPresent(Id.class) || f.getName().equals("id")) {
            return "_id";
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
