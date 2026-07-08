package ua.homecraft.guild.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GuildTalents {
    public static final String MEMBER = "member";
    public static final String GOLEM = "golem";

    private static final List<TalentDefinition> DEFINITIONS = List.of(
            def("member_xp_1", MEMBER, 1, "Навчання громади I", "Учасники гільдії отримують додатковий досвід на території гільдії.", "+5% до досвіду", 1, 1, "", "member_xp"),
            def("member_potion_1", MEMBER, 1, "Травницькі знання I", "Зілля діють трохи довше на учасників гільдії.", "+10% до тривалості зіль", 1, 1, "", "member_potion"),
            def("member_weapon_1", MEMBER, 2, "Бойова підготовка I", "Зброя учасників завдає більше шкоди на території гільдії.", "+5% до шкоди зброєю", 1, 2, "", "member_weapon"),
            def("member_armor_1", MEMBER, 2, "Майстерність обладунків I", "Вдягнені обладунки учасників стають ефективнішими.", "+5% до броні", 1, 2, "", "member_armor"),
            def("member_speed_1", MEMBER, 3, "Легка хода I", "Учасники швидше пересуваються на території гільдії.", "+5% до швидкості руху", 1, 3, "", "member_speed"),
            def("member_jump_1", MEMBER, 3, "Спритність I", "Учасники отримують трохи вищий стрибок на території гільдії.", "+0.08 до сили стрибка", 1, 3, "", "member_jump"),
            def("member_xp_2", MEMBER, 4, "Навчання громади II", "Учасники гільдії отримують ще більше досвіду.", "ще +5% до досвіду", 1, 4, "member_xp_1", "member_xp"),
            def("member_potion_2", MEMBER, 4, "Травницькі знання II", "Зілля діють ще довше на учасників гільдії.", "ще +10% до тривалості зіль", 1, 4, "member_potion_1", "member_potion"),
            def("member_night_vision", MEMBER, 4, "Нічне бачення", "Учасники отримують безкінечний гільдійний ефект нічного бачення.", "безкінечне Night Vision", 1, 4, "member_potion_1", "member_night_vision"),
            def("member_speed_2", MEMBER, 5, "Легка хода II", "Учасники ще швидше пересуваються на території гільдії.", "ще +5% до швидкості руху", 1, 5, "member_speed_1", "member_speed"),
            def("member_weapon_2", MEMBER, 5, "Бойова підготовка II", "Зброя учасників завдає ще більше шкоди.", "ще +5% до шкоди зброєю", 1, 5, "member_weapon_1", "member_weapon"),
            def("member_deep_breath", MEMBER, 5, "Подих глибин", "Учасники отримують безкінечне дихання під водою як гільдійний баф.", "безкінечне Water Breathing", 1, 5, "member_potion_1", "member_water_breathing"),
            def("member_field_training", MEMBER, 5, "Польовий вишкіл", "Комбінований талант для розвитку і бою.", "+5% досвіду і +5% шкоди зброєю", 1, 5, "member_xp_1,member_weapon_1", "member_combo"),
            def("member_armor_2", MEMBER, 6, "Майстерність обладунків II", "Вдягнені обладунки учасників стають ще ефективнішими.", "ще +5% до броні", 1, 6, "member_armor_1", "member_armor"),
            def("member_jump_2", MEMBER, 6, "Спритність II", "Стрибок учасників стає трохи вищим.", "ще +0.07 до сили стрибка", 1, 6, "member_jump_1", "member_jump"),
            def("member_fire_ward", MEMBER, 6, "Вогняний захист", "Учасники отримують безкінечний гільдійний вогняний захист.", "безкінечне Fire Resistance", 1, 6, "member_potion_2", "member_fire_resistance"),
            def("member_speed_3", MEMBER, 7, "Легка хода III", "Учасники значно швидше пересуваються на території гільдії.", "ще +10% до швидкості руху", 1, 7, "member_speed_2", "member_speed"),
            def("member_resilience", MEMBER, 7, "Стійкість громади", "Учасники краще тримають оборону на території гільдії.", "-5% отриманої шкоди від hostile mobs", 1, 7, "member_armor_2", "member_resilience"),
            def("member_vanguard_oath", MEMBER, 7, "Клятва варти", "Комбінований оборонний талант для гільдійної варти.", "+5% броні і ще -5% шкоди від hostile mobs", 1, 7, "member_armor_2,member_resilience", "member_combo_defense"),
            def("member_swift_strike", MEMBER, 7, "Швидкий натиск", "Комбінований бойовий талант для мобільних учасників.", "+5% швидкості і +5% шкоди зброєю", 1, 7, "member_speed_2,member_weapon_2", "member_combo_speed"),

            def("golem_health_1", GOLEM, 1, "Посилений каркас I", "Максимальне здоров’я гільдійних големів зростає.", "+5% до max HP големів", 1, 1, "", "golem_health"),
            def("golem_regen_1", GOLEM, 1, "Самовідновлення I", "Големи швидше лікуються біля тотема.", "+10% до лікування біля тотема", 1, 1, "", "golem_regen"),
            def("golem_damage_1", GOLEM, 2, "Важкий удар I", "Гільдійні големи завдають більше шкоди.", "+5% до шкоди големів", 1, 2, "", "golem_damage"),
            def("golem_speed_1", GOLEM, 2, "Марш варти I", "Гільдійні големи рухаються швидше.", "+5% до швидкості големів", 1, 2, "", "golem_speed"),
            def("golem_out_of_combat_regen_1", GOLEM, 3, "Тиха регенерація I", "Големи повільно відновлюються поза боєм на території гільдії.", "1 HP кожні 8 секунд поза боєм", 1, 3, "", "golem_ooc_regen"),
            def("golem_route_1", GOLEM, 3, "Пильний маршрут I", "Големи трохи краще шукають безпечні обходи.", "+10% до ефективності безпечного обходу", 1, 3, "", "golem_route"),
            def("golem_health_2", GOLEM, 4, "Посилений каркас II", "Максимальне здоров’я големів зростає ще сильніше.", "ще +5% до max HP големів", 1, 4, "golem_health_1", "golem_health"),
            def("golem_regen_2", GOLEM, 4, "Самовідновлення II", "Големи ще швидше лікуються біля тотема.", "ще +10% до лікування біля тотема", 1, 4, "golem_regen_1", "golem_regen"),
            def("golem_night_watch", GOLEM, 5, "Нічна варта", "Големи краще тримаються біля учасників уночі та швидше займають точки перехоплення.", "кращий нічний patrol/intercept", 1, 5, "golem_route_1", "golem_night_watch"),
            def("golem_damage_2", GOLEM, 5, "Важкий удар II", "Гільдійні големи завдають ще більше шкоди.", "ще +5% до шкоди големів", 1, 5, "golem_damage_1", "golem_damage"),
            def("golem_speed_2", GOLEM, 5, "Марш варти II", "Гільдійні големи рухаються ще швидше.", "ще +5% до швидкості големів", 1, 5, "golem_speed_1", "golem_speed"),
            def("golem_guardian_matrix", GOLEM, 5, "Матриця захисту", "Комбіноване посилення каркаса й сили удару.", "+5% HP і +5% шкоди големів", 1, 5, "golem_health_1,golem_damage_1", "golem_combo"),
            def("golem_out_of_combat_regen_2", GOLEM, 6, "Тиха регенерація II", "Позабойове відновлення големів стає швидшим.", "1 HP кожні 5 секунд поза боєм", 1, 6, "golem_out_of_combat_regen_1", "golem_ooc_regen"),
            def("golem_intercept_1", GOLEM, 6, "Перехоплення загрози", "Големи швидше реагують на тривогу біля учасників.", "швидша реакція на напад", 1, 6, "", "golem_intercept"),
            def("golem_reactive_armor", GOLEM, 6, "Реактивна броня", "Комбіноване посилення живучості та лікування біля тотема.", "+5% HP і +10% лікування біля тотема", 1, 6, "golem_health_2,golem_regen_1", "golem_reactive_armor"),
            def("golem_war_march", GOLEM, 6, "Бойовий марш", "Комбіноване посилення швидкості й перехоплення.", "+5% швидкості і кращий response route", 1, 6, "golem_speed_2,golem_intercept_1", "golem_war_march"),
            def("golem_elite_command", GOLEM, 7, "Командирська ланка", "Елітний голем краще координує звичайних големів.", "менше скупчення і кращий розподіл точок", 1, 7, "", "golem_elite_command"),
            def("golem_crushing_force", GOLEM, 7, "Нищівна сила", "Големи сильніше б’ють hostile mobs.", "+5% додаткової шкоди проти hostile mobs", 1, 7, "golem_damage_2", "golem_crushing_force"),
            def("golem_stone_heart", GOLEM, 7, "Кам’яне серце", "Фінальний комбінований талант для живучих големів.", "+5% HP і +5% шкоди проти hostile mobs", 1, 7, "golem_reactive_armor,golem_crushing_force", "golem_stone_heart")
    );

    private static final Map<String, TalentDefinition> BY_ID;
    static {
        Map<String, TalentDefinition> map = new LinkedHashMap<>();
        for (TalentDefinition definition : DEFINITIONS) map.put(definition.id(), definition);
        BY_ID = Collections.unmodifiableMap(map);
    }

    private GuildTalents() {}

    private static TalentDefinition def(String id, String branch, int row, String title, String description, String effect, int cost, int requiredGuildLevel, String prerequisite, String icon) {
        return new TalentDefinition(id, branch, row, title, description, effect, cost, requiredGuildLevel, prerequisite == null ? "" : prerequisite, icon == null || icon.isBlank() ? id : icon);
    }

    public static List<TalentDefinition> definitions() { return DEFINITIONS; }

    public static List<TalentDefinition> definitions(String branch) {
        String normalized = normalizeBranch(branch);
        List<TalentDefinition> out = new ArrayList<>();
        for (TalentDefinition definition : DEFINITIONS) if (definition.branch().equals(normalized)) out.add(definition);
        return out;
    }

    public static TalentDefinition byId(String id) {
        if (id == null) return null;
        return BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean validBranch(String branch) {
        String normalized = normalizeBranch(branch);
        return MEMBER.equals(normalized) || GOLEM.equals(normalized);
    }

    public static String normalizeBranch(String branch) {
        String normalized = branch == null ? "" : branch.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("members") || normalized.equals("учасники")) return MEMBER;
        if (normalized.equals("golems") || normalized.equals("големи")) return GOLEM;
        return normalized;
    }

    public static String branchLabel(String branch) {
        return GOLEM.equals(normalizeBranch(branch)) ? "големів" : "учасників";
    }

    public static List<String> prerequisites(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String id = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
            if (!id.isBlank()) out.add(id);
        }
        return out;
    }

    public record TalentDefinition(String id, String branch, int row, String title, String description, String effect, int cost, int requiredGuildLevel, String prerequisite, String icon) {}
}
