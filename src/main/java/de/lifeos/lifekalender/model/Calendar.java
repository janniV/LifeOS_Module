package de.lifeos.lifekalender.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

/**
 * Repräsentiert einen Kalender im LifeKalender-Modul.
 * Jeder Kalender hat eine eindeutige ID, einen Namen, eine Farbe und einen Sichtbarkeitsstatus.
 */
public class Calendar {
    private final String id;
    private String name;
    private String color;
    private boolean visible;

    @JsonCreator
    public Calendar(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("color") String color,
            @JsonProperty("visible") boolean visible) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
        this.name = (name != null && !name.isBlank()) ? name : "Neuer Kalender";
        this.color = (color != null && !color.isBlank()) ? color : generateRandomColor();
        this.visible = visible;
    }

    public Calendar(String name) {
        this(UUID.randomUUID().toString(), name, generateRandomColor(), true);
    }

    /**
     * Generiert eine zufällige Farbe aus einer vordefinierten Palette.
     */
    private static String generateRandomColor() {
        String[] colors = {
            "#FF5733", "#33FF57", "#3357FF", "#F3FF33", "#FF33F3",
            "#33FFF3", "#8A2BE2", "#FF7F50", "#6495ED", "#DC143C",
            "#20B2AA", "#FFD700", "#ADFF2F", "#FF69B4", "#1E90FF",
            "#FF4500", "#9400D3", "#00FA9A", "#FF1493", "#00BFFF"
        };
        return colors[(int) (Math.random() * colors.length)];
    }

    // Getter und Setter
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "Name darf nicht null sein");
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = Objects.requireNonNull(color, "Farbe darf nicht null sein");
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Calendar calendar = (Calendar) o;
        return id.equals(calendar.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Calendar{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", color='" + color + '\'' +
                ", visible=" + visible +
                '}';
    }
}
