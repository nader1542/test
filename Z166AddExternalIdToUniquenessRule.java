package com.rcibs.rdv.api.config.dbmigrations;

import com.rcibs.rdv.tools.dynamicproperties.model.DynamicProperty;
import io.mongock.api.annotations.BeforeExecution;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackBeforeExecution;
import io.mongock.api.annotations.RollbackExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.Set;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * MFSRDV-845 : externalId sub-resources - unicity rules.
 *
 * For each sub-resource listed in SUB_RESOURCES, the functional uniqueness rule becomes:
 *   ( same externalId (name, value, application.name) ) || ( existing functional rule )
 * It is applied on every setting of the sub-resource (default + IT / ES / PT).
 * Rules equal to "true" or "false" are left unchanged (the externalId condition would be redundant / meaningless).
 *
 * ASSUMPTION to check: the sub-resource models expose a single "externalId" property (ExternalIdentifier).
 */
@Slf4j
@ChangeUnit(id = "add-external-id-to-uniqueness-rule", order = "0166")
public class Z166AddExternalIdToUniquenessRule {

    private static final String PROPERTY_NAME = "ACTOR_SUBRESOURCE_CREATION_FUNCTIONAL_UNIQUENESS_RULE";
    private static final String SUB_RESOURCE_FILTER = "subResource";

    private static final Set<String> SUB_RESOURCES = Set.of(
            "contacts", "addresses", "bankAccounts", "employments",
            "familySituations", "financialSituations", "identityDocuments", "consents");

    private static final String EXTERNAL_ID_RULE =
            "( #model1.externalId != null && #model2.externalId != null && "
                    + "#model1.externalId.name == #model2.externalId.name && "
                    + "#model1.externalId.value == #model2.externalId.value && "
                    + "#model1.externalId.application != null && #model2.externalId.application != null && "
                    + "#model1.externalId.application.name == #model2.externalId.application.name )";

    private static final String PREFIX = EXTERNAL_ID_RULE + " ||\n(";
    private static final String SUFFIX = ")";

    private final MongoTemplate template;

    public Z166AddExternalIdToUniquenessRule(MongoTemplate template) {
        this.template = template;
    }

    @BeforeExecution
    public void execDDL() {
    }

    @RollbackBeforeExecution
    public void rollbackBeforeExecution() {
    }

    @Execution
    public void changeSet() {
        log.info("[DB_MIGRATION][Z166][PROPERTY] Start");
        update(true);
        log.info("[DB_MIGRATION][Z166][PROPERTY] End");
    }

    @RollbackExecution
    public void rollback() {
        log.info("[DB_MIGRATION][Z166][PROPERTY] Rollback Start");
        update(false);
        log.info("[DB_MIGRATION][Z166][PROPERTY] Rollback End");
    }

    private void update(boolean add) {
        List<DynamicProperty> properties = template.find(
                new Query(where("name").is(PROPERTY_NAME)), DynamicProperty.class);

        if (properties.isEmpty()) {
            log.error("[DB_MIGRATION][Z166][PROPERTY] Unable to find {} dynamic property", PROPERTY_NAME);
            return;
        }

        properties.forEach(property -> {
            property.getSettings().stream()
                    .filter(setting -> setting.getFilters() != null
                            && SUB_RESOURCES.contains(setting.getFilters().get(SUB_RESOURCE_FILTER)))
                    .forEach(setting -> setting.setValue(
                            add ? wrap(setting.getValue()) : unwrap(setting.getValue())));

            log.info("[DB_MIGRATION][Z166][PROPERTY] Update property {} {} : Start", PROPERTY_NAME, property.getId());
            template.save(property);
            log.info("[DB_MIGRATION][Z166][PROPERTY] Update property {} {} : End", PROPERTY_NAME, property.getId());
        });
    }

    private String wrap(Object currentValue) {
        String current = currentValue == null ? "" : currentValue.toString().trim();
        if (current.isEmpty() || "true".equals(current) || "false".equals(current) || current.startsWith(PREFIX)) {
            return currentValue == null ? null : currentValue.toString();
        }
        return PREFIX + current + SUFFIX;
    }

    private String unwrap(Object currentValue) {
        String current = currentValue == null ? "" : currentValue.toString();
        if (current.startsWith(PREFIX) && current.endsWith(SUFFIX)) {
            return current.substring(PREFIX.length(), current.length() - SUFFIX.length());
        }
        return currentValue == null ? null : current;
    }
}
