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
 *
 * SQUADS ARE COMPLETE ON PURPOSE. The first version listed 8 players for
 * 2007 and 7 for 2024 — no full-backs, one striker. The evaluator-optimizer
 * pattern asks for "a starting XI using only listed players", which was
 * therefore impossible: both frameworks filled the gaps with hallucinated
 * (if historically correct) names and the scorer waved them through. A loop
 * whose exit condition cannot be met by honest output demonstrates nothing.
 * Each season now has a full XI plus a bench, so "only listed players" is
 * satisfiable and a proposal with an outsider is a real defect to catch.
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
                    // Athens 2007 XI (4-3-2-1)
                    new Player("Dida", "GK", 1, 7.4),
                    new Player("Massimo Oddo", "RB", 44, 7.6),
                    new Player("Alessandro Nesta", "CB", 13, 8.6),
                    new Player("Paolo Maldini", "CB", 3, 8.8),
                    new Player("Marek Jankulovski", "LB", 18, 7.5),
                    new Player("Gennaro Gattuso", "CM", 8, 8.2),
                    new Player("Andrea Pirlo", "CM", 21, 9.0),
                    new Player("Massimo Ambrosini", "CM", 23, 7.7),
                    new Player("Clarence Seedorf", "AM", 10, 8.5),
                    new Player("Kaka", "AM", 22, 9.4),
                    new Player("Filippo Inzaghi", "ST", 9, 8.7),
                    // bench
                    new Player("Zeljko Kalac", "GK", 16, 6.9),
                    new Player("Cafu", "RB", 2, 7.8),
                    new Player("Kakha Kaladze", "CB", 4, 7.4),
                    new Player("Giuseppe Favalli", "LB", 5, 7.0),
                    new Player("Cristian Brocchi", "CM", 20, 7.0),
                    new Player("Alberto Gilardino", "ST", 11, 7.9)),
            2024, List.of(
                    // 2024 XI (4-3-3)
                    new Player("Mike Maignan", "GK", 16, 8.4),
                    new Player("Davide Calabria", "RB", 2, 7.3),
                    new Player("Fikayo Tomori", "CB", 23, 7.8),
                    new Player("Malick Thiaw", "CB", 28, 7.4),
                    new Player("Theo Hernandez", "LB", 19, 8.3),
                    new Player("Youssouf Fofana", "CM", 29, 7.5),
                    new Player("Tijjani Reijnders", "CM", 14, 8.1),
                    new Player("Ruben Loftus-Cheek", "CM", 8, 7.4),
                    new Player("Christian Pulisic", "RW", 11, 8.2),
                    new Player("Rafael Leao", "LW", 10, 8.5),
                    new Player("Alvaro Morata", "ST", 7, 7.6),
                    // bench
                    new Player("Marco Sportiello", "GK", 57, 6.8),
                    new Player("Emerson Royal", "RB", 22, 6.9),
                    new Player("Matteo Gabbia", "CB", 46, 7.2),
                    new Player("Yunus Musah", "CM", 80, 7.0),
                    new Player("Samuel Chukwueze", "RW", 21, 7.1),
                    new Player("Tammy Abraham", "ST", 90, 7.2)));

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
