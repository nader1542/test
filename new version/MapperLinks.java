package com.rcibs.rdv.api.business.corp.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Les liens modele <-> DTO connus, necessaires pour retrouver la description d'un champ.
 *
 * @param registry classe du modele -> DTO associes par les mappers (scan des *Mapper)
 * @param explicit classe "racine" du modele (Actor, ActorInActivity) -> chemin -> champs de DTO
 *                 (lu dans les @Mapping de ActorMapper)
 */
record MapperLinks(Map<Class<?>, Set<Class<?>>> registry,
                   Map<Class<?>, Map<String, List<DtoRef>>> explicit) {

    /** Champ de DTO (classe + nom) qui documente un champ du modele. */
    record DtoRef(Class<?> dto, String field) {
    }

    /** Construit les liens : scan des mappers + lecture de ActorMapper.java. */
    static MapperLinks load() {
        return new MapperLinks(MapperScanner.discoverMapperPairs(), ActorMapperSourceParser.parse());
    }

    /** DTO que les mappers associent a cette classe du modele. */
    Set<Class<?>> dtosOf(Class<?> model) {
        return registry.getOrDefault(model, Set.of());
    }

    /** Champs de DTO declares pour ce chemin (ex. "personalIdentity.firstName") dans ce scope. */
    List<DtoRef> explicitLinks(Class<?> scope, String path) {
        return explicit.getOrDefault(scope, Map.of()).getOrDefault(path, List.of());
    }

    /** Cette classe a-t-elle ses propres @Mapping (ex. ActorInActivity) ? Alors elle ouvre un scope. */
    boolean hasOwnMappings(Class<?> model) {
        return explicit.containsKey(model);
    }
}
