package com.rcibs.rdv.api.business.corp.schema;

import com.rcibs.rdv.api.business.corp.schema.MapperLinks.DtoRef;
import org.bson.Document;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.rcibs.rdv.api.business.corp.schema.ModelReflection.descriptionOf;
import static com.rcibs.rdv.api.business.corp.schema.ModelReflection.elementType;
import static com.rcibs.rdv.api.business.corp.schema.ModelReflection.findDtoField;
import static com.rcibs.rdv.api.business.corp.schema.ModelReflection.findModelField;

/**
 * Parcourt le schema en parallele du modele et ajoute "description" a chaque propriete,
 * en la prenant dans les @Schema(description) des DTO (via les liens des mappers).
 * Uniformise aussi "type" -> "bsonType" au passage.
 */
final class DescriptionEnricher {

    private final MapperLinks links;

    DescriptionEnricher(MapperLinks links) {
        this.links = links;
    }

    /**
     * @param node          noeud du schema en cours (objet ou items de tableau)
     * @param modelClass    classe du modele correspondant a ce noeud
     * @param dtoCandidates DTO susceptibles de documenter ce noeud (champs de meme nom)
     * @param scope         classe "racine" courante (Actor ou ActorInActivity) : les chemins des
     *                      @Mapping sont relatifs a elle
     * @param path          chemin du noeud courant depuis scope (ex. "personalIdentity")
     */
    void enrich(Document node, Class<?> modelClass, Set<Class<?>> dtoCandidates,
                Class<?> scope, String path) {
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
            List<Field> dtoFields = resolveDtoFields(scope, key, dtoCandidates, modelField.getName());

            String description = pickDescription(dtoFields, modelField);
            if (description != null) {
                prop.put("description", description);
            }

            // Descente dans les objets et les tableaux d'objets
            Object bsonType = prop.get("bsonType");
            if ("object".equals(bsonType)) {
                descend(prop, modelField.getType(), dtoFields, false, scope, key);
            } else if ("array".equals(bsonType)) {
                Document items = prop.get("items", Document.class);
                if (items == null) {
                    continue;
                }
                normalizeType(items);
                Class<?> modelElement = elementType(modelField.getGenericType());
                if (modelElement != null) {
                    descend(items, modelElement, dtoFields, true, scope, key);
                }
            }
        }
    }

    /** Premier @Schema(description) trouve sur les DTO, sinon sur le champ du modele. */
    private static String pickDescription(List<Field> dtoFields, Field modelField) {
        for (Field f : dtoFields) {
            String description = descriptionOf(f);
            if (description != null) {
                return description;
            }
        }
        return descriptionOf(modelField);
    }

    /** Champs de DTO documentant ce champ : d'abord via @Mapping, puis par nom identique. */
    private List<Field> resolveDtoFields(Class<?> scope, String key,
                                         Set<Class<?>> dtoCandidates, String javaName) {
        List<Field> fields = new ArrayList<>();

        // 1. Lien explicite lu dans ActorMapper
        for (DtoRef ref : links.explicitLinks(scope, key)) {
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
    private void descend(Document node, Class<?> childModel, List<Field> dtoFields,
                         boolean element, Class<?> scope, String key) {
        // DTO du sous-objet : type des champs DTO trouves + DTO connus des mappers
        Set<Class<?>> children = new LinkedHashSet<>();
        for (Field f : dtoFields) {
            addCandidate(children, element ? elementType(f.getGenericType()) : f.getType());
        }
        children.addAll(links.dtosOf(childModel));

        // Une classe qui a ses propres @Mapping (ex. ActorInActivity) ouvre un nouveau scope :
        // ses chemins repartent de zero.
        boolean newScope = links.hasOwnMappings(childModel);
        enrich(node, childModel, children,
                newScope ? childModel : scope,
                newScope ? "" : key);
    }

    /** Ne garde que les classes applicatives (evite String, List, etc.). */
    private static void addCandidate(Set<Class<?>> candidates, Class<?> type) {
        if (ModelReflection.isApplicationClass(type)) {
            candidates.add(type);
        }
    }

    /**
     * Remplace "type": "xxx" par "bsonType": "xxx" (convention du schema existant), en conservant
     * l'ordre des cles. Ne touche qu'a un noeud de schema : une propriete metier nommee "type"
     * vit dans "properties" sous forme de Document, jamais de String.
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
}
