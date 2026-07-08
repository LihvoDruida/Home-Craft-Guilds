package ua.homecraft.guild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import ua.homecraft.guild.HomeCraftGuildMod;
import ua.homecraft.guild.client.skin.GuildNpcSkins;
import ua.homecraft.guild.client.renderer.NpcPreviewRenderer;
import ua.homecraft.guild.entity.GuildRegistrarEntity;
import ua.homecraft.guild.entity.HomeCraftGuildEntities;
import ua.homecraft.guild.network.GuildActionPayload;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-authoritative NPC admin UI.
 *
 * v125 rewrites the editor around fixed panels, scrollable NPC/trade lists and a
 * real in-game entity preview. The old raw PNG blit preview was removed because
 * it could render the skin texture as large vertical strips over the whole GUI.
 */
public final class GuildNpcAdminScreen extends Screen {
    private static final String[][] TRADE_PRESETS = new String[][]{
            {"trader_basic", "Базовий", "Гільдійний Торговець", "guild_registrar"},
            {"trader_food", "Їжа", "Продуктовий торговець", "boy_green"},
            {"trader_tools", "Інструменти", "Торговець інструментами", "fighter_guy"},
            {"trader_weapons", "Зброя", "Героїчний зброяр", "medieval_knight"},
            {"trader_armor", "Броня", "Майстер броні", "trader_armor"},
            {"trader_elite", "Elite", "Елітний гільдійний торговець", "medieval_armor"}
    };

    private String snapshot;
    private final List<NpcRow> rows = new ArrayList<>();
    private final List<String> skins = new ArrayList<>();

    private String selectedKey = "guild_master";
    private String filter = "all";
    private int npcScroll;
    private int tradeScroll;
    private int skinScroll;
    private String editorTab = "main";
    private String previewStatus = "";
    private float previewYaw = 180.0F;
    private int previewScale = 66;
    private double lastMouseX;
    private double lastMouseY;

    private GuildRegistrarEntity previewEntity;
    private String previewEntitySkin = "";
    private String previewEntityKey = "";

    private EditBox keyBox;
    private EditBox nameBox;
    private EditBox skinBox;
    private EditBox presetBox;
    private EditBox buyItemBox;
    private EditBox buyCountBox;
    private EditBox sellItemBox;
    private EditBox sellCountBox;
    private EditBox maxUsesBox;
    private EditBox xpBox;
    private EditBox roleBox;
    private EditBox limitBox;
    private EditBox tradeNameBox;
    private EditBox enchantBox;

    public GuildNpcAdminScreen(String snapshot) {
        super(Component.literal("Home Craft — NPC Admin"));
        this.snapshot = snapshot == null ? "" : snapshot;
        parseSnapshot(this.snapshot);
        if (!rows.isEmpty()) selectedKey = rows.get(0).key;
        ensureDefaultSkins();
    }

    public void acceptServerSnapshot(String newSnapshot) {
        String normalizedSnapshot = newSnapshot == null ? "" : newSnapshot;
        UiState state = captureUiState();
        String keepKey = activeKey();
        this.snapshot = normalizedSnapshot;
        rows.clear();
        skins.clear();
        parseSnapshot(this.snapshot);
        ensureDefaultSkins();
        if (hasNpcKey(keepKey)) {
            selectedKey = keepKey;
        } else if (hasNpcKey(state.selectedKey)) {
            selectedKey = state.selectedKey;
        } else if (hasNpcKey(selectedKey)) {
            // keep current selection
        } else if (!rows.isEmpty()) {
            selectedKey = rows.get(0).key;
        }
        restoreUiStateFields(state);
        if (minecraft != null && minecraft.screen == this) {
            rebuildWidgets();
            restoreUiStateWidgets(state);
        }
    }

    private boolean hasNpcKey(String key) {
        if (key == null) return false;
        for (NpcRow row : rows) if (row.key.equals(key)) return true;
        return false;
    }

