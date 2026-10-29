package com.rcibs.rdv.api.business.corp.schema;

import com.rcibs.rdv.api.business.corp.service.actors.dto.ActorDto;
import com.rcibs.rdv.api.core.model.actor.Actor;
import org.bson.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Facade : genere le $jsonSchema MongoDB de la collection "actors" a partir du modele Java (Actor)
 * et des descriptions deja presentes sur les DTO (@Schema(description = "...")).
 *
 * <h3>Repartition des roles</h3>
 * <ul>
 *   <li>{@link ModelSchemaBuilder} : structure du schema (Spring Data) + required + _class</li>
 *   <li>{@link MapperLinks} : liens modele <-> DTO, construits par {@link MapperScanner} (scan des
 *       *Mapper) et {@link ActorMapperSourceParser} (lecture des @Mapping de ActorMapper.java)</li>
 *   <li>{@link DescriptionEnricher} : ajout des descriptions, champ par champ</li>
 *   <li>{@link SchemaFiles} + {@link SchemaVersion} : fichiers latest-x.x.x.json5 et versions</li>
 *   <li>{@link ModelReflection} : outils de reflexion communs</li>
 * </ul>
 * Cette classe ne contient que l'enchainement et les regles : "cree un fichier seulement si le
 * schema a change", "version automatique = patch + 1", "version manuelle > derniere".
 *
 * <h3>Nom du fichier</h3>
 * {@code latest-x.x.x.json5}. Version automatique par defaut (dernier fichier + 1 sur le patch,
 * ou 1.0.0 s'il n'y en a pas) ou manuelle ({@code -Dschema.version=1.14.0} ou premier argument de
 * {@link #main}). Les fichiers existants ne sont jamais modifies ni supprimes.
 *
 * NB : a lancer depuis la racine du module (chemins relatifs src/main/...).
 */
public final class SchemaExporter {

    /** Dossier des schemas Mongo de la collection "actors". */
    public static final Path SCHEMA_DIR =
            Path.of("src/main/resources/schema/mongodb/actors");

    /** Propriete systeme pour choisir la version a la main : -Dschema.version=1.14.0 */
    public static final String VERSION_PROPERTY = "schema.version";

    private SchemaExporter() {
    }

    // ------------------------------------------------------------------ API

    /** Calcule le schema depuis le modele : structure Spring Data + descriptions des DTO. */
    public static Document generate() {
        // 1. Structure (bsonType, properties, items)
        Document root = ModelSchemaBuilder.createStructure(Actor.class);
        Document jsonSchema = root.get("$jsonSchema", Document.class);

        // 2. Liens modele <-> DTO, puis descriptions
        MapperLinks links = MapperLinks.load();
        Set<Class<?>> rootDtos = new LinkedHashSet<>();
        rootDtos.add(ActorDto.class);
        rootDtos.addAll(links.dtosOf(Actor.class));
        new DescriptionEnricher(links).enrich(jsonSchema, Actor.class, rootDtos, Actor.class, "");

        // 3. required + _class
        ModelSchemaBuilder.addRootExtras(jsonSchema);
        return root;
    }

    /** Schema calcule, au format texte (ce qui est ecrit dans le fichier). */
    public static String generateAsString() {
        return SchemaFiles.toText(generate());
    }

    /** Le modele a-t-il change depuis le dernier fichier ? Vrai aussi s'il n'existe aucun fichier. */
    public static boolean hasChanged(Path dir) throws IOException {
        Optional<SchemaFiles.LatestFile> latest = new SchemaFiles(dir).findLatest();
        return latest.isEmpty() || !SchemaFiles.isSameAs(latest.get(), generate());
    }

    /** Version automatique : voir {@link #exportIfChanged(Path, String)}. */
    public static Optional<Path> exportIfChanged(Path dir) throws IOException {
        return exportIfChanged(dir, null);
    }

    /**
     * Cree un NOUVEAU fichier latest-x.x.x.json5 seulement si le modele a change.
     *
     * @param version version choisie a la main (ex. "1.14.0"), ou null / vide pour l'automatique
     * @return le fichier cree, ou vide si le schema est deja a jour
     * @throws IllegalArgumentException si la version manuelle est mal formee ou n'est pas
     *                                  strictement superieure a celle du dernier fichier
     */
    public static Optional<Path> exportIfChanged(Path dir, String version) throws IOException {
        // Valide le format tout de suite, meme si finalement rien ne change
        Optional<SchemaVersion> manual = SchemaVersion.parseOptional(version);

        SchemaFiles files = new SchemaFiles(dir);
        Optional<SchemaFiles.LatestFile> latest = files.findLatest();
        Document generated = generate();

        // Aucun changement par rapport au dernier schema : on ne cree rien
        if (latest.isPresent() && SchemaFiles.isSameAs(latest.get(), generated)) {
            return Optional.empty();
        }

        SchemaVersion target = chooseVersion(manual, latest);
        return Optional.of(files.write(target, generated));
    }

    /** Version manuelle (si donnee, et posterieure au dernier fichier) ou automatique (patch + 1). */
    private static SchemaVersion chooseVersion(Optional<SchemaVersion> manual,
                                               Optional<SchemaFiles.LatestFile> latest) {
        if (manual.isPresent()) {
            SchemaVersion wanted = manual.get();
            if (latest.isPresent() && wanted.compareTo(latest.get().version()) <= 0) {
                throw new IllegalArgumentException("La version " + wanted
                        + " doit etre superieure a la derniere version existante "
                        + latest.get().version());
            }
            return wanted;
        }
        return latest.map(l -> l.version().nextPatch()).orElse(SchemaVersion.INITIAL);
    }

    /**
     * Point d'entree : execution IDE ou mvn exec:java.
     * Version manuelle optionnelle : premier argument, ou -Dschema.version=1.14.0.
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
}
