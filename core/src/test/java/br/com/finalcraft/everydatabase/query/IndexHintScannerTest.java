package br.com.finalcraft.everydatabase.query;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@code @Indexed} makes of a field's declared type, and which declarations it refuses.
 *
 * <p>Pure unit tests: the scanner runs at {@code build()} time and never touches a backend.
 */
@DisplayName("IndexHintScanner (@Indexed type resolution)")
class IndexHintScannerTest {

    enum Rarity { COMMON, EPIC }

    /** One field per type the scanner is expected to resolve on its own. */
    @SuppressWarnings("unused")
    static class AutoDetected {
        @Indexed String         text;
        @Indexed char           initial;
        @Indexed Character      grade;
        @Indexed Rarity         rarity;
        @Indexed byte           tier;
        @Indexed Short          slot;
        @Indexed int            score;
        @Indexed long           experience;
        @Indexed double         ratio;
        @Indexed BigDecimal     balance;
        @Indexed BigInteger     supply;
        @Indexed boolean        active;
        @Indexed Instant        seenAt;
        @Indexed LocalDateTime  editedAt;
        @Indexed ZonedDateTime  startsAt;
        @Indexed OffsetDateTime endsAt;
        @Indexed Date           archivedAt;
        @Indexed LocalDate      releasedOn;
        @Indexed UUID           guildId;
    }

    @Test
    @DisplayName("every supported Java type resolves to its own FieldType")
    void autoDetection_coversTheSupportedTypes() {
        Map<String, IndexHint.FieldType> byPath = IndexHint.fromAnnotations(AutoDetected.class).stream()
            .collect(Collectors.toMap(IndexHint::fieldPath, IndexHint::fieldType));

        assertEquals(IndexHint.FieldType.STRING,    byPath.get("text"));
        assertEquals(IndexHint.FieldType.STRING,    byPath.get("initial"),    "a char is a one-character string");
        assertEquals(IndexHint.FieldType.STRING,    byPath.get("grade"));
        assertEquals(IndexHint.FieldType.STRING,    byPath.get("rarity"),     "an enum indexes as its name");
        assertEquals(IndexHint.FieldType.INT,       byPath.get("tier"),       "a byte is a narrow int");
        assertEquals(IndexHint.FieldType.INT,       byPath.get("slot"),       "a short is a narrow int");
        assertEquals(IndexHint.FieldType.INT,       byPath.get("score"));
        assertEquals(IndexHint.FieldType.LONG,      byPath.get("experience"));
        assertEquals(IndexHint.FieldType.DOUBLE,    byPath.get("ratio"));
        assertEquals(IndexHint.FieldType.DECIMAL,   byPath.get("balance"));
        assertEquals(IndexHint.FieldType.DECIMAL,   byPath.get("supply"),     "a BigInteger is exact, so it indexes exactly");
        assertEquals(IndexHint.FieldType.BOOLEAN,   byPath.get("active"));
        assertEquals(IndexHint.FieldType.TIMESTAMP, byPath.get("seenAt"));
        assertEquals(IndexHint.FieldType.TIMESTAMP, byPath.get("editedAt"));
        assertEquals(IndexHint.FieldType.TIMESTAMP, byPath.get("startsAt"));
        assertEquals(IndexHint.FieldType.TIMESTAMP, byPath.get("endsAt"));
        assertEquals(IndexHint.FieldType.TIMESTAMP, byPath.get("archivedAt"));
        assertEquals(IndexHint.FieldType.DATE,      byPath.get("releasedOn"), "a LocalDate is a day, not a moment");
        assertEquals(IndexHint.FieldType.UUID,      byPath.get("guildId"));
        assertEquals(19, byPath.size(), "every annotated field must produce exactly one hint");
    }

    @Test
    @DisplayName("a LocalDate declared as a TIMESTAMP index fails at build(), instead of indexing null forever")
    void localDateAsTimestamp_isRefused() {
        @SuppressWarnings("unused")
        class Event {
            @Indexed(type = Instant.class) LocalDate releasedOn;
        }

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> IndexHint.fromAnnotations(Event.class));

