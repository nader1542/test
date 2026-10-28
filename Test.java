package com.rcibs.rdv.api.business.corp.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Declenche SchemaExporter au demarrage de l'application Spring, sans classe main ni plugin Maven.
 *
 * Inactif par defaut : le bean n'existe que si {@code schema.export.enabled=true}.
 * A utiliser en local uniquement (il ecrit dans src/main/resources) ; ne jamais l'activer en prod.
 *
 * Exemples (Program arguments de la configuration IntelliJ "RDV") :
 * <pre>
 *   --schema.export.enabled=true                       nom du fichier automatique
 *   --schema.export.enabled=true --schema.export.version=1.14.0   version manuelle
 *   --schema.export.enabled=true --schema.export.exit=false       l'appli continue de tourner
 * </pre>
 * Comme SchemaExporter.main : un nouveau fichier latest-x.x.x.json5 n'est cree que si le modele a
 * change depuis le dernier fichier.
 */
@Component
@ConditionalOnProperty(name = "schema.export.enabled", havingValue = "true")
public class SchemaExportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaExportRunner.class);

    private final ConfigurableApplicationContext context;

    /** Version manuelle (vide = automatique). */
    @Value("${schema.export.version:}")
    private String version;

    /** Arreter l'application une fois le schema traite (par defaut oui : on ne veut que generer). */
    @Value("${schema.export.exit:true}")
    private boolean exitAfter;

    public SchemaExportRunner(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Optional<Path> written = SchemaExporter.exportIfChanged(SchemaExporter.SCHEMA_DIR, version);
        if (written.isPresent()) {
            log.info("[schema] Schema ecrit dans {}", written.get().toAbsolutePath());
        } else {
            log.info("[schema] Schema deja a jour, aucun fichier genere.");
        }

        if (exitAfter) {
            // Arret propre du contexte Spring, puis fin du processus
            System.exit(SpringApplication.exit(context));
        }
    }
}
