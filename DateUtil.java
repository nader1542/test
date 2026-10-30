package com.rcibs.rdv.api.repository.util;

import jakarta.validation.ConstraintValidatorContext; // javax.validation si Spring Boot 2
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

public class DateUtil {

    // "uuuu" (et non "yyyy") est obligatoire avec ResolverStyle.STRICT
    public final static List<String> DATE_TIME_FORMATS = List.of(
            "uuuu-MM-dd",
            "uuuu-MM-dd'T'HH:mm:ss[XXX][X]",
            "uuuu-MM-dd'T'HH:mm:ss.SSSSSS[XXX][X]",
            "EEE MMM dd HH:mm:ss zzz uuuu");

    public final static List<String> DATE_FORMATS = List.of("uuuu-MM-dd");

    public final static String BULK_OUTPUT_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

    public static boolean isValidDate(String dateString, List<String> validFormats,
                                      ConstraintValidatorContext context) {
        if (dateString == null) {
            return true;
        }
        for (String format : validFormats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format, Locale.ENGLISH)
                        .withResolverStyle(ResolverStyle.STRICT);
                // lève DateTimeParseException si la chaîne entière ne correspond pas
                formatter.parse(dateString);
                return true;
            } catch (DateTimeParseException ignored) {
                // essayer le format suivant
            }
        }
        if (context != null) {
            HibernateConstraintValidatorContext hibernateContext =
                    context.unwrap(HibernateConstraintValidatorContext.class);
            hibernateContext.addMessageParameter("expectedValues", validFormats);
            hibernateContext.addMessageParameter("invalidValue", dateString);
        }
        return false;
    }

    public static String convertDateToString(ZonedDateTime dateTime, String format) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        return dateTime.format(formatter);
    }
}
