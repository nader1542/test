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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Genere le $jsonSchema MongoDB de la collection "actors" a partir du modele Java (Actor)
 * et reprend les descriptions deja presentes sur les DTO (@Schema(description = "...")).
 *
 * <h3>Quand un fichier est-il cree ?</h3>
 * Uniquement si le schema calcule depuis le modele (champs + descriptions) est different
 * du dernier fichier {@code latest-x.x.x.json5} present dans le dossier des schemas.
 * Sans modification, rien n'est cree.
 *
 * <h3>Nom du fichier</h3>
 * Chaque modification cree un nouveau fichier {@code latest-x.x.x.json5}. La version est :
 * <ul>
 *   <li>automatique par defaut : celle du dernier fichier + 1 sur le patch
 *       (latest-1.13.0 -> latest-1.13.1), ou 1.0.0 s'il n'y en a pas ;</li>
 *   <li>manuelle si on la donne : {@code -Dschema.version=1.14.0} ou premier argument de
 *       {@link #main} (elle doit etre superieure a la derniere version existante).</li>
 * </ul>
 * Les fichiers existants ne sont jamais modifies ni supprimes.
 *
 * <h3>Comment retrouver le bon DTO / le bon champ pour la description</h3>
 * <ol>
 *   <li>ActorMapper : on lit ses @Mapping (target/source) pour relier un chemin du modele
 *       (ex. "personalIdentity.firstName" dans ActorInActivity) a un champ de DTO plat
 *       (ex. ActorDto.firstName). Les annotations MapStruct ne sont pas visibles par reflexion
 *       (retention CLASS) : on parse donc le fichier source ActorMapper.java.</li>
 *   <li>Les autres mappers (AddressMapper, ContactMapper, ...) : decouverts par scan, ils relient
 *       une classe du modele a son DTO (champs de meme nom).</li>
 *   <li>Secours : champ de meme nom dans le DTO, puis @Schema sur le modele.</li>
 * </ol>
 *
 * NB : a lancer depuis la racine du module (chemins relatifs src/main/...).
 */
public final class SchemaExporter {

    // ------------------------------------------------------------ constantes

    /** Dossier des schemas Mongo de la collection "actors". */
    public static final Path SCHEMA_DIR =
            Path.of("src/main/resources/schema/mongodb/actors");

    /** Nom des fichiers "derniere version" : latest-<major>.<minor>.<patch>.json5. */
    private static final Pattern LATEST_FILE = Pattern.compile("latest-(\\d+)\\.(\\d+)\\.(\\d+)\\.json5");

    /** Format d'une version choisie a la main : x.y.z */
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /** Version du premier fichier, quand aucun latest-* n'existe encore. */
    private static final String INITIAL_VERSION = "1.0.0";

    /** Propriete systeme pour choisir la version a la main : -Dschema.version=1.14.0 */
    public static final String VERSION_PROPERTY = "schema.version";

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

    /** Format d'ecriture : JSON indente sur 4 espaces, nombres en notation "relaxed". */
    private static final JsonWriterSettings WRITER = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(true)
            .indentCharacters("    ")
            .build();

    // Expressions pour parser ActorMapper.java
    /** @Mapping(...) : capture le contenu des parentheses. */
    private static final Pattern MAPPING = Pattern.compile("@Mapping\\(([^)]*)\\)");
    /** Declaration d'une methode abstraite : "Type nom(params);". */
    private static final Pattern DECLARATION =
            Pattern.compile("([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^()]*)\\)\\s*;");
    private static final Pattern ATTR_TARGET = Pattern.compile("target\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_SOURCE = Pattern.compile("source\\s*=\\s*\"([^\"]+)\"");

    // ------------------------------------------------------------ types internes

    /** Champ de DTO (classe + nom) qui documente un champ du modele. */
    private record DtoRef(Class<?> dto, String field) {
    }

    /** Un @Mapping lu dans le source : position, cible et source. */
    private record MappingEntry(int start, String target, String source) {
    }

    /** Fichier latest-x.x.x.json5 existant, avec sa version decomposee pour la comparer. */
    private record VersionedFile(Path path, int major, int minor, int patch) {
        String version() {
            return major + "." + minor + "." + patch;
        }

        /** Version du fichier suivant en mode automatique : on incremente le patch (1.13.0 -> 1.13.1). */
        String nextVersion() {
            return major + "." + minor + "." + (patch + 1);
        }

        /** Version "x.y.z" -> VersionedFile sans chemin, uniquement pour la comparer. */
        static VersionedFile parse(String version) {
            String[] p = version.split("\\.");
            return new VersionedFile(null, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }

        static final Comparator<VersionedFile> BY_VERSION = Comparator
                .comparingInt(VersionedFile::major)
                .thenComparingInt(VersionedFile::minor)
                .thenComparingInt(VersionedFile::patch);
    }

    /**
     * Informations partagees pendant le parcours du schema.
     * registry : classe du modele -> DTO associes (scan des mappers)
     * explicit : classe "racine" du modele (Actor, ActorInActivity) -> chemin -> champs de DTO
     *            (lu dans les @Mapping de ActorMapper)
     */
    private record Context(Map<Class<?>, Set<Class<?>>> registry,
                           Map<Class<?>, Map<String, List<DtoRef>>> explicit) {
    }

    private SchemaExporter() {
    }

    // ------------------------------------------------------------------ API

    /**
     * Calcule le schema depuis le modele : structure Spring Data + descriptions des DTO.
     * Retourne le document {"$jsonSchema": {...}}.
     */
    public static Document generate() {
        // 1. Structure (bsonType, properties, items) fournie par Spring Data MongoDB
        MongoMappingContext ctx = new MongoMappingContext();
        ctx.setSimpleTypeHolder(new MongoCustomConversions(List.of()).getSimpleTypeHolder());
        ctx.setInitialEntitySet(Set.of(Actor.class));
        ctx.afterPropertiesSet();

        MongoJsonSchema schema = MongoJsonSchemaCreator.create(ctx).createSchemaFor(Actor.class);
        Document root = schema.toDocument();
        Document jsonSchema = root.get("$jsonSchema", Document.class);

        // 2. Informations pour relier modele et DTO (mappers)
        Context context = new Context(discoverMapperPairs(), parseActorMapper());

        // DTO candidats a la racine : ActorDto + ceux que les mappers associent a Actor
        Set<Class<?>> rootDtos = new LinkedHashSet<>();
        rootDtos.add(ActorDto.class);
        rootDtos.addAll(context.registry().getOrDefault(Actor.class, Set.of()));

        // 3. Ajout des descriptions (et uniformisation type -> bsonType)
        enrich(jsonSchema, Actor.class, rootDtos, context, Actor.class, "");
        addRootExtras(jsonSchema);
        return root;
    }

    /** Schema calcule, au format texte (ce qui est ecrit dans le fichier). */
    public static String generateAsString() {
        return generate().toJson(WRITER) + System.lineSeparator();
    }

    /**
     * Le modele a-t-il change depuis le dernier fichier latest-x.x.x.json5 ?
     * Vrai aussi s'il n'existe encore aucun fichier.
     */
    public static boolean hasChanged(Path dir) throws IOException {
        Optional<VersionedFile> latest = findLatest(dir);
        if (latest.isEmpty()) {
            return true;
        }
        return !sameSchema(latest.get().path(), generate());
    }

    /** Version automatique : voir {@link #exportIfChanged(Path, String)}. */
    public static Optional<Path> exportIfChanged(Path dir) throws IOException {
        return exportIfChanged(dir, null);
    }

    /**
     * Cree un NOUVEAU fichier latest-x.x.x.json5 seulement si le modele a change.
     * Les fichiers existants ne sont jamais modifies ni supprimes : ils gardent l'historique.
     *
     * @param version version choisie a la main (ex. "1.14.0"), ou null / vide pour la laisser
     *                automatique : version du dernier fichier + 1 sur le patch (1.13.0 -> 1.13.1),
     *                ou 1.0.0 s'il n'y a encore aucun fichier
     * @return le fichier cree, ou vide si le schema est deja a jour
     * @throws IllegalArgumentException si la version manuelle est mal formee ou n'est pas
     *                                  strictement superieure a celle du dernier fichier
     */
    public static Optional<Path> exportIfChanged(Path dir, String version) throws IOException {
        // Une version vide (propriete Maven non renseignee) equivaut a "pas de version manuelle"
        if (version != null && version.isBlank()) {
            version = null;
        }
        if (version != null && !VERSION.matcher(version.trim()).matches()) {
            throw new IllegalArgumentException("Version invalide '" + version + "' (attendu : x.y.z)");
        }

        Optional<VersionedFile> latest = findLatest(dir);
        Document generated = generate();

        // Aucun changement par rapport au dernier schema : on ne cree rien
        if (latest.isPresent() && sameSchema(latest.get().path(), generated)) {
            return Optional.empty();
        }

        String target;
        if (version != null) {
            // Version manuelle : doit etre posterieure au dernier fichier (jamais d'ecrasement)
            target = version.trim();
            if (latest.isPresent() && VersionedFile.BY_VERSION.compare(
                    VersionedFile.parse(target), latest.get()) <= 0) {
                throw new IllegalArgumentException("La version " + target
                        + " doit etre superieure a la derniere version existante "
                        + latest.get().version());
            }
        } else {
            // Version automatique : 1.13.0 -> 1.13.1 (ou 1.0.0 pour le premier fichier)
            target = latest.map(VersionedFile::nextVersion).orElse(INITIAL_VERSION);
        }
        Path file = dir.resolve("latest-" + target + ".json5");

        Files.createDirectories(dir);
        Files.writeString(file, generated.toJson(WRITER) + System.lineSeparator(), StandardCharsets.UTF_8);
        return Optional.of(file);
    }

    /**
     * Point d'entree : mvn exec:java ou execution IDE.
     * Version manuelle optionnelle : premier argument, ou -Dschema.version=1.14.0.
     * Sans version, le nom est automatique.
     */
    public static void main(String[] args) throws IOException {
        String version = args.length > 0 ? args[0] : System.getProperty(VERSION_PROPERTY);

        Optional<Path> written = exportIfChanged(SCHEMA_DIR, version);
        if (written.isPresent()) {
            System.out.println("Schema ecrit dans " + written.get().toAbsolutePath());
        } else {
            System.out.println("Schema deja a jour, aucun fichier genere.");
        }
    }

    // ------------------------------------------- fichiers latest-x.x.x.json5

    /** Retourne le fichier latest-* ayant la plus grande version (ex. 1.13.0 > 1.0.7). */
    private static Optional<VersionedFile> findLatest(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(SchemaExporter::toVersionedFile)
                    .flatMap(Optional::stream)
                    .max(VersionedFile.BY_VERSION);
        }
    }

    private static Optional<VersionedFile> toVersionedFile(Path path) {
        Matcher m = LATEST_FILE.matcher(path.getFileName().toString());
        if (!m.matches()) {
            return Optional.empty(); // 1.0.0.json5, banner.txt, ... : ignores
        }
        return Optional.of(new VersionedFile(path,
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))));
    }

    /**
     * Compare le fichier existant au schema genere, de maniere semantique : on relit les deux
     * en Document, donc l'indentation, l'ordre des cles et les guillemets n'ont pas d'importance.
     * Si le fichier existant est illisible, on considere qu'il differe (il sera regenere).
     */
    private static boolean sameSchema(Path existingFile, Document generated) {
        try {
            Document existing = Document.parse(Files.readString(existingFile, StandardCharsets.UTF_8));
            // Le schema genere repasse par le texte pour avoir les memes types que le fichier relu
            Document normalized = Document.parse(generated.toJson(WRITER));
            return existing.equals(normalized);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------- lecture des @Mapping de ActorMapper

    /**
     * Lit ActorMapper.java et construit : classe du modele -> chemin -> champs de DTO.
     * Exemple : ActorInActivity -> "personalIdentity.firstName" -> [ActorDto.firstName, ...]
     */
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
        // Supprime les commentaires (// Enum, // Sub-resources, ...) pour ne pas gener le parsing
        source = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");

        // Etape 1 : tous les @Mapping avec target ET source (ignore = true n'a rien a documenter)
        List<MappingEntry> mappings = new ArrayList<>();
        Matcher mm = MAPPING.matcher(source);
        while (mm.find()) {
            String attributes = mm.group(1);
            String target = firstGroup(ATTR_TARGET, attributes);
            String src = firstGroup(ATTR_SOURCE, attributes);
            if (target != null && src != null) {
                mappings.add(new MappingEntry(mm.start(), target, src));
            }
        }

        // Etape 2 : les @Mapping precedent la methode abstraite qu'ils decrivent.
        // Pour chaque declaration "Type nom(params);", on prend les @Mapping situes entre la
        // declaration precedente et celle-ci.
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

        // Etape 3 : valeurs posees dans les @AfterMapping (enums) : aucun @Mapping a lire,
        // on declare le lien a la main. A mettre a jour si ces champs sont renommes.
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

    /**
     * Enregistre un @Mapping selon le sens de la methode :
     * DTO -> modele : target = chemin du modele, source = champ du DTO
     * modele -> DTO : target = champ du DTO, source = "<parametre>.<chemin du modele>"
     */
    private static void register(Map<Class<?>, Map<String, List<DtoRef>>> explicit,
                                 Method method,
                                 List<String> paramNames,
                                 MappingEntry entry) {
        Class<?> returned = unwrap(method.getGenericReturnType());
        Class<?>[] params = method.getParameterTypes();

        if (isModel(returned)) {
            for (Class<?> param : params) {
                if (isDto(param)) {
                    addExplicit(explicit, returned, entry.target(), param, entry.source());
                }
            }
        } else if (isDto(returned)) {
            // "activity.personalIdentity.firstName" -> parametre "activity" + chemin du modele
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

    /** Retrouve par reflexion la methode de ActorMapper portant ce nom. */
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

    /** Un DTO = classe applicative hors modele, dans un package ".dto." ou nommee "...Dto". */
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

        // Scanner classpath qui accepte aussi les interfaces (les mappers MapStruct en sont)
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isIndependent();
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
                // Type de retour + types des parametres de la methode
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
                // Chaque classe du modele est reliee aux DTO vus dans la meme methode
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

    /** Ajoute ce que le creator Spring ne produit pas : "required" et le champ "_class". */
    private static void addRootExtras(Document jsonSchema) {
        jsonSchema.put("required", new ArrayList<>(REQUIRED));

        Document props = jsonSchema.get("properties", Document.class);
        if (props != null && !props.containsKey("_class")) {
            props.put("_class", new Document("bsonType", "string"));
        }
    }

    /**
     * Parcourt le schema en parallele du modele et ajoute "description" a chaque propriete.
     *
     * @param node          noeud du schema en cours (objet ou items de tableau)
     * @param modelClass    classe du modele correspondant a ce noeud
     * @param dtoCandidates DTO susceptibles de documenter ce noeud (champs de meme nom)
     * @param scope         classe "racine" courante (Actor ou ActorInActivity) : les chemins des
     *                      @Mapping sont relatifs a elle
     * @param path          chemin du noeud courant depuis scope (ex. "personalIdentity")
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
                continue; // propriete sans champ Java (ex. _class) : rien a documenter
            }

            // Chemin complet du champ depuis la classe racine, ex. "personalIdentity.firstName"
            String key = path.isEmpty() ? modelField.getName() : path + "." + modelField.getName();
            List<Field> dtoFields = resolveDtoFields(ctx, scope, key, dtoCandidates, modelField.getName());

            // Description : premier @Schema(description) trouve sur les DTO, sinon sur le modele
            String description = null;
            for (Field f : dtoFields) {
                description = descriptionOf(f);
                if (description != null) {
                    break;
                }
            }
            if (description == null) {
                description = descriptionOf(modelField);
            }
            if (description != null) {
                prop.put("description", description);
            }

            // Descente dans les objets et les tableaux d'objets
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

        // 1. Lien explicite lu dans ActorMapper
        List<DtoRef> refs = ctx.explicit()
                .getOrDefault(scope, Map.of())
                .getOrDefault(key, List.of());
        for (DtoRef ref : refs) {
            Field f = findDtoField(ref.dto(), ref.field());
            if (f != null && !fields.contains(f)) {
                fields.add(f);
            }
        }
        // 2. Champ de meme nom dans les DTO candidats
        for (Class<?> dto : dtoCandidates) {
            Field f = findDtoField(dto, javaName);
            if (f != null && !fields.contains(f)) {
                fields.add(f);
            }
        }
        return fields;
    }

    /** Descend dans un sous-objet (ou element de liste) en preparant ses DTO candidats. */
    private static void descend(Document node, Class<?> childModel, List<Field> dtoFields,
                                boolean element, Context ctx, Class<?> scope, String key) {
        // DTO du sous-objet : type des champs DTO trouves + DTO connus des mappers
        Set<Class<?>> children = new LinkedHashSet<>();
        for (Field f : dtoFields) {
            addCandidate(children, element ? elementType(f.getGenericType()) : f.getType());
        }
        children.addAll(ctx.registry().getOrDefault(childModel, Set.of()));

        // Une classe qui a ses propres @Mapping (ex. ActorInActivity) ouvre un nouveau scope :
        // ses chemins repartent de zero.
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

    /** Valeur de @Schema(description) sur un champ, ou null si absente / vide. */
    private static String descriptionOf(Field field) {
        if (field == null) {
            return null;
        }
        Schema schema = field.getAnnotation(Schema.class);
        return schema != null && !schema.description().isBlank() ? schema.description() : null;
    }

    // ------------------------------------------------------- recherche de champs

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

    /** Premier champ non statique repondant au critere, en remontant les classes parentes. */
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

    /** Nom du champ dans Mongo : @Field(name/value), "_id" pour l'identifiant, sinon nom Java. */
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

    /** Type des elements d'une List<X> / Set<X> : X (ou son type brut si generique). */
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