        assertTrue(ex.getMessage().contains("releasedOn"), "the message must name the field: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("LocalDate"),  "the message must name the type: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("DATE"),       "the message must teach the way out: " + ex.getMessage());
    }

    @Test
    @DisplayName("the other date-ish mismatches are refused too, in both directions")
    void otherTemporalMismatches_areRefused() {
        @SuppressWarnings("unused")
        class WithLocalTime {
            @Indexed(type = Instant.class) LocalTime opensAt;
        }
        @SuppressWarnings("unused")
        class WithYearMonth {
            @Indexed(type = Instant.class) YearMonth season;
        }
        @SuppressWarnings("unused")
        class WithDuration {
            @Indexed(type = Instant.class) Duration cooldown;
        }
        @SuppressWarnings("unused")
        class InstantAsDay {
            @Indexed(type = LocalDate.class) Instant seenAt;
        }

        for (Class<?> entity : new Class<?>[]{WithLocalTime.class, WithYearMonth.class, WithDuration.class}) {
            assertThrows(IllegalArgumentException.class, () -> IndexHint.fromAnnotations(entity),
                entity.getSimpleName() + " does not name a moment, so a TIMESTAMP index would be all nulls");
        }
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> IndexHint.fromAnnotations(InstantAsDay.class));
        assertTrue(ex.getMessage().contains("calendar day"),
            "an Instant has no day without a zone, and the message must say so: " + ex.getMessage());
    }

    @Test
    @DisplayName("a deliberate downgrade and a nested path are still allowed")
    void legitimateOverrides_stillBuild() {
        @SuppressWarnings("unused")
        class Wallet {
            // Lossy on purpose - a double index over a decimal field is the caller's call to make.
            @Indexed(type = double.class) BigDecimal balance;
            // The annotated field's own type says nothing about the value the path names.
            @Indexed(path = "issuer.name", type = String.class) Object issuer;
            // A type that serialises as text is indexable by saying so.
            @Indexed(type = String.class) URI homepage;
            @Indexed(type = String.class) Duration cooldown;
        }

        Map<String, IndexHint.FieldType> byPath = IndexHint.fromAnnotations(Wallet.class).stream()
            .collect(Collectors.toMap(IndexHint::fieldPath, IndexHint::fieldType));

        assertEquals(IndexHint.FieldType.DOUBLE, byPath.get("balance"));
        assertEquals(IndexHint.FieldType.STRING, byPath.get("issuer.name"));
        assertEquals(IndexHint.FieldType.STRING, byPath.get("homepage"));
        assertEquals(IndexHint.FieldType.STRING, byPath.get("cooldown"));
    }

    @Test
    @DisplayName("an unsupported type names itself, and the message teaches both ways out")
    void unsupportedType_explainsTheOptions() {
        @SuppressWarnings("unused")
        class Region {
            double x;
        }
        @SuppressWarnings("unused")
        class BadEntity {
            @Indexed Region spawn;
        }

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> IndexHint.fromAnnotations(BadEntity.class));

        assertTrue(ex.getMessage().contains("Region"), "the message must name the type: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("type = String.class"),
            "the message must teach the type= escape: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("path ="),
            "the message must teach the nested-path escape: " + ex.getMessage());
    }

    @Test
    @DisplayName("the descriptor builder surfaces the same refusal")
    void builder_surfacesTheRefusal() {
        @SuppressWarnings("unused")
        class Event {
            UUID id;
            @Indexed(type = Instant.class) LocalDate releasedOn;
        }
        Function<Event, UUID> key = e -> e.id;

        assertThrows(IllegalArgumentException.class, () ->
            EntityDescriptor.builder(UUID.class, Event.class)
                .collection("events")
                .keyExtractor(key::apply)
                .codec(new JacksonJsonCodec<>(Event.class))
                .build());
    }
}
