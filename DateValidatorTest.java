package com.rcibs.rdv.api.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rcibs.rdv.api.repository.util.DateUtil;
import jakarta.validation.ConstraintViolation;  // javax.validation si Spring Boot 2
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DateValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    /** Bean minimal pour tester l'annotation seule. */
    static class Holder {
        @DateValidation
        String creationDate;

        Holder(String creationDate) {
            this.creationDate = creationDate;
        }
    }

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    // ---------- DateUtil.isValidDate avec DATE_TIME_FORMATS ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "2025-01-03",
            "2024-02-29",                       // année bissextile
            "2025-01-03T08:15:42",
            "2025-01-03T08:15:42Z",
            "2025-01-03T08:15:42+01:00",
            "2025-01-03T08:15:42.123456Z",
            "Fri Jan 03 08:15:42 UTC 2025"      // format Date.toString() (MFSRDV-861)
    })
    void dateTime_accepteLesFormatsValides(String value) {
        assertTrue(DateUtil.isValidDate(value, DateUtil.DATE_TIME_FORMATS, null),
                "Devrait être accepté : '" + value + "'");
    }

    @ParameterizedTest
    @NullSource
    void dateTime_accepteNull(String value) {
        assertTrue(DateUtil.isValidDate(value, DateUtil.DATE_TIME_FORMATS, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "test",
            "",
            "   ",
            "2025-13-01",                       // mois invalide
            "2025-02-30",                       // jour invalide
            "2025-01-03test",                   // suffixe parasite
            "30/01/2025",
            "Mon Jan 03 08:15:42 UTC 2025",     // le 3 janvier 2025 est un vendredi
            "Fri Jan 03 25:15:42 UTC 2025",     // heure invalide
            "Fri Foo 03 08:15:42 UTC 2025"      // mois inconnu
    })
    void dateTime_refuseLesValeursInvalides(String value) {
        assertFalse(DateUtil.isValidDate(value, DateUtil.DATE_TIME_FORMATS, null),
                "Devrait être refusé : '" + value + "'");
    }

    // ---------- DateUtil.isValidDate avec DATE_FORMATS (date seule) ----------

    @Test
    void dateSeule_accepteUneDate() {
        assertTrue(DateUtil.isValidDate("2025-01-03", DateUtil.DATE_FORMATS, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-01-03T08:15:42", "Fri Jan 03 08:15:42 UTC 2025", "test"})
    void dateSeule_refuseLesDateTime(String value) {
        assertFalse(DateUtil.isValidDate(value, DateUtil.DATE_FORMATS, null));
    }

    // ---------- Contrainte @DateValidation via Bean Validation ----------

    @Test
    void annotation_aucuneViolation_siNull() {
        assertTrue(validator.validate(new Holder(null)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-01-03", "2025-01-03T08:15:42Z", "Fri Jan 03 08:15:42 UTC 2025"})
    void annotation_aucuneViolation_siFormatValide(String value) {
        assertTrue(validator.validate(new Holder(value)).isEmpty(),
                "Aucune violation attendue pour : '" + value + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "2025-02-30", "2025-01-03test", "Mon Jan 03 08:15:42 UTC 2025"})
    void annotation_violation_siFormatInvalide(String value) {
        Set<ConstraintViolation<Holder>> violations = validator.validate(new Holder(value));
        assertEquals(1, violations.size(), "Une violation attendue pour : '" + value + "'");
        assertEquals("creationDate", violations.iterator().next().getPropertyPath().toString());
    }

    // ---------- convertDateToString ----------

    @Test
    void convertDateToString_formateSelonLePattern() {
        ZonedDateTime date = ZonedDateTime.of(2025, 1, 3, 8, 15, 42, 0, ZoneId.of("UTC"));
        assertEquals("2025-01-03T08:15:42",
                DateUtil.convertDateToString(date, DateUtil.BULK_OUTPUT_DATE_FORMAT));
    }

    // ---------- Sur le DTO réel (MFSRDV-861) ----------
    // Décommenter si ActorInProposalDto a des setters Lombok.

    /*
    @Test
    void actorInProposalDto_refuseCreationDateInvalide() {
        ActorInProposalDto dto = new ActorInProposalDto();
        dto.setDealerId("dealer-1");      // @NotNull
        dto.setCreationDate("test");

        Set<ConstraintViolation<ActorInProposalDto>> violations = validator.validate(dto);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("creationDate")));
    }

    @Test
    void actorInProposalDto_accepteFormatDateToString() {
        ActorInProposalDto dto = new ActorInProposalDto();
        dto.setDealerId("dealer-1");
        dto.setCreationDate("Fri Jan 03 08:15:42 UTC 2025");

        assertTrue(validator.validate(dto).stream()
                .noneMatch(v -> v.getPropertyPath().toString().equals("creationDate")));
    }
    */
}
