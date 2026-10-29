package com.rcibs.rdv.api.business.corp.schema;

import com.rcibs.rdv.api.business.corp.schema.MapperLinks.DtoRef;
import com.rcibs.rdv.api.business.corp.service.actors.dto.ActorDto;
import com.rcibs.rdv.api.business.corp.service.actors.mapper.ActorMapper;
import com.rcibs.rdv.api.core.model.actor.Actor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit ActorMapper.java et en deduit : classe du modele -> chemin -> champs de DTO.
 * Exemple : ActorInActivity -> "personalIdentity.firstName" -> [ActorDto.firstName, ...]
 *
 * Les annotations MapStruct ont une retention CLASS (invisibles par reflexion) : on parse donc
 * le fichier source avec des expressions regulieres.
 */
final class ActorMapperSourceParser {

    /** Source de l'ActorMapper (chemin relatif : a lancer depuis la racine du module). */
    static final Path SOURCE = Path.of(
            "src/main/java/com/rcibs/rdv/api/business/corp/service/actors/mapper/ActorMapper.java");

    /** @Mapping(...) : capture le contenu des parentheses. */
    private static final Pattern MAPPING = Pattern.compile("@Mapping\\(([^)]*)\\)");
    /** Declaration d'une methode abstraite : "Type nom(params);". */
    private static final Pattern DECLARATION =
            Pattern.compile("([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^()]*)\\)\\s*;");
    private static final Pattern ATTR_TARGET = Pattern.compile("target\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ATTR_SOURCE = Pattern.compile("source\\s*=\\s*\"([^\"]+)\"");

    /** Un @Mapping lu dans le source : position, cible et source. */
    private record MappingEntry(int start, String target, String source) {
    }

    private ActorMapperSourceParser() {
    }

    static Map<Class<?>, Map<String, List<DtoRef>>> parse() {
        Map<Class<?>, Map<String, List<DtoRef>>> explicit = new LinkedHashMap<>();
        String source = readSource();

        List<MappingEntry> mappings = readMappings(source);          // etape 1
        attachToMethods(source, mappings, explicit);                  // etape 2
        addAfterMappingLinks(explicit);                               // etape 3
        return explicit;
    }

    private static String readSource() {
        String source;
        try {
            source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("ActorMapper.java introuvable : "
                    + SOURCE.toAbsolutePath() + " (lancer depuis la racine du module)", e);
        }
        // Supprime les commentaires (// Enum, // Sub-resources, ...) pour ne pas gener le parsing
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** Etape 1 : tous les @Mapping avec target ET source (ignore = true n'a rien a documenter). */
    private static List<MappingEntry> readMappings(String source) {
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
        return mappings;
    }

    /**
     * Etape 2 : les @Mapping precedent la methode abstraite qu'ils decrivent. Pour chaque
     * declaration "Type nom(params);", on prend les @Mapping situes entre la declaration
     * precedente et celle-ci.
     */
    private static void attachToMethods(String source, List<MappingEntry> mappings,
                                        Map<Class<?>, Map<String, List<DtoRef>>> explicit) {
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
    }

    /**
     * Etape 3 : valeurs posees dans les @AfterMapping (enums) : aucun @Mapping a lire, on declare
     * le lien a la main. A mettre a jour si ces champs sont renommes.
     */
    private static void addAfterMappingLinks(Map<Class<?>, Map<String, List<DtoRef>>> explicit) {
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
    }

    /**
     * Enregistre un @Mapping selon le sens de la methode :
     * DTO -> modele : target = chemin du modele, source = champ du DTO
     * modele -> DTO : target = champ du DTO, source = "<parametre>.<chemin du modele>"
     */
    private static void register(Map<Class<?>, Map<String, List<DtoRef>>> explicit,
                                 Method method, List<String> paramNames, MappingEntry entry) {
        Class<?> returned = ModelReflection.unwrap(method.getGenericReturnType());
        Class<?>[] params = method.getParameterTypes();

        if (ModelReflection.isModel(returned)) {
            for (Class<?> param : params) {
                if (ModelReflection.isDto(param)) {
                    addExplicit(explicit, returned, entry.target(), param, entry.source());
                }
            }
        } else if (ModelReflection.isDto(returned)) {
            // "activity.personalIdentity.firstName" -> parametre "activity" + chemin du modele
            int dot = entry.source().indexOf('.');
            if (dot < 0) {
                return;
            }
            int index = paramNames.indexOf(entry.source().substring(0, dot));
            if (index < 0 || index >= params.length || !ModelReflection.isModel(params[index])) {
                return;
            }
            addExplicit(explicit, params[index], entry.source().substring(dot + 1),
                    returned, entry.target());
        }
    }

    private static void addExplicit(Map<Class<?>, Map<String, List<DtoRef>>> explicit,
                                    Class<?> modelClass, String path, Class<?> dto, String dtoField) {
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
}
