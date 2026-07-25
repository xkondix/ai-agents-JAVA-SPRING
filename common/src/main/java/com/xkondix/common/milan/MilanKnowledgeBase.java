package com.xkondix.common.milan;

import java.util.List;
import java.util.Map;

/**
 * Static AC Milan knowledge base — the data source behind the tools used by
 * BOTH patterns modules (patterns-langchain4j and patterns-spring-ai).
 *
 * Deliberately in-memory and framework-free: the point of the patterns
 * modules is composition of LLM calls, not data engineering. Tool classes in
 * each module wrap this class with the framework-specific @Tool annotations.
 *
 * Data is illustrative (real names, simplified numbers).
 */
public final class MilanKnowledgeBase {

    private MilanKnowledgeBase() {
    }

    public record Player(String name, String position, int shirtNumber, double rating) {}

    public record Transfer(String player, String fromClub, String toClub,
                           double feeMillions, String window) {}

    public record Rumor(String player, String linkedClub, int probabilityPercent,
                        String secretNote) {}

    private static final Map<Integer, List<Player>> SQUADS = Map.of(
            2007, List.of(
                    new Player("Dida", "GK", 1, 7.4),
                    new Player("Paolo Maldini", "CB", 3, 8.8),
                    new Player("Alessandro Nesta", "CB", 13, 8.6),
                    new Player("Gennaro Gattuso", "CM", 8, 8.2),
                    new Player("Andrea Pirlo", "CM", 21, 9.0),
                    new Player("Clarence Seedorf", "CM", 10, 8.5),
                    new Player("Kaka", "AM", 22, 9.4),
                    new Player("Filippo Inzaghi", "ST", 9, 8.7)),
            2024, List.of(
                    new Player("Mike Maignan", "GK", 16, 8.4),
                    new Player("Theo Hernandez", "LB", 19, 8.3),
                    new Player("Fikayo Tomori", "CB", 23, 7.8),
                    new Player("Tijjani Reijnders", "CM", 14, 8.1),
                    new Player("Christian Pulisic", "RW", 11, 8.2),
                    new Player("Rafael Leao", "LW", 10, 8.5),
                    new Player("Alvaro Morata", "ST", 7, 7.6)));

    private static final List<Transfer> TRANSFERS = List.of(
            new Transfer("Kaka", "Sao Paulo", "AC Milan", 8.5, "2003 summer"),
            new Transfer("Andriy Shevchenko", "AC Milan", "Chelsea", 43.9, "2006 summer"),
            new Transfer("Kaka", "AC Milan", "Real Madrid", 67.0, "2009 summer"),
            new Transfer("Zlatan Ibrahimovic", "Barcelona", "AC Milan", 24.0, "2010 summer"),
            new Transfer("Rafael Leao", "Lille", "AC Milan", 29.5, "2019 summer"),
            new Transfer("Mike Maignan", "Lille", "AC Milan", 13.0, "2021 summer"),
            new Transfer("Christian Pulisic", "Chelsea", "AC Milan", 20.0, "2023 summer"));

    private static final List<Rumor> RUMORS = List.of(
            new Rumor("Jonathan David", "AC Milan", 65,
                    "Agent met the sporting director twice in Casa Milan last week."),
            new Rumor("Rafael Leao", "Al-Hilal", 20,
                    "Saudi bid of 90M rejected; player wants Champions League football."),
            new Rumor("Joshua Zirkzee", "AC Milan", 45,
                    "Release clause expires end of June; board split on the fee."));

    public static List<Player> squad(int year) {
        return SQUADS.getOrDefault(year, List.of());
    }

    public static List<Integer> availableSeasons() {
        return SQUADS.keySet().stream().sorted().toList();
    }

    public static List<Transfer> transfers(String windowContains) {
        if (windowContains == null || windowContains.isBlank()) {
            return TRANSFERS;
        }
        String needle = windowContains.toLowerCase();
        return TRANSFERS.stream()
                .filter(t -> t.window().toLowerCase().contains(needle))
                .toList();
    }

    public static Player playerStats(String name) {
        return SQUADS.values().stream()
                .flatMap(List::stream)
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    /** Secret transfer rumors — in the MCP modules this sits behind the
     *  human Approval Flow; here it is plain data for pattern demos. */
    public static List<Rumor> secretRumors() {
        return RUMORS;
    }
}
