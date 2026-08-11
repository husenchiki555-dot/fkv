package com.huseyn.elixircollector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class CardCatalog {
    public static final class Card {
        public final String id;
        public final String deckId;
        public final String displayName;
        public final int cost;
        public final boolean mirror;

        Card(String id, String deckId, String displayName, int cost, boolean mirror) {
            this.id=id; this.deckId=deckId; this.displayName=displayName; this.cost=cost; this.mirror=mirror;
        }
    }

    public static final List<Card> ALL;
    static {
        ArrayList<Card> list = new ArrayList<>();
        list.add(new Card("electro_spirit", "electro_spirit", "Electro Spirit", 1, false));
        list.add(new Card("fire_spirit", "fire_spirit", "Fire Spirit", 1, false));
        list.add(new Card("heal_spirit", "heal_spirit", "Heal Spirit", 1, false));
        list.add(new Card("ice_spirit", "ice_spirit", "Ice Spirit", 1, false));
        list.add(new Card("skeletons", "skeletons", "Skeletons", 1, false));
        list.add(new Card("barbarian_barrel", "barbarian_barrel", "Barbarian Barrel", 2, false));
        list.add(new Card("bats", "bats", "Bats", 2, false));
        list.add(new Card("berserker", "berserker", "Berserker", 2, false));
        list.add(new Card("bomber", "bomber", "Bomber", 2, false));
        list.add(new Card("giant_snowball", "giant_snowball", "Giant Snowball", 2, false));
        list.add(new Card("goblin_curse", "goblin_curse", "Goblin Curse", 2, false));
        list.add(new Card("goblins", "goblins", "Goblins", 2, false));
        list.add(new Card("ice_golem", "ice_golem", "Ice Golem", 2, false));
        list.add(new Card("rage", "rage", "Rage", 2, false));
        list.add(new Card("spear_goblins", "spear_goblins", "Spear Goblins", 2, false));
        list.add(new Card("suspicious_bush", "suspicious_bush", "Suspicious Bush", 2, false));
        list.add(new Card("the_log", "the_log", "The Log", 2, false));
        list.add(new Card("wall_breakers", "wall_breakers", "Wall Breakers", 2, false));
        list.add(new Card("zap", "zap", "Zap", 2, false));
        list.add(new Card("archers", "archers", "Archers", 3, false));
        list.add(new Card("arrows", "arrows", "Arrows", 3, false));
        list.add(new Card("bandit", "bandit", "Bandit", 3, false));
        list.add(new Card("cannon", "cannon", "Cannon", 3, false));
        list.add(new Card("clone", "clone", "Clone", 3, false));
        list.add(new Card("dart_goblin", "dart_goblin", "Dart Goblin", 3, false));
        list.add(new Card("earthquake", "earthquake", "Earthquake", 3, false));
        list.add(new Card("elixir_golem", "elixir_golem", "Elixir Golem", 3, false));
        list.add(new Card("firecracker", "firecracker", "Firecracker", 3, false));
        list.add(new Card("fisherman", "fisherman", "Fisherman", 3, false));
        list.add(new Card("goblin_barrel", "goblin_barrel", "Goblin Barrel", 3, false));
        list.add(new Card("goblin_gang", "goblin_gang", "Goblin Gang", 3, false));
        list.add(new Card("guards", "guards", "Guards", 3, false));
        list.add(new Card("ice_wizard", "ice_wizard", "Ice Wizard", 3, false));
        list.add(new Card("knight", "knight", "Knight", 3, false));
        list.add(new Card("little_prince", "little_prince", "Little Prince", 3, false));
        list.add(new Card("mega_minion", "mega_minion", "Mega Minion", 3, false));
        list.add(new Card("miner", "miner", "Miner", 3, false));
        list.add(new Card("minions", "minions", "Minions", 3, false));
        list.add(new Card("princess", "princess", "Princess", 3, false));
        list.add(new Card("royal_delivery", "royal_delivery", "Royal Delivery", 3, false));
        list.add(new Card("royal_ghost", "royal_ghost", "Royal Ghost", 3, false));
        list.add(new Card("skeleton_army", "skeleton_army", "Skeleton Army", 3, false));
        list.add(new Card("skeleton_barrel", "skeleton_barrel", "Skeleton Barrel", 3, false));
        list.add(new Card("tombstone", "tombstone", "Tombstone", 3, false));
        list.add(new Card("tornado", "tornado", "Tornado", 3, false));
        list.add(new Card("vines", "vines", "Vines", 3, false));
        list.add(new Card("void", "void", "Void", 3, false));
        list.add(new Card("baby_dragon", "baby_dragon", "Baby Dragon", 4, false));
        list.add(new Card("battle_healer", "battle_healer", "Battle Healer", 4, false));
        list.add(new Card("battle_ram", "battle_ram", "Battle Ram", 4, false));
        list.add(new Card("bomb_tower", "bomb_tower", "Bomb Tower", 4, false));
        list.add(new Card("dark_prince", "dark_prince", "Dark Prince", 4, false));
        list.add(new Card("electro_wizard", "electro_wizard", "Electro Wizard", 4, false));
        list.add(new Card("fireball", "fireball", "Fireball", 4, false));
        list.add(new Card("flying_machine", "flying_machine", "Flying Machine", 4, false));
        list.add(new Card("freeze", "freeze", "Freeze", 4, false));
        list.add(new Card("furnace", "furnace", "Furnace", 4, false));
        list.add(new Card("goblin_cage", "goblin_cage", "Goblin Cage", 4, false));
        list.add(new Card("goblin_demolisher", "goblin_demolisher", "Goblin Demolisher", 4, false));
        list.add(new Card("goblin_drill", "goblin_drill", "Goblin Drill", 4, false));
        list.add(new Card("goblin_hut", "goblin_hut", "Goblin Hut", 4, false));
        list.add(new Card("golden_knight", "golden_knight", "Golden Knight", 4, false));
        list.add(new Card("hog_rider", "hog_rider", "Hog Rider", 4, false));
        list.add(new Card("hunter", "hunter", "Hunter", 4, false));
        list.add(new Card("inferno_dragon", "inferno_dragon", "Inferno Dragon", 4, false));
        list.add(new Card("lumberjack", "lumberjack", "Lumberjack", 4, false));
        list.add(new Card("magic_archer", "magic_archer", "Magic Archer", 4, false));
        list.add(new Card("mighty_miner", "mighty_miner", "Mighty Miner", 4, false));
        list.add(new Card("mini_pekka", "mini_pekka", "Mini P.E.K.K.A", 4, false));
        list.add(new Card("mortar", "mortar", "Mortar", 4, false));
        list.add(new Card("mother_witch", "mother_witch", "Mother Witch", 4, false));
        list.add(new Card("musketeer", "musketeer", "Musketeer", 4, false));
        list.add(new Card("night_witch", "night_witch", "Night Witch", 4, false));
        list.add(new Card("phoenix", "phoenix", "Phoenix", 4, false));
        list.add(new Card("poison", "poison", "Poison", 4, false));
        list.add(new Card("rune_giant", "rune_giant", "Rune Giant", 4, false));
        list.add(new Card("skeleton_dragons", "skeleton_dragons", "Skeleton Dragons", 4, false));
        list.add(new Card("skeleton_king", "skeleton_king", "Skeleton King", 4, false));
        list.add(new Card("tesla", "tesla", "Tesla", 4, false));
        list.add(new Card("valkyrie", "valkyrie", "Valkyrie", 4, false));
        list.add(new Card("zappies", "zappies", "Zappies", 4, false));
        list.add(new Card("archer_queen", "archer_queen", "Archer Queen", 5, false));
        list.add(new Card("balloon", "balloon", "Balloon", 5, false));
        list.add(new Card("barbarians", "barbarians", "Barbarians", 5, false));
        list.add(new Card("bowler", "bowler", "Bowler", 5, false));
        list.add(new Card("cannon_cart", "cannon_cart", "Cannon Cart", 5, false));
        list.add(new Card("electro_dragon", "electro_dragon", "Electro Dragon", 5, false));
        list.add(new Card("executioner", "executioner", "Executioner", 5, false));
        list.add(new Card("giant", "giant", "Giant", 5, false));
        list.add(new Card("goblin_machine", "goblin_machine", "Goblin Machine", 5, false));
        list.add(new Card("goblinstein", "goblinstein", "Goblinstein", 5, false));
        list.add(new Card("graveyard", "graveyard", "Graveyard", 5, false));
        list.add(new Card("inferno_tower", "inferno_tower", "Inferno Tower", 5, false));
        list.add(new Card("minion_horde", "minion_horde", "Minion Horde", 5, false));
        list.add(new Card("monk", "monk", "Monk", 5, false));
        list.add(new Card("prince", "prince", "Prince", 5, false));
        list.add(new Card("ram_rider", "ram_rider", "Ram Rider", 5, false));
        list.add(new Card("rascals", "rascals", "Rascals", 5, false));
        list.add(new Card("ronin", "ronin", "Ronin", 5, false));
        list.add(new Card("royal_hogs", "royal_hogs", "Royal Hogs", 5, false));
        list.add(new Card("witch", "witch", "Witch", 5, false));
        list.add(new Card("wizard", "wizard", "Wizard", 5, false));
        list.add(new Card("barbarian_hut", "barbarian_hut", "Barbarian Hut", 6, false));
        list.add(new Card("boss_bandit", "boss_bandit", "Boss Bandit", 6, false));
        list.add(new Card("elite_barbarians", "elite_barbarians", "Elite Barbarians", 6, false));
        list.add(new Card("elixir_collector", "elixir_collector", "Elixir Collector", 6, false));
        list.add(new Card("giant_skeleton", "giant_skeleton", "Giant Skeleton", 6, false));
        list.add(new Card("goblin_giant", "goblin_giant", "Goblin Giant", 6, false));
        list.add(new Card("lightning", "lightning", "Lightning", 6, false));
        list.add(new Card("rocket", "rocket", "Rocket", 6, false));
        list.add(new Card("royal_giant", "royal_giant", "Royal Giant", 6, false));
        list.add(new Card("sparky", "sparky", "Sparky", 6, false));
        list.add(new Card("spirit_empress_ground", "spirit_empress", "Spirit Empress (Ground)", 3, false));
        list.add(new Card("spirit_empress_flying", "spirit_empress", "Spirit Empress (Flying)", 6, false));
        list.add(new Card("x_bow", "x_bow", "X-Bow", 6, false));
        list.add(new Card("electro_giant", "electro_giant", "Electro Giant", 7, false));
        list.add(new Card("lava_hound", "lava_hound", "Lava Hound", 7, false));
        list.add(new Card("mega_knight", "mega_knight", "Mega Knight", 7, false));
        list.add(new Card("pekka", "pekka", "P.E.K.K.A", 7, false));
        list.add(new Card("royal_recruits", "royal_recruits", "Royal Recruits", 7, false));
        list.add(new Card("golem", "golem", "Golem", 8, false));
        list.add(new Card("three_musketeers", "three_musketeers", "Three Musketeers", 9, false));
        list.add(new Card("mirror", "mirror", "Mirror", 0, true));
        Collections.sort(list, new Comparator<Card>() {
            @Override public int compare(Card a, Card b) {
                int ac = a.mirror ? 99 : a.cost; int bc = b.mirror ? 99 : b.cost;
                int c = Integer.compare(ac, bc); if (c != 0) return c; return a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        ALL = Collections.unmodifiableList(list);
    }

    public static List<Card> choicesForCost(int cost) {
        ArrayList<Card> out = new ArrayList<>();
        for (Card c : ALL) if (!c.mirror && c.cost == cost) out.add(c);
        return out;
    }

    public static Card mirror() { for (Card c : ALL) if (c.mirror) return c; return null; }

    public static String shortName(String name) {
        if (name == null || name.length() == 0) return "?";
        String n = name.replace(" Evolution", " Evo").replace("Spirit Empress (Ground)", "Empress G").replace("Spirit Empress (Flying)", "Empress F");
        if (n.length() <= 10) return n;
        String[] parts = n.split(" ");
        if (parts.length >= 2) {
            StringBuilder initials = new StringBuilder();
            for (String p : parts) if (!p.isEmpty()) initials.append(Character.toUpperCase(p.charAt(0)));
            if (initials.length() >= 2 && initials.length() <= 5) return initials.toString();
        }
        return n.substring(0, 9) + "…";
    }

    private CardCatalog() {}
}
