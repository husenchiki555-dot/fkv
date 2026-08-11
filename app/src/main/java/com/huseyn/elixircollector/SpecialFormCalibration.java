package com.huseyn.elixircollector;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stores which special form is actually active for each card in the user's calibrated deck. */
public final class SpecialFormCalibration {
    public enum Form { NORMAL, EVO, HERO, CHAMPION }

    private static final String PREFS = "royalevision_my_deck_forms";
    private static final String KEY = "forms";

    private static final Set<String> EVOS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ice_spirit","skeletons","bats","bomber","giant_snowball","wall_breakers","zap",
            "archers","cannon","dart_goblin","firecracker","goblin_barrel","knight","princess",
            "royal_ghost","skeleton_army","skeleton_barrel","baby_dragon","battle_ram","furnace",
            "goblin_cage","goblin_drill","hunter","inferno_dragon","lumberjack","mortar","musketeer",
            "tesla","valkyrie","barbarians","electro_dragon","executioner","minion_horde","royal_hogs",
            "witch","wizard","goblin_giant","royal_giant","mega_knight","pekka","royal_recruits",
            "elite_barbarians"
    )));

    private static final Set<String> HEROES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "knight","mini_pekka","musketeer","giant","wizard","ice_golem","goblins","mega_minion",
            "barbarian_barrel","magic_archer","balloon","dark_prince","bowler","tombstone",
            "berserker","valkyrie"
    )));

    private static final Set<String> CHAMPIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "golden_knight","skeleton_king","archer_queen","mighty_miner",
            "boss_bandit","monk","little_prince","goblinstein"
    )));

    public static boolean hasEvo(String id) { return EVOS.contains(baseId(id)); }
    public static boolean hasHero(String id) { return HEROES.contains(baseId(id)); }
    public static boolean isChampion(String id) { return CHAMPIONS.contains(baseId(id)); }

    public static List<Form> allowedForms(String id) {
        String base = baseId(id);
        ArrayList<Form> out = new ArrayList<>();
        out.add(Form.NORMAL);
        if (EVOS.contains(base)) out.add(Form.EVO);
        if (HEROES.contains(base)) out.add(Form.HERO);
        if (CHAMPIONS.contains(base)) out.add(Form.CHAMPION);
        return out;
    }

    public static Form get(Context context, String id) {
        return load(context).getOrDefault(baseId(id), Form.NORMAL);
    }

    public static Map<String, Form> load(Context context) {
        HashMap<String, Form> out = new HashMap<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (raw == null || raw.length() == 0) return out;
        for (String part : raw.split(";")) {
            int p = part.indexOf('=');
            if (p <= 0 || p >= part.length() - 1) continue;
            String id = baseId(part.substring(0, p));
            try { out.put(id, Form.valueOf(part.substring(p + 1))); }
            catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    public static void save(Context context, Map<String, Form> forms) {
        StringBuilder b = new StringBuilder();
        if (forms != null) {
            for (Map.Entry<String, Form> e : forms.entrySet()) {
                if (e.getValue() == null || e.getValue() == Form.NORMAL) continue;
                if (b.length() > 0) b.append(';');
                b.append(baseId(e.getKey())).append('=').append(e.getValue().name());
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, b.toString()).apply();
    }

    /** Current standard deck-slot rules: 1 Evo, 1 Hero/Champion, 1 Wild. */
    public static String validate(Map<String, Form> forms) {
        int evo = 0, heroLike = 0, special = 0;
        if (forms != null) {
            for (Map.Entry<String, Form> e : forms.entrySet()) {
                Form f = e.getValue();
                if (f == null || f == Form.NORMAL) continue;
                special++;
                if (f == Form.EVO) {
                    if (!hasEvo(e.getKey())) return "That card does not have an Evolution.";
                    evo++;
                } else if (f == Form.HERO) {
                    if (!hasHero(e.getKey())) return "That card does not have a Hero form.";
                    heroLike++;
                } else if (f == Form.CHAMPION) {
                    if (!isChampion(e.getKey())) return "That card is not a Champion.";
                    heroLike++;
                }
            }
        }
        if (special > 3) return "Only 3 special slots are available: Evo + Hero + Wild.";
        if (evo > 2) return "At most 2 Evolutions can be active (Evo slot + Wild slot).";
        if (heroLike > 2) return "At most 2 Hero/Champion forms can be active (Hero slot + Wild slot).";
        return null;
    }

    public static String summary(Map<String, Form> forms) {
        int evo = 0, hero = 0, champ = 0;
        if (forms != null) for (Form f : forms.values()) {
            if (f == Form.EVO) evo++;
            else if (f == Form.HERO) hero++;
            else if (f == Form.CHAMPION) champ++;
        }
        return "EVO " + evo + " • HERO " + hero + " • CHAMP " + champ + " • special " + (evo + hero + champ) + "/3";
    }

    public static String assetId(String cardId, Form form) {
        String id = baseId(cardId);
        if (form == Form.EVO) return id + "__evo";
        if (form == Form.HERO) return id + "__hero";
        return id;
    }

    private static String baseId(String id) {
        if (id == null) return "";
        if (id.equals("spirit_empress_ground") || id.equals("spirit_empress_flying")) return "spirit_empress";
        return id;
    }

    private SpecialFormCalibration() {}
}
