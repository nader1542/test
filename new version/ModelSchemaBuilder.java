package com.rcibs.rdv.api.business.corp.schema;

import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.schema.MongoJsonSchema;
import org.springframework.data.mongodb.core.schema.MongoJsonSchemaCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Produit le squelette du schema (structure, bsonType, properties, items) avec Spring Data MongoDB,
 * puis ajoute ce que le creator Spring ne fournit pas : la liste "required" et le champ "_class".
 */
final class ModelSchemaBuilder {

    /** Champs obligatoires (ceux du schema actuel). */
    private static final List<String> REQUIRED =
            List.of("_id", "type", "role", "status", "countryDatabase");

    private ModelSchemaBuilder() {
    }

    /** Structure du schema de l'entite, sans descriptions : {"$jsonSchema": {...}}. */
    static Document createStructure(Class<?> entity) {
        MongoMappingContext ctx = new MongoMappingContext();
        ctx.setSimpleTypeHolder(new MongoCustomConversions(List.of()).getSimpleTypeHolder());
        ctx.setInitialEntitySet(Set.of(entity));
        ctx.afterPropertiesSet();

        MongoJsonSchema schema = MongoJsonSchemaCreator.create(ctx).createSchemaFor(entity);
        return schema.toDocument();
    }

    /** Ajoute "required" et le champ technique "_class" au noeud $jsonSchema. */
    static void addRootExtras(Document jsonSchema) {
        jsonSchema.put("required", new ArrayList<>(REQUIRED));

        Document props = jsonSchema.get("properties", Document.class);
        if (props != null && !props.containsKey("_class")) {
            props.put("_class", new Document("bsonType", "string"));
        }
    }
}