    private void ensureDefaultSkins() {
        String[] defaults = new String[]{
                "guild_master", "guild_registrar", "trader_armor", "medieval_armor", "medieval_knight", "fighter_guy", "boy_green"
        };
        for (String skin : defaults) {
            if (!skins.contains(skin)) skins.add(skin);
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        Layout l = layout();
        NpcRow selected = selectedRow();
        List<NpcRow> filtered = filteredRows();

        int titleY = l.y + 10;
        int filterX = l.leftX;
        String[][] filters = new String[][]{
                {"all", "Всі"}, {"system", "Системні"}, {"trader", "Торговці"}
        };
        int fx = filterX;
        int filterW = Math.max(48, (l.leftW - 8) / 3);
        for (String[] f : filters) {
            final String id = f[0];
            addRenderableWidget(Button.builder(Component.literal((id.equals(filter) ? "● " : "") + f[1]), b -> {
                filter = id;
                npcScroll = 0;
                rebuildWidgets();
            }).bounds(fx, titleY + 22, filterW, 20).build());
            fx += filterW + 4;
        }

        String[][] tabs = new String[][]{{"main", "Основне"}, {"skins", "Скіни"}, {"trades", "Товари"}, {"diagnostics", "Діагностика"}, {"placed", "Розставлені"}};
        int tabGap = 4;
        int tabW = Math.max(54, Math.min(104, (l.centerW - tabGap * (tabs.length - 1)) / tabs.length));
        for (int i = 0; i < tabs.length; i++) addEditorTabButton(l.centerX + i * (tabW + tabGap), titleY + 22, tabW, tabs[i][0], tabs[i][1]);

        int rowH = 24;
        int listY = l.y + 62;
        int listH = Math.max(90, l.bottomY - listY - 10);
        int visibleRows = Math.max(3, listH / rowH);
        int maxNpcScroll = Math.max(0, filtered.size() - visibleRows);
        npcScroll = Math.max(0, Math.min(npcScroll, maxNpcScroll));
        for (int i = 0; i < visibleRows && npcScroll + i < filtered.size(); i++) {
            NpcRow row = filtered.get(npcScroll + i);
            int yy = listY + i * rowH;
            String prefix = row.key.equals(selectedKey) ? "▶ " : (row.system ? "★ " : "☰ ");
            String label = prefix + shortenLabel(row.key + " / " + row.skin, 26);
            addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                selectedKey = row.key;
                tradeScroll = 0;
                rebuildWidgets();
            }).bounds(l.leftX, yy, l.leftW, 22).build());
        }
        if (filtered.size() > visibleRows) {
            addRenderableWidget(Button.builder(Component.literal("▲"), b -> { npcScroll = Math.max(0, npcScroll - 1); rebuildWidgets(); })
                    .bounds(l.leftX, l.bottomY - 48, 42, 20).build());
            addRenderableWidget(Button.builder(Component.literal("▼"), b -> { npcScroll = Math.min(maxNpcScroll, npcScroll + 1); rebuildWidgets(); })
                    .bounds(l.leftX + 46, l.bottomY - 48, 42, 20).build());
        }

        int formX = l.centerX;
        int formW = l.centerW;
        int y = l.y + 62;

        keyBox = new EditBox(this.font, formX, y, Math.min(220, formW), 20, Component.literal("Ключ NPC"));
        keyBox.setHint(Component.literal("trader_weapons"));
        keyBox.setMaxLength(32);
        keyBox.setValue(selected == null ? "trader_basic" : selected.key);
        keyBox.setEditable(selected == null || !selected.system);
        addRenderableWidget(keyBox);

        nameBox = new EditBox(this.font, formX, y + 36, formW, 20, Component.literal("Назва NPC"));
        nameBox.setHint(Component.literal("Назва NPC"));
        nameBox.setMaxLength(48);
        nameBox.setValue(selected == null ? "Гільдійний Торговець" : selected.name);
        addRenderableWidget(nameBox);

        skinBox = new EditBox(this.font, formX, y + 72, Math.max(150, formW - 76), 20, Component.literal("skinId"));
        skinBox.setHint(Component.literal("skinId"));
        skinBox.setMaxLength(48);
        skinBox.setValue(selected == null ? "guild_registrar" : selected.skin);
        addRenderableWidget(skinBox);
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> cycleSkin(-1)).bounds(formX + formW - 70, y + 72, 32, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> cycleSkin(1)).bounds(formX + formW - 34, y + 72, 32, 20).build());

        boolean selectedSystem = selected != null && selected.system;
        presetBox = new EditBox(this.font, formX, y + 112, Math.min(260, formW), 20, Component.literal("traderPresetId"));
        presetBox.setHint(Component.literal("traderPresetId"));
        presetBox.setMaxLength(48);
        presetBox.setValue(selected == null ? "trader_basic" : selected.presetId);
        presetBox.setEditable(!selectedSystem);
        addRenderableWidget(presetBox);

        int modeY = y + 150;
        if ("skins".equals(editorTab)) {
            int skinButtonW = Math.max(96, Math.min(132, (formW - 8) / 3));
            int rowsForSkins = Math.max(2, (l.bottomY - modeY - 46) / 22);
            int maxSkinButtons = Math.max(6, rowsForSkins * 3);
            int maxSkinScroll = Math.max(0, skins.size() - maxSkinButtons);
            skinScroll = Math.max(0, Math.min(skinScroll, maxSkinScroll));
            for (int i = 0; i < maxSkinButtons && skinScroll + i < skins.size(); i++) {
                String skinId = skins.get(skinScroll + i);
                int bx = formX + (i % 3) * (skinButtonW + 4);
                int by = modeY + (i / 3) * 22;
                String marker = skinBox != null && skinId.equalsIgnoreCase(skinBox.getValue().trim()) ? "● " : "";
                addRenderableWidget(Button.builder(Component.literal(marker + shortenLabel(skinId, 15)), b -> setSkinValue(skinId))
                        .bounds(bx, by, skinButtonW, 20).build());
            }
            int actionY = Math.min(l.bottomY - 26, modeY + rowsForSkins * 22 + 4);
            if (skins.size() > maxSkinButtons) {
                addRenderableWidget(Button.builder(Component.literal("▲ Скіни"), b -> { skinScroll = Math.max(0, skinScroll - 3); rebuildWidgets(); })
                        .bounds(formX, actionY, 76, 20).build());
                addRenderableWidget(Button.builder(Component.literal("▼ Скіни"), b -> { skinScroll = Math.min(maxSkinScroll, skinScroll + 3); rebuildWidgets(); })
                        .bounds(formX + 80, actionY, 76, 20).build());
            }
            addButtonRows(formX, actionY, formW, 4,
                    new UiButton("Скинути preview", 126, true, () -> { previewYaw = 180.0F; previewScale = 66; }),
                    new UiButton("Зберегти назву і скін", 172, true, this::saveSelected));
        } else if ("diagnostics".equals(editorTab)) {
            // Diagnostics tab has no widgets in rebuildWidgets(); labels are rendered in render().
        } else if ("placed".equals(editorTab)) {
            addButtonRows(formX, modeY, formW, 4,
                    new UiButton("Оновити список", 118, true, () -> send("npc_admin_refresh", "", "")),
                    new UiButton("Repair усі NPC", 142, true, () -> send("npc_admin_repair_all", "", "")));
        } else if ("trades".equals(editorTab)) {
            addPresetButtons(formX, modeY, formW, !selectedSystem);
            int actionY = modeY + 48;
            addButtonRows(formX, actionY, formW, 4,
                    new UiButton("Створити NPC", 128, true, this::createTrader),
                    new UiButton("Оновити товари з пресету", 190, !selectedSystem, this::applyPresetToSelected),
                    new UiButton("Оновити список", 112, true, () -> send("npc_admin_refresh", "", "")));
        } else {
            addPresetButtons(formX, modeY, formW, !selectedSystem);
            int buttonY = modeY + 52;
            addButtonRows(formX, buttonY, formW, 4,
                    new UiButton("Зберегти", 92, true, this::saveSelected),
                    new UiButton("Поставити тут", 106, true, () -> send("npc_admin_tp", activeKey(), "")),
                    new UiButton("Прибрати", 86, !selectedSystem, () -> send("npc_admin_remove", activeKey(), "")),
                    new UiButton("Оновити", 82, true, () -> send("npc_admin_refresh", "", "")),
                    new UiButton("Створити NPC", 128, true, this::createTrader),
                    new UiButton("Оновити товари з пресету", 190, !selectedSystem, this::applyPresetToSelected));
        }

        if ("trades".equals(editorTab) && selected != null) {
            int top = l.bottomY + 30;
            if (!selected.system) {
                addButtonRows(l.tradeX, top, l.tradeW, 4,
                        new UiButton("+ Додати товар", 126, true, () -> openTradeEditor(selected, -1)),
                        new UiButton("Оновити товари з пресету", 186, true, this::applyPresetToSelected),
                        new UiButton("Товари ▲", 74, true, () -> { tradeScroll = Math.max(0, tradeScroll - 1); rebuildWidgets(); }),
                        new UiButton("Товари ▼", 74, true, () -> { tradeScroll = tradeScroll + 1; rebuildWidgets(); }));

                int tradeButtonsListY = tradeListY(l, selected);
                int tradeButtonsListH = tradeListH(l, tradeButtonsListY);
                int tradeButtonsRowH = 22;
                int visible = Math.max(1, (tradeButtonsListH - 28) / tradeButtonsRowH);
                int maxScroll = Math.max(0, selected.trades.size() - visible);
                tradeScroll = Math.max(0, Math.min(tradeScroll, maxScroll));
                int actionW = 174;
                for (int i = 0; i < visible && tradeScroll + i < selected.trades.size(); i++) {
                    int idx = tradeScroll + i;
                    int yy = tradeButtonsListY + 26 + i * tradeButtonsRowH;
                    int ax = l.tradeX + l.tradeW - actionW - 6;
                    addRenderableWidget(Button.builder(Component.literal("✎"), b -> openTradeEditor(selected, idx)).bounds(ax, yy - 3, 34, 20).build());
                    addRenderableWidget(Button.builder(Component.literal("✕"), b -> removeTrade(selected, idx)).bounds(ax + 38, yy - 3, 30, 20).build());
                    addRenderableWidget(Button.builder(Component.literal("⧉"), b -> duplicateTrade(selected, idx)).bounds(ax + 72, yy - 3, 30, 20).build());
                    addRenderableWidget(Button.builder(Component.literal("↑"), b -> moveTrade(selected, idx, -1)).bounds(ax + 106, yy - 3, 30, 20).build());
                    addRenderableWidget(Button.builder(Component.literal("↓"), b -> moveTrade(selected, idx, 1)).bounds(ax + 140, yy - 3, 30, 20).build());
                }
            }
        }

        if ("placed".equals(editorTab)) {
            List<NpcRow> placedRows = rows;
            int placedListY = l.bottomY + 56;
            int placedListH = Math.max(48, l.y + l.h - 48 - placedListY);
            int rowH2 = 24;
            int visible = Math.max(1, (placedListH - 26) / rowH2);
            int maxScroll = Math.max(0, placedRows.size() - visible);
            tradeScroll = Math.max(0, Math.min(tradeScroll, maxScroll));
            for (int i = 0; i < visible && tradeScroll + i < placedRows.size(); i++) {
                NpcRow row = placedRows.get(tradeScroll + i);
                int yy = placedListY + 24 + i * rowH2;
                int ax = l.tradeX + l.tradeW - 280;
                addRenderableWidget(Button.builder(Component.literal("Вибрати"), b -> { selectedKey = row.key; editorTab = "main"; rebuildWidgets(); }).bounds(ax, yy - 4, 70, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Поставити тут"), b -> { selectedKey = row.key; send("npc_admin_tp", row.key, ""); }).bounds(ax + 74, yy - 4, 104, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Видалити"), b -> { selectedKey = row.key; send("npc_admin_remove", row.key, ""); }).bounds(ax + 182, yy - 4, 86, 20).build());
            }
        }

        addRenderableWidget(Button.builder(Component.literal("Закрити"), b -> onClose()).bounds(l.x + l.w - 112, l.y + l.h - 32, 96, 22).build());
    }

    private void addEditorTabButton(int x, int y, int w, String tab, String label) {
        addRenderableWidget(Button.builder(Component.literal((tab.equals(editorTab) ? "● " : "") + label), b -> {
            editorTab = tab;
            rebuildWidgets();
        }).bounds(x, y, w, 20).build());
    }
    private void addButtonRows(int x, int y, int maxW, int gap, UiButton... buttons) {
        if (buttons == null || buttons.length == 0) return;
        int cx = x;
        int cy = y;
        int rowH = 24;
        int right = x + Math.max(80, maxW);
        for (UiButton spec : buttons) {
            if (spec == null) continue;
            int bw = Math.max(54, Math.min(spec.preferredWidth, Math.max(54, maxW)));
            if (cx > x && cx + bw > right) {
                cx = x;
                cy += rowH;
            }
            if (cx + bw > right) bw = Math.max(54, right - cx);
            Button button = Button.builder(Component.literal(spec.label), b -> spec.action.run()).bounds(cx, cy, bw, 22).build();
            button.active = spec.active;
            addRenderableWidget(button);
            cx += bw + Math.max(0, gap);
        }
    }


    private void addPresetButtons(int formX, int presetY, int formW, boolean active) {
        int presetW = Math.max(92, Math.min(132, (formW - 8) / 3));
        for (int i = 0; i < TRADE_PRESETS.length; i++) {
            String[] pr = TRADE_PRESETS[i];
            int bx = formX + (i % 3) * (presetW + 4);
            int by = presetY + (i / 3) * 22;
            Button button = Button.builder(Component.literal("+ " + pr[1]), b -> presetTrader(pr[0], pr[2], pr[3]))
                    .bounds(bx, by, presetW, 20).build();
            button.active = active;
            addRenderableWidget(button);
        }
    }

    private EditBox box(int x, int y, int w, String title, String hint, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.literal(title));
        box.setHint(Component.literal(hint));
        box.setMaxLength(160);
        box.setValue(value);
        return box;
    }

    private void presetTrader(String key, String name, String skin) {
        if (keyBox != null && (selectedRow() == null || !selectedRow().system)) keyBox.setValue(key);
        if (presetBox != null && (selectedRow() == null || !selectedRow().system)) presetBox.setValue(key);
        if (nameBox != null) nameBox.setValue(name);
        setSkinValue(skin);
    }

    private void setSkinValue(String skin) {
        if (skinBox == null) return;
        String safe = skin == null || skin.isBlank() ? "guild_registrar" : skin.trim().toLowerCase(Locale.ROOT);
        if (!safe.equals(skinBox.getValue())) {
            skinBox.setValue(safe);
        }
        previewEntity = null;
        previewEntitySkin = "";
        previewStatus = "";
    }

    private void applyPresetToSelected() {
        String preset = presetBox == null ? (selectedRow() == null ? activeKey() : selectedRow().presetId) : clean(presetBox.getValue()).toLowerCase(Locale.ROOT);
        if (preset.isBlank()) preset = activeKey();
        send("npc_admin_apply_preset", activeKey(), "preset=" + clean(preset));
    }

    private void saveSelected() {
        String preset = presetBox == null ? "" : clean(presetBox.getValue()).toLowerCase(Locale.ROOT);
        send("npc_admin_save", activeKey(), "name=" + clean(nameBox.getValue()) + ";skin=" + clean(skinBox.getValue()) + ";preset=" + preset);
    }

    private void createTrader() {
        String key = keyBox == null ? "trader_basic" : clean(keyBox.getValue()).toLowerCase(Locale.ROOT);
        String name = nameBox == null ? "Гільдійний Торговець" : nameBox.getValue();
        String skin = skinBox == null ? "guild_registrar" : skinBox.getValue();
        String preset = presetBox == null ? key : presetBox.getValue();
        Minecraft.getInstance().setScreen(new CreateNpcScreen(this, key, name, skin, preset));
    }

    private void openTradeEditor(NpcRow row, int index) {
        if (row == null || row.system) return;
        String raw = index >= 0 && index < row.trades.size() ? row.trades.get(index) : "";
        Minecraft.getInstance().setScreen(new TradeEditScreen(this, row.key, index, raw));
    }

    private void removeTrade(NpcRow row, int index) {
        if (row == null || row.system || index < 0 || index >= row.trades.size()) return;
        send("npc_admin_trade_remove", row.key, String.valueOf(index));
    }

    private void duplicateTrade(NpcRow row, int index) {
        if (row == null || row.system || index < 0 || index >= row.trades.size()) return;
        send("npc_admin_trade_duplicate", row.key, String.valueOf(index));
    }

    private void moveTrade(NpcRow row, int index, int delta) {
        if (row == null || row.system || index < 0 || index >= row.trades.size()) return;
        send("npc_admin_trade_move", row.key, index + ";" + delta);
    }

    private void addTrade() {
        // Kept for compatibility with older callbacks. New UI uses TradeEditScreen.
        openTradeEditor(selectedRow(), -1);
    }

    private void removeLastTrade(NpcRow row) {
        if (row == null || row.trades.isEmpty()) return;
        removeTrade(row, row.trades.size() - 1);
    }

    private String activeKey() {
        NpcRow selected = selectedRow();
        if (selected != null && selected.system) return selected.key;
        String key = keyBox == null ? selectedKey : clean(keyBox.getValue()).toLowerCase(Locale.ROOT);
        return key.isBlank() ? selectedKey : key;
    }

    private void cycleSkin(int delta) {
        if (skinBox == null || skins.isEmpty()) return;
        String current = skinBox.getValue().trim().toLowerCase(Locale.ROOT);
        int idx = skins.indexOf(current);
        if (idx < 0) idx = 0;
        int next = Math.floorMod(idx + delta, skins.size());
        setSkinValue(skins.get(next));
    }

    private void send(String action, String a, String b) {
        ClientPacketDistributor.sendToServer(new GuildActionPayload(action, a == null ? "" : a, b == null ? "" : b));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        Layout l = layout();
        NpcRow selected = selectedRow();

        graphics.fill(l.x, l.y, l.x + l.w, l.y + l.h, 0xE0101522);
        graphics.fill(l.leftX - 6, l.y + 48, l.leftX + l.leftW + 6, l.bottomY, 0x66000000);
        graphics.fill(l.centerX - 6, l.y + 48, l.centerX + l.centerW + 6, l.bottomY, 0x44000000);
        graphics.fill(l.previewX - 6, l.y + 48, l.previewX + l.previewW + 6, l.bottomY, 0x66000000);
        graphics.fill(l.tradeX - 6, l.bottomY + 8, l.tradeX + l.tradeW + 6, l.y + l.h - 42, 0x66000000);

        graphics.drawString(this.font, "Редактор NPC — серверні дані", l.x + 12, l.y + 10, 0xFFFFFFFF, false);
        graphics.drawString(this.font, "NPC", l.leftX, l.y + 50, 0xFFFFD166, false);
        graphics.drawString(this.font, "Основні налаштування", l.centerX, l.y + 50, 0xFFFFD166, false);
        graphics.drawString(this.font, "3D preview", l.previewX, l.y + 50, 0xFFFFD166, false);
        String bottomTitle = "placed".equals(editorTab) ? "Розставлені NPC" : ("trades".equals(editorTab) ? "Товари" : "Готові списки / товари");
        graphics.drawString(this.font, bottomTitle, l.tradeX, l.bottomY + 14, 0xFFFFD166, false);

        int formY = l.y + 62;
        graphics.drawString(this.font, "Ключ", l.centerX, formY - 11, 0xFF9AA8BD, false);
        graphics.drawString(this.font, "Назва", l.centerX, formY + 25, 0xFF9AA8BD, false);
        graphics.drawString(this.font, "Скін", l.centerX, formY + 61, 0xFF9AA8BD, false);
        if ("skins".equals(editorTab)) {
            graphics.drawString(this.font, "Список скінів — вибір одразу міняє preview", l.centerX, formY + 96, 0xFF9AA8BD, false);
        } else if ("diagnostics".equals(editorTab)) {
            // Diagnostics tab has no widgets in rebuildWidgets(); labels are rendered in render().
        } else if ("trades".equals(editorTab)) {
            graphics.drawString(this.font, "Товари: готові пресети + редагування кожної позиції нижче", l.centerX, formY + 96, 0xFF9AA8BD, false);
        } else if ("placed".equals(editorTab)) {
            graphics.drawString(this.font, "Список розставлених NPC у server config + repair", l.centerX, formY + 96, 0xFF9AA8BD, false);
        } else {
            graphics.drawString(this.font, "Готові типи торговців", l.centerX, formY + 96, 0xFF9AA8BD, false);
        }

        int previewTop = l.y + 72;
        int availablePreview = Math.max(160, l.bottomY - previewTop - 82);
        int previewH = Math.max(160, Math.min(286, availablePreview));
        renderNpcPreview(graphics, l.previewX, previewTop, l.previewW, previewH);
        if (selected != null) {
            int infoY = previewTop + previewH + 8;
            int c = selected.system ? 0xFF9AE6B4 : 0xFFBFD7FF;
            drawClippedText(graphics, selected.key + " — " + selected.name, l.previewX, infoY, l.previewW, c); infoY += 12;
            drawClippedText(graphics, "Тип: " + selected.kind + " | " + (selected.enabled ? "у світі" : "вимкнений"), l.previewX, infoY, l.previewW, 0xFFB8C7DD); infoY += 12;
            if (!selected.system) { drawClippedText(graphics, "Preset: " + selected.presetId + (selected.customTrades ? " | custom" : " | synced"), l.previewX, infoY, l.previewW, 0xFFB8C7DD); infoY += 12; }
            drawClippedText(graphics, "Позиція: " + selected.pos, l.previewX, infoY, l.previewW, 0xFFB8C7DD); infoY += 12;
            if (previewStatus != null && !previewStatus.isBlank()) {
                drawClippedText(graphics, previewStatus, l.previewX, infoY, l.previewW, previewStatus.startsWith("Preview OK") ? 0xFF9AE6B4 : 0xFFFF7777);
            }
            if ("trades".equals(editorTab)) {
                renderTradeList(graphics, selected, l);
            } else if ("diagnostics".equals(editorTab)) {
                renderDiagnostics(graphics, selected, l);
            } else if ("placed".equals(editorTab)) {
                renderPlacedNpcList(graphics, l);
            } else {
                renderTradeHint(graphics, selected, l);
            }
        }

        // Widgets are rendered last so edit boxes and per-trade buttons are never covered by panel backgrounds/text.
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderNpcPreview(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xAA000000);
        String skin = skinBox == null ? "guild_registrar" : skinBox.getValue().trim().toLowerCase(Locale.ROOT);
        if (skin.isBlank()) skin = "guild_registrar";
        String key = keyBox == null ? activeKey() : clean(keyBox.getValue()).toLowerCase(Locale.ROOT);
        if (key.isBlank()) key = activeKey();

        boolean rendered = false;
        GuildNpcSkins.SkinProfile previewProfile = GuildNpcSkins.resolve(skin);
        boolean missingSkin = !skin.equalsIgnoreCase(previewProfile.id()) && !"default".equalsIgnoreCase(skin);
        try {
            GuildRegistrarEntity entity = previewEntity(key, skin);
            if (entity != null) {
                entity.setYRot(previewYaw);
                entity.setYBodyRot(previewYaw);
                entity.setYHeadRot(previewYaw);
                entity.yRotO = previewYaw;
                entity.yBodyRotO = previewYaw;
                entity.yHeadRotO = previewYaw;
                rendered = NpcPreviewRenderer.render(graphics, x, y, w, h, previewScale, previewYaw, entity);
            }
        } catch (Throwable ignored) {
            rendered = false;
        }

        if (!rendered) {
            previewStatus = "Preview fallback: 2D skin parts";
            graphics.drawString(this.font, "NPC preview", x + 12, y + 28, 0xFFFFD166, false);
            drawWrapped(graphics, "3D inventory path недоступний у цьому mapping, показано безпечний skin fallback.", x + 12, y + 46, w - 24, 0xFFFF7777);
        } else {
            previewStatus = missingSkin ? "Preview OK: fallback skin, missing skinId=" + skin : "Preview OK: skin=" + skin;
        }
        drawClippedText(graphics, "skinId: " + skin, x + 8, y + h - 14, w - 16, 0xFFB8C7DD);
    }

    private GuildRegistrarEntity previewEntity(String key, String skin) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) return null;
        String safeKey = key == null || key.isBlank() ? "preview" : key;
        String safeSkin = skin == null || skin.isBlank() ? "guild_registrar" : skin;
        if (previewEntity == null || !safeSkin.equals(previewEntitySkin) || !safeKey.equals(previewEntityKey)) {
            previewEntity = new GuildRegistrarEntity(HomeCraftGuildEntities.GUILD_REGISTRAR.get(), minecraft.level);
            NpcRow selected = selectedRow();
            String kind = selected != null ? selected.kind : (safeKey.startsWith("trader") ? "trader" : "system");
            boolean system = selected != null ? selected.system : !"trader".equalsIgnoreCase(kind);
            previewEntity.setupHomeCraftNpc(safeKey, kind, safeSkin, safeKey, system, false);
            previewEntitySkin = safeSkin;
            previewEntityKey = safeKey;
        }
        return previewEntity;
    }

    private void renderPlacedNpcList(GuiGraphics graphics, Layout l) {
        int listX = l.tradeX;
        int listY = l.bottomY + 30;
        int listW = l.tradeW;
        int listH = Math.max(72, l.y + l.h - 48 - listY);
        graphics.fill(listX, listY, listX + listW, listY + listH, 0x77000000);
        int headerY = listY + 8;
        graphics.drawString(this.font, "NPC", listX + 8, headerY, 0xFFFFD166, false);
        graphics.drawString(this.font, "Стан / позиція / скін", listX + 220, headerY, 0xFFFFD166, false);
        graphics.drawString(this.font, "Дії", listX + listW - 270, headerY, 0xFFFFD166, false);
        int rowH = 24;
        int visible = Math.max(1, (listH - 32) / rowH);
        int maxScroll = Math.max(0, rows.size() - visible);
        tradeScroll = Math.max(0, Math.min(tradeScroll, maxScroll));
        for (int i = 0; i < visible && tradeScroll + i < rows.size(); i++) {
            NpcRow row = rows.get(tradeScroll + i);
            int yy = listY + 34 + i * rowH;
            graphics.fill(listX + 4, yy - 5, listX + listW - 4, yy + rowH - 5, row.enabled ? 0x33204030 : 0x33202020);
            drawClippedText(graphics, row.key + " — " + row.name, listX + 8, yy, 200, row.enabled ? 0xFF9AE6B4 : 0xFF9AA8BD);
            drawClippedText(graphics, (row.enabled ? "placed" : "off") + " · " + row.kind + " · " + row.skin + " · " + row.pos, listX + 220, yy, listW - 510, 0xFFB8C7DD);
        }
        if (rows.isEmpty()) {
            graphics.drawString(this.font, "NPC у конфігу не знайдені.", listX + 8, listY + 34, 0xFF9AA8BD, false);
        }
    }

    private void renderTradeList(GuiGraphics graphics, NpcRow selected, Layout l) {
        if (selected == null) return;
        int listX = l.tradeX;
        int listY = tradeListY(l, selected);
        int listW = l.tradeW;
        int listH = tradeListH(l, listY);
        graphics.fill(listX, listY, listX + listW, listY + listH, 0x99000000);
        if (selected.system) {
            drawWrapped(graphics, "Це системний NPC. Торгівля вимкнена. Можна змінювати тільки назву і skinId.", listX + 8, listY + 10, listW - 16, 0xFFFFD166);
            return;
        }
        int headerY = listY + 6;
        graphics.drawString(this.font, "#", listX + 8, headerY, 0xFFFFD166, false);
        graphics.drawString(this.font, "Товар", listX + 38, headerY, 0xFFFFD166, false);
        graphics.drawString(this.font, "Дії", listX + listW - 166, headerY, 0xFFFFD166, false);
        graphics.drawString(this.font, "Поточних товарів: " + selected.trades.size(), listX + listW - 280, headerY, 0xFF9AA8BD, false);
        int rowY = listY + 26;
        int rowH = 22;
        int visible = Math.max(1, (listH - 28) / rowH);
        int maxScroll = Math.max(0, selected.trades.size() - visible);
        tradeScroll = Math.max(0, Math.min(tradeScroll, maxScroll));
        for (int i = 0; i < visible && tradeScroll + i < selected.trades.size(); i++) {
            int idx = tradeScroll + i;
            int yy = rowY + i * rowH;
            int bg = (idx % 2 == 0) ? 0x33000000 : 0x22000000;
            graphics.fill(listX + 4, yy - 4, listX + listW - 4, yy + rowH - 4, bg);
            graphics.drawString(this.font, String.valueOf(idx), listX + 8, yy, 0xFFBFD7FF, false);
            drawClippedText(graphics, humanTrade(selected.trades.get(idx)), listX + 38, yy, listW - 250, 0xFFBFD7FF);
        }
        if (selected.trades.isEmpty()) {
            graphics.drawString(this.font, "Товарів ще немає. Натисни готовий пресет або додай товар через окреме вікно.", listX + 8, rowY, 0xFF9AA8BD, false);
        }
    }

    private void renderDiagnostics(GuiGraphics graphics, NpcRow selected, Layout l) {
        int listX = l.tradeX;
        int listY = l.bottomY + 30;
        int listW = l.tradeW;
        int listH = Math.max(72, l.y + l.h - 48 - listY);
        graphics.fill(listX, listY, listX + listW, listY + listH, 0x77000000);
        int y = listY + 10;
        drawClippedText(graphics, "snapshot revision/cache: " + revisionFromSnapshot(snapshot), listX + 10, y, listW - 20, 0xFFB8C7DD); y += 12;
        drawClippedText(graphics, "npcKey=" + selected.key + " skin=" + selected.skin + " preset=" + selected.presetId, listX + 10, y, listW - 20, 0xFFB8C7DD); y += 12;
        drawClippedText(graphics, "trades=" + selected.trades.size() + " customTrades=" + selected.customTrades + " tradesHash=" + selected.tradesHash, listX + 10, y, listW - 20, 0xFFB8C7DD); y += 12;
        drawWrapped(graphics, "Діагностика не малює кнопки інших вкладок. Якщо після save екран скаче — це regression у UiState.", listX + 10, y + 4, listW - 20, 0xFFFFD166);
    }

    private String revisionFromSnapshot(String raw) {
        if (raw == null) return "0";
        for (String line : raw.split("\n")) if (line.startsWith("revision=")) return line.substring("revision=".length()).trim();
        return "0";
    }

    private void renderTradeHint(GuiGraphics graphics, NpcRow selected, Layout l) {
        int listX = l.tradeX;
        int listY = l.bottomY + 30;
        int listW = l.tradeW;
        int listH = Math.max(36, l.y + l.h - 48 - listY);
        graphics.fill(listX, listY, listX + listW, listY + listH, 0x77000000);
        String msg = selected.system
                ? "Системний NPC: можна змінювати тільки назву та skinId. Торгівля вимкнена."
                : "Для редагування товарів відкрий вкладку «Товари». Там кожен товар має свою кнопку редагування та видалення.";
        drawWrapped(graphics, msg, listX + 10, listY + 12, listW - 20, selected.system ? 0xFFFFD166 : 0xFFB8C7DD);
    }

    private int tradeListY(Layout l, NpcRow selected) {
        return l.bottomY + (selected != null && !selected.system ? 58 : 30);
    }

    private int tradeListH(Layout l, int listY) {
        return Math.max(48, l.y + l.h - 48 - listY);
    }

    private void drawClippedText(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        String value = text == null ? "" : text;
        while (this.font.width(value) > maxWidth && value.length() > 4) value = value.substring(0, value.length() - 2);
        if (!value.equals(text)) value = value + "…";
        graphics.drawString(this.font, value, x, y, color, false);
    }

    private int countPrimitive(Class<?>[] types, Class<?> primitive, Class<?> boxed) {
        int count = 0;
        for (Class<?> type : types) if (type == primitive || type == boxed) count++;
        return count;
    }

    private int drawWrapped(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        int lines = 0;
        for (FormattedCharSequence seq : this.font.split(Component.literal(text == null ? "" : text), maxWidth)) {
            graphics.drawString(this.font, seq, x, y + lines * 9, color, false);
            lines++;
        }
        return Math.max(1, lines) * 9;
    }

    private String humanTrade(String raw) {
        return raw.replace(",", " · ")
                .replace("buy=", "")
                .replace("sell=", "→ ")
                .replace("buyCount=", "x")
                .replace("sellCount=", "x")
                .replace("max=", "max ")
                .replace("xp=", "xp ")
                .replace("role=", "role ")
                .replace("limit=", "limit ")
                .replace("reset=", "reset ")
                .replace("ench=", "ench ")
                .replace("name=", "name ");
    }

    private String clean(String value) {
        if (value == null) return "";
        return value.trim().replace('|', ' ').replace(';', ' ').replace('~', ' ');
    }

    private NpcRow selectedRow() {
        for (NpcRow row : rows) if (row.key.equals(selectedKey)) return row;
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<NpcRow> filteredRows() {
        List<NpcRow> out = new ArrayList<>();
        for (NpcRow row : rows) {
            if ("system".equals(filter) && !row.system) continue;
            if ("trader".equals(filter) && row.system) continue;
            out.add(row);
        }
        return out;
    }

    private void parseSnapshot(String raw) {
        int schema = snapshotSchemaVersion(raw);
        for (String line : raw.split("\n")) {
            if (line == null || line.isBlank()) continue;
            if (line.startsWith("skins=")) {
                skins.clear();
                for (String part : line.substring("skins=".length()).split(",")) {
                    String skin = part.trim().toLowerCase(Locale.ROOT);
                    if (!skin.isBlank() && !skins.contains(skin)) skins.add(skin);
                }
                continue;
            }
            if (!line.startsWith("npc|")) continue;
            String[] p = line.split("\\|", -1);
            if (p.length < 12) continue;
            List<String> trades = new ArrayList<>();
            String rawTrades = p.length >= 13 ? (schema >= 3 ? decodeSnapshotField(p[12]) : p[12]) : "";
            if (!rawTrades.isBlank()) {
                for (String trade : rawTrades.split("~")) if (!trade.isBlank()) trades.add(trade);
            }
            String name = p.length > 11 ? (schema >= 3 ? decodeSnapshotField(p[11]) : p[11]) : p[1];
            String preset = p.length > 13 ? p[13] : ("trader".equalsIgnoreCase(p[3]) ? p[1] : "");
            boolean customTrades = p.length > 14 && "1".equals(p[14]);
            String tradesHash = p.length > 15 ? p[15] : "0";
            rows.add(new NpcRow(p[1], "1".equals(p[2]), p[3], "1".equals(p[4]), p[5], p[7] + ", " + p[8] + ", " + p[9], name, trades, preset, customTrades, tradesHash));
        }
    }

    private static int snapshotSchemaVersion(String raw) {
        if (raw == null) return 1;
        for (String line : raw.split("\n")) {
            if (line != null && line.startsWith("schemaVersion=")) {
                try { return Integer.parseInt(line.substring("schemaVersion=".length()).trim()); } catch (Exception ignored) { return 1; }
            }
        }
        return 1;
    }

    private static String decodeSnapshotField(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return encoded;
        }
    }

    private Layout layout() {
        Layout l = new Layout();
        l.w = Math.min(1440, Math.max(760, this.width - 24));
        l.h = Math.min(820, Math.max(500, this.height - 24));
        l.x = (this.width - l.w) / 2;
        l.y = (this.height - l.h) / 2;
        l.leftW = Math.min(300, Math.max(200, l.w / 5));
        l.previewW = Math.min(320, Math.max(240, l.w / 4));
        l.leftX = l.x + 14;
        l.previewX = l.x + l.w - l.previewW - 18;
        l.centerX = l.leftX + l.leftW + 18;
        l.centerW = Math.max(340, l.previewX - l.centerX - 18);
        l.bottomY = l.y + Math.max(330, l.h - 250);
        l.tradeX = l.leftX;
        l.tradeW = l.w - 32;
        l.tradeEditorY = l.bottomY + 34;
        return l;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout l = layout();
        if (mouseX >= l.leftX && mouseX <= l.leftX + l.leftW && mouseY >= l.y + 56 && mouseY <= l.bottomY) {
            npcScroll = Math.max(0, npcScroll + (scrollY < 0 ? 1 : -1));
            rebuildWidgets();
            return true;
        }
        if ("skins".equals(editorTab) && mouseX >= l.centerX && mouseX <= l.centerX + l.centerW && mouseY >= l.y + 150 && mouseY <= l.bottomY) {
            skinScroll = Math.max(0, skinScroll + (scrollY < 0 ? 3 : -3));
            rebuildWidgets();
            return true;
        }
        if (mouseY >= l.bottomY) {
            tradeScroll = Math.max(0, tradeScroll + (scrollY < 0 ? 1 : -1));
            rebuildWidgets();
            return true;
        }
        if (mouseX >= l.previewX && mouseX <= l.previewX + l.previewW) {
            previewScale = Math.max(34, Math.min(86, previewScale + (scrollY > 0 ? 3 : -3)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = mouseEventDouble(event, this.lastMouseX, "x", "mouseX");
        double mouseY = mouseEventDouble(event, this.lastMouseY, "y", "mouseY");
        int button = mouseEventButton(event);
        Layout l = layout();
        if (button == 0 && mouseX >= l.previewX && mouseX <= l.previewX + l.previewW && mouseY >= l.y + 64 && mouseY <= l.bottomY) {
            previewYaw = (float) (previewYaw + dragX * 1.6D);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    private double mouseEventDouble(Object event, double fallback, String... names) {
        if (event == null) return fallback;
        for (String name : names) {
            try {
                Object value = event.getClass().getMethod(name).invoke(event);
                if (value instanceof Number n) return n.doubleValue();
            } catch (Throwable ignored) { }
            try {
                java.lang.reflect.Field field = event.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(event);
                if (value instanceof Number n) return n.doubleValue();
            } catch (Throwable ignored) { }
        }
        return fallback;
    }

    private int mouseEventButton(Object event) {
        Object input = mouseEventValue(event, "input", "buttonInfo", "mouseInput");
        Object source = input != null ? input : event;
        Object button = mouseEventValue(source, "button", "buttonCode");
        if (button instanceof Number n) return n.intValue();
        return 0;
    }

    private Object mouseEventValue(Object event, String... names) {
        if (event == null) return null;
        for (String name : names) {
            try { return event.getClass().getMethod(name).invoke(event); } catch (Throwable ignored) { }
            try {
                java.lang.reflect.Field field = event.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(event);
            } catch (Throwable ignored) { }
        }
        return null;
    }


    private UiState captureUiState() {
        UiState state = new UiState();
        state.selectedKey = selectedKey;
        state.selectedTab = editorTab;
        state.npcScroll = npcScroll;
        state.tradeScroll = tradeScroll;
        state.skinScroll = skinScroll;
        state.previewYaw = previewYaw;
        state.previewScale = previewScale;
        rememberBox(state, "key", keyBox);
        rememberBox(state, "name", nameBox);
        rememberBox(state, "skin", skinBox);
        rememberBox(state, "preset", presetBox);
        return state;
    }

    private void restoreUiStateFields(UiState state) {
        if (state == null) return;
        editorTab = state.selectedTab == null || state.selectedTab.isBlank() ? editorTab : state.selectedTab;
        npcScroll = state.npcScroll;
        tradeScroll = state.tradeScroll;
        skinScroll = state.skinScroll;
        previewYaw = state.previewYaw;
        previewScale = state.previewScale;
    }

    private void restoreUiStateWidgets(UiState state) {
        if (state == null) return;
        restoreBox(state, "key", keyBox);
        restoreBox(state, "name", nameBox);
        restoreBox(state, "skin", skinBox);
        restoreBox(state, "preset", presetBox);
    }

    private void rememberBox(UiState state, String id, EditBox box) {
        if (state == null || id == null || box == null) return;
        state.values.put(id, box.getValue());
        if (box.isFocused()) {
            state.focusedFieldId = id;
            state.caretPosition = reflectInt(box, "getCursorPosition", box.getValue().length());
        }
    }

    private void restoreBox(UiState state, String id, EditBox box) {
        if (state == null || id == null || box == null) return;
        if (state.values.containsKey(id) && (id.equals(state.focusedFieldId) || "key".equals(id) || "preset".equals(id) || "skin".equals(id) || "name".equals(id))) {
            box.setValue(state.values.get(id));
        }
        if (id.equals(state.focusedFieldId)) {
            box.setFocused(true);
            reflectMoveCursor(box, Math.max(0, Math.min(state.caretPosition, box.getValue().length())));
        }
    }

    private int reflectInt(Object target, String method, int fallback) {
        try { Object value = target.getClass().getMethod(method).invoke(target); if (value instanceof Number n) return n.intValue(); } catch (Throwable ignored) {}
        return fallback;
    }

    private void reflectMoveCursor(Object target, int pos) {
        for (String name : new String[]{"moveCursorTo", "setCursorPosition"}) {
            try { target.getClass().getMethod(name, int.class).invoke(target, pos); return; } catch (Throwable ignored) {}
        }
    }

    private static String shortenLabel(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    public static final class CreateNpcScreen extends Screen {
        private final GuildNpcAdminScreen parent;
        private final String initialKey;
        private final String initialName;
        private final String initialSkin;
        private final String initialPreset;
        private EditBox keyBox;
        private EditBox nameBox;
        private EditBox skinBox;
        private EditBox presetBox;
        private String validationMessage = "";

        CreateNpcScreen(GuildNpcAdminScreen parent, String key, String name, String skin, String preset) {
            super(Component.literal("Створення NPC"));
            this.parent = parent;
            this.initialKey = key == null || key.isBlank() ? "trader_basic" : key;
            this.initialName = name == null || name.isBlank() ? "Гільдійний Торговець" : name;
            this.initialSkin = skin == null || skin.isBlank() ? "guild_registrar" : skin;
            this.initialPreset = preset == null || preset.isBlank() ? this.initialKey : preset;
        }

        @Override
        protected void init() {
            clearWidgets();
            int w = Math.min(560, Math.max(420, this.width - 40));
            int h = 244;
            int x = (this.width - w) / 2;
            int y = (this.height - h) / 2;
            int inner = w - 24;
            keyBox = edit(x + 12, y + 42, inner, "npcKey", "trader_weapons", initialKey);
            nameBox = edit(x + 12, y + 78, inner, "displayName", "Гільдійний Торговець", initialName);
            skinBox = edit(x + 12, y + 114, inner, "skinId", "guild_registrar", initialSkin);
            presetBox = edit(x + 12, y + 150, inner, "traderPresetId", "trader_basic", initialPreset);
            addRenderableWidget(keyBox); addRenderableWidget(nameBox); addRenderableWidget(skinBox); addRenderableWidget(presetBox);
            addRenderableWidget(Button.builder(Component.literal("Перевірити"), b -> check()).bounds(x + w - 328, y + h - 34, 100, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Створити"), b -> create()).bounds(x + w - 220, y + h - 34, 100, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Скасувати"), b -> Minecraft.getInstance().setScreen(parent)).bounds(x + w - 112, y + h - 34, 100, 22).build());
        }

        private EditBox edit(int x, int y, int w, String title, String hint, String value) {
            EditBox box = new EditBox(this.font, x, y, w, 20, Component.literal(title));
            box.setHint(Component.literal(hint));
            box.setMaxLength(80);
            box.setValue(value == null ? "" : value);
            return box;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int w = Math.min(560, Math.max(420, this.width - 40));
            int h = 244;
            int x = (this.width - w) / 2;
            int y = (this.height - h) / 2;
            graphics.fill(0, 0, this.width, this.height, 0x99000000);
            graphics.fill(x, y, x + w, y + h, 0xEE101522);
            graphics.drawString(this.font, "Створення торгового NPC у позиції гравця", x + 12, y + 12, 0xFFFFFFFF, false);
            graphics.drawString(this.font, "npcKey", x + 12, y + 31, 0xFFFFD166, false);
            graphics.drawString(this.font, "displayName", x + 12, y + 67, 0xFFFFD166, false);
            graphics.drawString(this.font, "skinId", x + 12, y + 103, 0xFFFFD166, false);
            graphics.drawString(this.font, "traderPresetId", x + 12, y + 139, 0xFFFFD166, false);
            if (validationMessage != null && !validationMessage.isBlank()) graphics.drawString(this.font, validationMessage, x + 12, y + h - 52, validationMessage.startsWith("OK") ? 0xFF9AE6B4 : 0xFFFF7777, false);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        private void check() {
            validationMessage = "Перевірка на сервері...";
            ClientPacketDistributor.sendToServer(new GuildActionPayload("npc_admin_create_check", cleanStatic(keyBox.getValue()), buildParams()));
        }

        private void create() {
            validationMessage = "Створення через сервер...";
            ClientPacketDistributor.sendToServer(new GuildActionPayload("npc_admin_create_trader", cleanStatic(keyBox.getValue()), buildParams()));
        }

        private String buildParams() {
            return "name=" + cleanStatic(nameBox.getValue()) + ";skin=" + cleanStatic(skinBox.getValue()) + ";preset=" + cleanStatic(presetBox.getValue());
        }

        public void acceptServerSnapshot(String snapshot) {
            CreateResult result = CreateResult.from(snapshot);
            if (result == null) {
                parent.acceptServerSnapshot(snapshot);
                return;
            }
            if (!result.success) {
                validationMessage = result.message.isBlank() ? "Помилка створення" : result.message;
                return;
            }
            parent.selectedKey = result.normalizedKey.isBlank() ? cleanStatic(keyBox.getValue()) : result.normalizedKey;
            parent.editorTab = "main";
            parent.acceptServerSnapshot(snapshot);
            if ("NPC створено".equalsIgnoreCase(result.message)) Minecraft.getInstance().setScreen(parent);
            else validationMessage = "OK: " + (result.message.isBlank() ? "ключ валідний" : result.message);
        }

        private record CreateResult(String npcKey, boolean success, String message, String normalizedKey) {
            static CreateResult from(String snapshot) {
                if (snapshot == null) return null;
                for (String line : snapshot.split("\\n")) {
                    if (!line.startsWith("result|npc_create|")) continue;
                    String[] p = line.split("\\|", -1);
                    if (p.length < 6) continue;
                    return new CreateResult(p[2], "success".equalsIgnoreCase(p[3]), decodeSnapshotField(p[4]), p[5]);
                }
                return null;
            }
        }

        private static String cleanStatic(String value) {
            if (value == null) return "";
            return value.trim().replace('|', ' ').replace(';', ' ').replace('~', ' ');
        }
    }

    public static final class TradeEditScreen extends Screen {
        private final GuildNpcAdminScreen parent;
        private final String npcKey;
        private final int index;
        private final String raw;
        private EditBox buyItemBox;
        private EditBox buyCountBox;
        private EditBox buy2ItemBox;
        private EditBox buy2CountBox;
        private EditBox sellItemBox;
        private EditBox sellCountBox;
        private EditBox maxUsesBox;
        private EditBox xpBox;
        private EditBox roleBox;
        private EditBox limitBox;
        private EditBox resetBox;
        private EditBox damageBox;
        private EditBox tradeNameBox;
        private EditBox enchantBox;
        private String validationMessage = "";
        private int lastRequestedIndex;
        private boolean pendingSave;

        TradeEditScreen(GuildNpcAdminScreen parent, String npcKey, int index, String raw) {
            super(Component.literal(index >= 0 ? "Редагування товару" : "Новий товар"));
            this.parent = parent;
            this.npcKey = npcKey == null || npcKey.isBlank() ? "trader_basic" : npcKey;
            this.index = index;
            this.raw = raw == null ? "" : raw;
        }

        @Override
        protected void init() {
            clearWidgets();
            Map<String, String> values = parseTrade(raw);
            int w = dialogWidth();
            int h = dialogHeight();
            int x = (this.width - w) / 2;
            int y = (this.height - h) / 2;
            int inner = w - 24;
            int countW = 56;
            int itemW = Math.max(160, inner - countW - 6);
            int yy = y + 42;
            buyItemBox = edit(x + 12, yy, itemW, "buy", "minecraft:emerald", values.getOrDefault("buy", "minecraft:emerald"));
            buyCountBox = edit(x + 18 + itemW, yy, countW, "buyCount", "1", values.getOrDefault("buycount", "1")); yy += 36;
            buy2ItemBox = edit(x + 12, yy, itemW, "buy2", "optional second item", values.getOrDefault("buy2", values.getOrDefault("secondbuy", "")));
            buy2CountBox = edit(x + 18 + itemW, yy, countW, "buy2Count", "1", values.getOrDefault("buy2count", values.getOrDefault("secondbuycount", "1"))); yy += 36;
            sellItemBox = edit(x + 12, yy, itemW, "sell", "minecraft:iron_sword", values.getOrDefault("sell", "minecraft:bread"));
            sellCountBox = edit(x + 18 + itemW, yy, countW, "sellCount", "1", values.getOrDefault("sellcount", "1")); yy += 36;
            int smallGap = 6;
            int smallW = Math.max(48, (inner - smallGap * 5) / 6);
            maxUsesBox = edit(x + 12, yy, smallW, "max", "16", values.getOrDefault("max", values.getOrDefault("maxuses", "16")));
            xpBox = edit(x + 12 + (smallW + smallGap), yy, smallW, "xp", "0", values.getOrDefault("xp", "0"));
            roleBox = edit(x + 12 + (smallW + smallGap) * 2, yy, smallW, "role", "any/member", values.getOrDefault("role", "member"));
            limitBox = edit(x + 12 + (smallW + smallGap) * 3, yy, smallW, "limit", "0", values.getOrDefault("limit", values.getOrDefault("perplayer", "0")));
            resetBox = edit(x + 12 + (smallW + smallGap) * 4, yy, smallW, "reset", "never", values.getOrDefault("reset", values.getOrDefault("resetpolicy", "never")));
            damageBox = edit(x + 12 + (smallW + smallGap) * 5, yy, smallW, "+урон", "0/2", values.getOrDefault("damage", values.getOrDefault("extradamage", values.getOrDefault("bonusdamage", "0")))); yy += 46;
            tradeNameBox = edit(x + 12, yy, inner, "name", "Назва товару", values.getOrDefault("name", "")); yy += 36;
            enchantBox = edit(x + 12, yy, inner, "ench", "sharpness:5+unbreaking:3", values.getOrDefault("ench", values.getOrDefault("enchant", "")));
            addRenderableWidget(buyItemBox); addRenderableWidget(buyCountBox); addRenderableWidget(buy2ItemBox); addRenderableWidget(buy2CountBox); addRenderableWidget(sellItemBox); addRenderableWidget(sellCountBox);
            addRenderableWidget(maxUsesBox); addRenderableWidget(xpBox); addRenderableWidget(roleBox); addRenderableWidget(limitBox); addRenderableWidget(resetBox); addRenderableWidget(damageBox); addRenderableWidget(tradeNameBox); addRenderableWidget(enchantBox);
            int buttonY = y + h - 34;
            addRenderableWidget(Button.builder(Component.literal("Перевірити"), b -> checkTrade()).bounds(x + w - 328, buttonY, 100, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Зберегти"), b -> saveTrade()).bounds(x + w - 220, buttonY, 100, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Скасувати"), b -> Minecraft.getInstance().setScreen(parent)).bounds(x + w - 112, buttonY, 100, 22).build());
        }

        private int dialogWidth() {
            return Math.min(720, Math.max(420, this.width - 40));
        }

        private int dialogHeight() {
            return Math.min(360, Math.max(342, this.height - 28));
        }

        private EditBox edit(int x, int y, int w, String title, String hint, String value) {
            EditBox box = new EditBox(this.font, x, y, w, 20, Component.literal(title));
            box.setHint(Component.literal(hint));
            box.setMaxLength(180);
            box.setValue(value == null ? "" : value);
            return box;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int w = dialogWidth();
            int h = dialogHeight();
            int x = (this.width - w) / 2;
            int y = (this.height - h) / 2;
            graphics.fill(0, 0, this.width, this.height, 0x99000000);
            graphics.fill(x, y, x + w, y + h, 0xEE101522);
            graphics.drawString(this.font, (index >= 0 ? "Редагування товару #" + index : "Новий товар") + " для " + npcKey, x + 12, y + 12, 0xFFFFFFFF, false);
            graphics.drawString(this.font, "Купує", x + 12, y + 31, 0xFFFFD166, false);
            graphics.drawString(this.font, "x", x + 18 + (w - 36) / 2, y + 31, 0xFFFFD166, false);
            graphics.drawString(this.font, "Друга ціна (опційно)", x + 12, y + 67, 0xFFFFD166, false);
            graphics.drawString(this.font, "x", x + 18 + (w - 36) / 2, y + 67, 0xFFFFD166, false);
            graphics.drawString(this.font, "Продає", x + 12, y + 103, 0xFFFFD166, false);
            graphics.drawString(this.font, "x", x + 18 + (w - 36) / 2, y + 103, 0xFFFFD166, false);
            graphics.drawString(this.font, "max / xp / роль / ліміт / reset / +урон", x + 12, y + 139, 0xFFFFD166, false);
            graphics.drawString(this.font, "Назва товару", x + 12, y + 185, 0xFFFFD166, false);
            graphics.drawString(this.font, "Зачарування", x + 12, y + 221, 0xFFFFD166, false);
            if (validationMessage != null && !validationMessage.isBlank()) graphics.drawString(this.font, validationMessage, x + 12, y + h - 52, validationMessage.startsWith("OK") ? 0xFF9AE6B4 : 0xFFFF7777, false);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        private void saveTrade() {
            sendTrade(true);
        }

        private void checkTrade() {
            sendTrade(false);
        }

        private void sendTrade(boolean save) {
            String spec = buildSpec();
            pendingSave = save;
            lastRequestedIndex = index;
            validationMessage = save ? "Перевірка на сервері перед збереженням..." : "Перевірка на сервері...";
            if (save && index >= 0) {
                ClientPacketDistributor.sendToServer(new GuildActionPayload("npc_admin_trade_set", npcKey, index + ";" + spec));
            } else if (save) {
                ClientPacketDistributor.sendToServer(new GuildActionPayload("npc_admin_trade_add", npcKey, spec));
            } else {
                ClientPacketDistributor.sendToServer(new GuildActionPayload("npc_admin_trade_check", npcKey, spec));
            }
        }

        private String buildSpec() {
            return "buy=" + cleanStatic(buyItemBox.getValue())
                    + ";buyCount=" + cleanStatic(buyCountBox.getValue())
                    + ";buy2=" + cleanStatic(buy2ItemBox.getValue())
                    + ";buy2Count=" + cleanStatic(buy2CountBox.getValue())
                    + ";sell=" + cleanStatic(sellItemBox.getValue())
                    + ";sellCount=" + cleanStatic(sellCountBox.getValue())
                    + ";max=" + cleanStatic(maxUsesBox.getValue())
                    + ";xp=" + cleanStatic(xpBox.getValue())
                    + ";role=" + cleanStatic(roleBox.getValue())
                    + ";limit=" + cleanStatic(limitBox.getValue())
                    + ";reset=" + cleanStatic(resetBox.getValue())
                    + ";damage=" + cleanStatic(damageBox.getValue())
                    + ";name=" + cleanStatic(tradeNameBox.getValue())
                    + ";ench=" + cleanStatic(enchantBox.getValue());
        }

        public void acceptServerSnapshot(String snapshot) {
            TradeValidationResult result = TradeValidationResult.from(snapshot);
            if (result == null || !npcKey.equals(result.npcKey)) {
                parent.acceptServerSnapshot(snapshot);
                return;
            }
            if (!result.success) {
                validationMessage = result.message.isBlank() ? "Помилка валідації" : result.message;
                return;
            }
            if ("check".equals(result.mode)) {
                validationMessage = "OK: " + (result.message.isBlank() ? "товар валідний" : result.message);
                applyNormalizedSpec(result.normalized);
                return;
            }
            parent.editorTab = "trades";
            parent.acceptServerSnapshot(snapshot);
            Minecraft.getInstance().setScreen(parent);
        }

        private void applyNormalizedSpec(String spec) {
            Map<String, String> values = parseTrade(spec);
            if (values.isEmpty()) return;
            buyItemBox.setValue(values.getOrDefault("buy", buyItemBox.getValue()));
            buyCountBox.setValue(values.getOrDefault("buycount", buyCountBox.getValue()));
            buy2ItemBox.setValue(values.getOrDefault("buy2", buy2ItemBox.getValue()));
            buy2CountBox.setValue(values.getOrDefault("buy2count", buy2CountBox.getValue()));
            sellItemBox.setValue(values.getOrDefault("sell", sellItemBox.getValue()));
            sellCountBox.setValue(values.getOrDefault("sellcount", sellCountBox.getValue()));
            maxUsesBox.setValue(values.getOrDefault("max", maxUsesBox.getValue()));
            xpBox.setValue(values.getOrDefault("xp", xpBox.getValue()));
            roleBox.setValue(values.getOrDefault("role", roleBox.getValue()));
            limitBox.setValue(values.getOrDefault("limit", limitBox.getValue()));
            resetBox.setValue(values.getOrDefault("reset", resetBox.getValue()));
            damageBox.setValue(values.getOrDefault("damage", values.getOrDefault("extradamage", values.getOrDefault("bonusdamage", damageBox.getValue()))));
            tradeNameBox.setValue(values.getOrDefault("name", tradeNameBox.getValue()));
            enchantBox.setValue(values.getOrDefault("ench", enchantBox.getValue()));
        }

        private record TradeValidationResult(String npcKey, int index, boolean success, String mode, String message, String normalized) {
            static TradeValidationResult from(String snapshot) {
                if (snapshot == null) return null;
                for (String line : snapshot.split("\n")) {
                    if (!line.startsWith("result|trade_validation|")) continue;
                    String[] p = line.split("\\|", -1);
                    if (p.length < 8) continue;
                    int idx = -1;
                    try { idx = Integer.parseInt(p[3]); } catch (Exception ignored) {}
                    return new TradeValidationResult(p[2], idx, "success".equalsIgnoreCase(p[4]), p[5], decodeSnapshotField(p[6]), decodeSnapshotField(p[7]));
                }
                return null;
            }
        }

        private static Map<String, String> parseTrade(String raw) {
            Map<String, String> out = new LinkedHashMap<>();
            if (raw == null || raw.isBlank()) return out;
            for (String part : raw.replace(';', ',').split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                out.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1).trim());
            }
            return out;
        }

        private static String cleanStatic(String value) {
            if (value == null) return "";
            return value.trim().replace('|', ' ').replace(';', ' ').replace('~', ' ');
        }
    }

    private static final class UiState {
        String selectedKey = "";
        String selectedTab = "main";
        int npcScroll;
        int tradeScroll;
        int skinScroll;
        String focusedFieldId = "";
        int caretPosition;
        float previewYaw = 180.0F;
        int previewScale = 66;
        final Map<String, String> values = new LinkedHashMap<>();
    }

    private static final class Layout {
        int x, y, w, h;
        int leftX, leftW;
        int centerX, centerW;
        int previewX, previewW;
        int bottomY;
        int tradeX, tradeW, tradeEditorY;
    }

    private record UiButton(String label, int preferredWidth, boolean active, Runnable action) {}

    private record NpcRow(String key, boolean enabled, String kind, boolean system, String skin, String pos, String name, List<String> trades, String presetId, boolean customTrades, String tradesHash) {}
}
