package com.rcibs.rdv.api.business.corp.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.annotation.Id;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Outils de reflexion partages : reconnaitre une classe du modele ou un DTO, deballer List<X>,
 * retrouver un champ dans le modele ou dans un DTO, lire une description @Schema.
 */
final class ModelReflection {

    /** Racine du code applicatif. */
    static final String BASE_PACKAGE = "com.rcibs.rdv.api";

    /** Package des classes du modele Mongo (cote "entite" des mappers). */
    static final String MODEL_PACKAGE = "com.rcibs.rdv.api.core.model";

    private ModelReflection() {
    }

    // ------------------------------------------------------------- classification

    static boolean isModel(Class<?> c) {
        return c != null && c.getName().startsWith(MODEL_PACKAGE);
    }

    /** Un DTO = classe applicative hors modele, dans un package ".dto." ou nommee "...Dto". */
    static boolean isDto(Class<?> c) {
        return c != null
                && c.getName().startsWith(BASE_PACKAGE)
                && !isModel(c)
                && (c.getName().contains(".dto.") || c.getSimpleName().endsWith("Dto"));
    }

    /** Classe applicative (evite String, List, etc.). */
    static boolean isApplicationClass(Class<?> c) {
        return c != null && c.getName().startsWith(BASE_PACKAGE);
    }

    // ------------------------------------------------------------- types generiques

    /** List<X>, Set<X>, Optional<X>, X[] -> X ; X -> X ; le reste -> null. */
    static Class<?> unwrap(Type type) {
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

    /** Type des elements d'une List<X> / Set<X> : X (ou son type brut si generique), sinon null. */
    static Class<?> elementType(Type generic) {
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

    // ------------------------------------------------------------- recherche de champs

    /** Champ du modele : par nom Java ou par nom Mongo (@Field, @Id -> _id). */
    static Field findModelField(Class<?> type, String mongoName) {
        return findField(type, f -> f.getName().equals(mongoName) || mongoName(f).equals(mongoName));
    }

    /** Champ du DTO : par nom Java ou par @Schema(name = ...). */
    static Field findDtoField(Class<?> type, String javaName) {
        return findField(type, f -> {
            if (f.getName().equals(javaName)) {
                return true;
            }
            Schema s = f.getAnnotation(Schema.class);
            return s != null && s.name().equals(javaName);
        });
    }

    /** Valeur de @Schema(description) sur un champ, ou null si absente / vide. */
    static String descriptionOf(Field field) {
        if (field == null) {
            return null;
        }
        Schema schema = field.getAnnotation(Schema.class);
        return schema != null && !schema.description().isBlank() ? schema.description() : null;
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
}
