package ua.homecraft.guild.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import ua.homecraft.guild.network.GuildActionPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CreateGuildScreen extends Screen {
    private static final String[] COLOR_IDS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "cyan", "purple", "blue", "red"};

    private final NpcView view;
    private final List<ColorButtonOverlay> colorButtons = new ArrayList<>();
    private EditBox nameBox;
    private String selectedColor = "magenta";
    private List<String> helpLines = buildHelpLines();
    private String liveLanguageStamp = HomeCraftGuildI18n.languageStamp();
    private boolean helpOpen;
    private int helpScroll;
    private int infoX, infoY, infoS;
    private int helpCloseX, helpCloseY, helpCloseW, helpCloseH;
    private int helpPanelX, helpPanelY, helpPanelW, helpPanelH;
    private int helpInnerX, helpInnerY, helpInnerW, helpInnerH;

    public CreateGuildScreen(String message) {
        super(HomeCraftGuildI18n.c("screen.homecraftguild.create.title"));
        this.view = NpcView.parse(message == null ? "" : message);
    }

    @Override
    protected void init() {
        colorButtons.clear();
        helpScroll = 0;
        Layout l = layout();
        infoS = 18;
        infoX = l.x + l.panelW - l.pad - infoS;
        infoY = l.y + 10;
        addRenderableWidget(Button.builder(Component.literal("i"), b -> { helpOpen = !helpOpen; if (helpOpen) helpScroll = 0; }).bounds(infoX, infoY, infoS, infoS).build());

        if (view.mode.equals("CREATE")) {
            this.nameBox = new EditBox(this.font, l.x + l.pad, l.y + 90, l.contentW, 22, HomeCraftGuildI18n.c("field.homecraftguild.guild_name"));
            this.nameBox.setHint(HomeCraftGuildI18n.c("field.homecraftguild.guild_name"));
            this.nameBox.setMaxLength(32);
            addRenderableWidget(nameBox);

            int columns = l.contentW < 240 ? 3 : 5;
            int colorButtonW = Math.max(42, (l.contentW - (columns - 1) * 4) / columns);
            for (int i = 0; i < COLOR_IDS.length; i++) {
                final String color = COLOR_IDS[i];
                int cx = l.x + l.pad + (i % columns) * (colorButtonW + 4);
                int cy = l.y + 130 + (i / columns) * 24;
                colorButtons.add(new ColorButtonOverlay(cx, cy, colorButtonW, 20, color, colorLabel(color)));
                addRenderableWidget(Button.builder(Component.literal(colorLabel(color)), b -> selectedColor = color)
                        .bounds(cx, cy, colorButtonW, 20).build());
            }
            addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("button.homecraftguild.create_for_emeralds", view.cost), b -> {
                ClientPacketDistributor.sendToServer(new GuildActionPayload("create", nameBox.getValue(), selectedColor));
                onClose();
            }).bounds(l.x + l.pad, l.bottomY - 54, l.contentW, 22).build());
            setInitialFocus(nameBox);
        } else if (view.mode.equals("MASTER")) {
            int golemY = Math.min(l.y + 180, l.bottomY - 86);
            int gap = 6;
            int bw = Math.max(76, (l.contentW - gap * 3) / 4);
            boolean ordinaryAvailable = view.ordinaryGolems < view.maxOrdinaryGolems;
            boolean eliteAvailable = view.maxEliteGolems > 0 && view.eliteGolems < view.maxEliteGolems;
            Button ironButton = Button.builder(HomeCraftGuildI18n.c("golem_type.homecraftguild.iron"), b -> send("hire_golem", "minecraft:iron_golem"))
                    .bounds(l.x + l.pad, golemY, bw, 22).build();
            ironButton.active = ordinaryAvailable;
            addRenderableWidget(ironButton);
            Button snowButton = Button.builder(HomeCraftGuildI18n.c("golem_type.homecraftguild.snow"), b -> send("hire_golem", "minecraft:snow_golem"))
                    .bounds(l.x + l.pad + (bw + gap), golemY, bw, 22).build();
            snowButton.active = ordinaryAvailable;
            addRenderableWidget(snowButton);
            Button copperButton = Button.builder(HomeCraftGuildI18n.c("golem_type.homecraftguild.copper"), b -> send("hire_golem", "minecraft:copper_golem"))
                    .bounds(l.x + l.pad + (bw + gap) * 2, golemY, bw, 22).build();
            copperButton.active = ordinaryAvailable;
            addRenderableWidget(copperButton);
            if (view.maxEliteGolems > 0) {
                Button eliteButton = Button.builder(HomeCraftGuildI18n.c("golem_type.homecraftguild.elite"), b -> send("hire_golem", "elite"))
                        .bounds(l.x + l.pad + (bw + gap) * 3, golemY, bw, 22).build();
                eliteButton.active = eliteAvailable;
                addRenderableWidget(eliteButton);
            }
            addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("button.homecraftguild.disband_guild"), b -> send("disband", ""))
                    .bounds(l.x + l.pad, l.bottomY - 54, l.contentW, 22).build());
        }
        addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("button.homecraftguild.close"), b -> onClose())
                .bounds(l.x + l.pad, l.bottomY - 26, l.contentW, 22).build());
    }

    private void refreshLiveLanguage() {
        String now = HomeCraftGuildI18n.languageStamp();
        if (now.equals(liveLanguageStamp)) return;
        liveLanguageStamp = now;
        String nameValue = nameBox == null ? "" : nameBox.getValue();
        helpLines = buildHelpLines();
        clearWidgets();
        init();
        if (nameBox != null && !nameValue.isBlank()) nameBox.setValue(nameValue);
    }

    private Layout layout() {
        Layout l = new Layout();
        l.panelW = Math.min(560, Math.max(330, this.width - 24));
        int targetH = view.mode.equals("CREATE") ? 356 : (view.mode.equals("MASTER") ? 336 : 246);
        l.panelH = Math.min(targetH, Math.max(218, this.height - 20));
        l.x = (this.width - l.panelW) / 2;
        l.y = Math.max(8, (this.height - l.panelH) / 2);
        l.pad = this.width < 480 ? 14 : 22;
        l.contentW = l.panelW - l.pad * 2;
        l.bottomY = l.y + l.panelH - 8;
        return l;
    }

    private void send(String action, String value) {
        ClientPacketDistributor.sendToServer(new GuildActionPayload(action, value == null ? "" : value, value == null ? "" : value));
        onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = mouseEventDouble(event, 0.0D, "x", "mouseX");
        double mouseY = mouseEventDouble(event, 1.0D, "y", "mouseY");
        if (helpOpen) {
            if (inside(mouseX, mouseY, helpCloseX, helpCloseY, helpCloseW, helpCloseH)) {
                helpOpen = false;
                return true;
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (helpOpen) {
            int delta = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
            if (delta != 0) {
                int max = Math.max(0, helpLines.size() - visibleLineCount(helpInnerH, 12));
                helpScroll = clamp(helpScroll + delta, 0, max);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshLiveLanguage();
        graphics.fill(0, 0, this.width, this.height, 0xAA05070D);
        Layout l = layout();
        graphics.fill(l.x, l.y, l.x + l.panelW, l.y + l.panelH, 0xF0101119);
        graphics.fill(l.x, l.y, l.x + l.panelW, l.y + 3, 0xFFFFA914);
        graphics.drawCenteredString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.title"), this.width / 2, l.y + 12, 0xFFFFF3DC);

        if (view.mode.equals("CREATE")) {
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.not_in_guild"), l.x + l.pad, l.y + 38, 0xFFD8DEE9, false);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.creation_cost", view.cost, view.memberLimit), l.x + l.pad, l.y + 54, 0xFFFFD99A, false);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.name_rule"), l.x + l.pad, l.y + 70, 0xFF9AA4B2, false);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.guild_color", colorLabel(selectedColor)), l.x + l.pad, l.y + 116, colorArgb(selectedColor), false);
            int bonusY = Math.min(l.y + 228, l.bottomY - 92);
            drawLevelSummary(graphics, l.x + l.pad, bonusY, l.contentW);
        } else if (view.mode.equals("MASTER")) {
            String title = HomeCraftGuildI18n.t("screen.homecraftguild.common.level_name", view.guildLevel, trim(view.guildName, Math.max(12, l.contentW / 7)));
            graphics.drawString(this.font, title, l.x + l.pad, l.y + 38, 0xFFFFD99A, false);
            drawXpBar(graphics, l.x + l.pad, l.y + 54, l.contentW, 9);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.color_members", colorLabel(view.color), view.members, view.memberLimit), l.x + l.pad, l.y + 72, 0xFFD8DEE9, false);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.golems_summary", view.golems, view.maxGolems, view.ordinaryGolems, view.maxOrdinaryGolems, view.eliteGolems, view.maxEliteGolems), l.x + l.pad, l.y + 88, 0xFFD8DEE9, false);
            String eliteText = view.maxEliteGolems > 0 ? HomeCraftGuildI18n.t("screen.homecraftguild.create.elite_cost", view.eliteGolemCost) : HomeCraftGuildI18n.t("screen.homecraftguild.create.elite_unlock_level", view.nextEliteGolemUnlockLevel);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.golem_cost", view.normalGolemCost, eliteText), l.x + l.pad, l.y + 104, 0xFFFFD99A, false);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.hire_golems"), l.x + l.pad, Math.min(l.y + 164, l.bottomY - 102), 0xFF9AA4B2, false);
            drawLevelSummary(graphics, l.x + l.pad, l.y + 124, l.contentW);
        } else {
            String title = HomeCraftGuildI18n.t("screen.homecraftguild.common.level_name", view.guildLevel, trim(view.guildName, Math.max(12, l.contentW / 7)));
            graphics.drawString(this.font, title, l.x + l.pad, l.y + 42, 0xFFFFD99A, false);
            drawXpBar(graphics, l.x + l.pad, l.y + 58, l.contentW, 9);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.roster_hint"), l.x + l.pad, l.y + 76, 0xFFD8DEE9, false);
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.master_only_hint"), l.x + l.pad, l.y + 92, 0xFF9AA4B2, false);
            drawLevelSummary(graphics, l.x + l.pad, l.y + 116, l.contentW);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (view.mode.equals("CREATE")) renderColorButtonOverlays(graphics, mouseX, mouseY);
        if (helpOpen) renderHelpOverlay(graphics);
    }

    private List<String> buildHelpLines() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 29; i++) {
            String line = HomeCraftGuildI18n.t("screen.homecraftguild.create.help.line_" + i);
            if (line.equals("<blank>")) line = "";
            lines.add(line);
        }
        return lines;
    }

    private void renderHelpOverlay(GuiGraphics graphics) {
        int w = Math.min(420, this.width - 24);
        int h = Math.min(250, this.height - 24);
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        helpPanelX = x; helpPanelY = y; helpPanelW = w; helpPanelH = h;
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        graphics.fill(x, y, x + w, y + h, 0xF0151920);
        graphics.fill(x, y, x + w, y + 3, 0xFFFFA914);
        graphics.drawCenteredString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.help_title"), x + w / 2, y + 10, 0xFFFFF3DC);
        helpCloseW = 18; helpCloseH = 18; helpCloseX = x + w - 24; helpCloseY = y + 8;
        graphics.fill(helpCloseX, helpCloseY, helpCloseX + helpCloseW, helpCloseY + helpCloseH, 0xFF313842);
        graphics.drawCenteredString(this.font, "×", helpCloseX + helpCloseW / 2, helpCloseY + 5, 0xFFFFFFFF);

        helpInnerX = x + 12;
        helpInnerY = y + 34;
        helpInnerW = w - 24;
        helpInnerH = h - 50;
        int lineStep = 12;
        List<String> wrappedHelpLines = wrapHelpLines(helpLines, helpInnerW);
        int visibleLines = visibleLineCount(helpInnerH, lineStep);
        int maxScroll = Math.max(0, wrappedHelpLines.size() - visibleLines);
        helpScroll = clamp(helpScroll, 0, maxScroll);

        int drawY = helpInnerY;
        for (int i = helpScroll; i < wrappedHelpLines.size() && drawY <= helpInnerY + helpInnerH - 10; i++) {
            String line = wrappedHelpLines.get(i);
            int color = line.endsWith(":") ? 0xFFFFD99A : 0xFFD8DEE9;
            graphics.drawString(this.font, line, helpInnerX, drawY, color, false);
            drawY += lineStep;
        }

        if (wrappedHelpLines.size() > visibleLines) {
            String scrollLabel = HomeCraftGuildI18n.t("screen.homecraftguild.common.scroll_range", helpScroll + 1, Math.min(wrappedHelpLines.size(), helpScroll + visibleLines), wrappedHelpLines.size());
            graphics.drawString(this.font, scrollLabel, helpInnerX, y + h - 12, 0xFF9AA4B2, false);
            if (helpScroll > 0) graphics.drawString(this.font, "↑", x + w - 38, y + 34, 0xFFFFD99A, false);
            if (helpScroll < maxScroll) graphics.drawString(this.font, "↓", x + w - 38, y + h - 24, 0xFFFFD99A, false);
        }
    }

    private List<String> wrapHelpLines(List<String> source, int maxWidthPx) {
        List<String> out = new ArrayList<>();
        if (source == null) return out;
        for (String line : source) {
            if (line == null || line.isBlank()) {
                out.add("");
                continue;
            }
            List<String> wrapped = wrapText(line, maxWidthPx);
            if (wrapped.isEmpty()) out.add(line);
            else out.addAll(wrapped);
        }
        return out;
    }

    private List<String> wrapText(String text, int maxWidthPx) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        int limit = Math.max(30, maxWidthPx - 4);
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (line.length() == 0) {
                appendWrappedWord(out, line, word, limit);
                continue;
            }
            String candidate = line + " " + word;
            if (this.font.width(candidate) <= limit) {
                line.append(' ').append(word);
            } else {
                out.add(line.toString());
                line.setLength(0);
                appendWrappedWord(out, line, word, limit);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private void appendWrappedWord(List<String> out, StringBuilder line, String word, int limitPx) {
        if (this.font.width(word) <= limitPx) {
            line.append(word);
            return;
        }
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            String candidate = chunk.toString() + c;
            if (chunk.length() > 0 && this.font.width(candidate) > limitPx) {
                out.add(chunk.toString());
                chunk.setLength(0);
            }
            chunk.append(c);
        }
        if (chunk.length() > 0) line.append(chunk);
    }

    private int visibleLineCount(int innerH, int lineStep) {
        return Math.max(1, innerH / Math.max(1, lineStep));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void drawXpBar(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF262B34);
        int fill = view.guildXpLevelSize <= 0 ? w : Math.max(0, Math.min(w, (int) Math.round((double) view.guildXpInLevel * w / Math.max(1, view.guildXpLevelSize))));
        graphics.fill(x, y, x + fill, y + h, 0xFFFFA914);
        String label = view.guildXpLevelSize <= 0 ? HomeCraftGuildI18n.t("screen.homecraftguild.common.max_level") : HomeCraftGuildI18n.t("screen.homecraftguild.common.xp_plain", view.guildXpInLevel, view.guildXpLevelSize);
        graphics.drawCenteredString(this.font, label, x + w / 2, y + Math.max(0, (h - 8) / 2), 0xFFFFFFFF);
    }

    private void drawLevelSummary(GuiGraphics graphics, int x, int y, int w) {
        int line = 0;
        graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.create.summary_guild"), x, y + line, 0xFF9FE7FF, false); line += 14;
        for (String row : wrapText(HomeCraftGuildI18n.t("screen.homecraftguild.create.summary_line_1"), w)) {
            graphics.drawString(this.font, row, x, y + line, 0xFFB7A7FF, false); line += 12;
        }
        for (String row : wrapText(HomeCraftGuildI18n.t("screen.homecraftguild.create.summary_line_2"), w)) {
            graphics.drawString(this.font, row, x, y + line, 0xFF9AFFB4, false); line += 12;
        }
        for (String row : wrapText(HomeCraftGuildI18n.t("screen.homecraftguild.create.summary_line_3", view.guildLevel, view.ordinaryGolems, view.maxOrdinaryGolems, view.eliteGolems, view.maxEliteGolems, view.territoryLimit), w)) {
            graphics.drawString(this.font, row, x, y + line, 0xFF9AA4B2, false); line += 12;
        }
    }

    private void renderColorButtonOverlays(GuiGraphics graphics, int mouseX, int mouseY) {
        for (ColorButtonOverlay b : colorButtons) {
            boolean selected = b.color.equals(selectedColor);
            boolean hover = inside(mouseX, mouseY, b.x, b.y, b.w, b.h);
            int border = selected ? 0xFFFFFFFF : (hover ? 0xFFFFD99A : 0xFF303643);
            graphics.fill(b.x - 1, b.y - 1, b.x + b.w + 1, b.y + b.h + 1, border);
            graphics.fill(b.x, b.y, b.x + b.w, b.y + b.h, colorArgb(b.color));
            if (hover || selected) graphics.fill(b.x, b.y, b.x + b.w, b.y + b.h, selected ? 0x22FFFFFF : 0x18FFFFFF);
            graphics.drawCenteredString(this.font, b.label, b.x + b.w / 2, b.y + 6, readableTextArgb(b.color));
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String colorLabel(String color) {
        return switch (String.valueOf(color == null ? "" : color)) {
            case "white" -> HomeCraftGuildI18n.t("color.homecraftguild.white");
            case "orange" -> HomeCraftGuildI18n.t("color.homecraftguild.orange");
            case "magenta" -> HomeCraftGuildI18n.t("color.homecraftguild.magenta");
            case "light_blue" -> HomeCraftGuildI18n.t("color.homecraftguild.light_blue");
            case "yellow" -> HomeCraftGuildI18n.t("color.homecraftguild.yellow");
            case "lime" -> HomeCraftGuildI18n.t("color.homecraftguild.lime");
            case "cyan" -> HomeCraftGuildI18n.t("color.homecraftguild.cyan");
            case "purple" -> HomeCraftGuildI18n.t("color.homecraftguild.purple");
            case "blue" -> HomeCraftGuildI18n.t("color.homecraftguild.blue");
            case "red" -> HomeCraftGuildI18n.t("color.homecraftguild.red");
            default -> HomeCraftGuildI18n.t("color.homecraftguild.magenta");
        };
    }

    private static int colorArgb(String color) {
        return switch (String.valueOf(color == null ? "" : color)) {
            case "white" -> 0xFFF4F4F4;
            case "orange" -> 0xFFFF9A28;
            case "magenta" -> 0xFFFF45F6;
            case "light_blue" -> 0xFF55BFFF;
            case "yellow" -> 0xFFFFE057;
            case "lime" -> 0xFF79F05A;
            case "cyan" -> 0xFF4FE7FF;
            case "purple" -> 0xFFA95CFF;
            case "blue" -> 0xFF5478FF;
            case "red" -> 0xFFFF5A5A;
            default -> 0xFFFF45F6;
        };
    }

    private static int readableTextArgb(String color) {
        return switch (String.valueOf(color == null ? "" : color)) {
            case "white", "yellow", "lime", "cyan", "light_blue" -> 0xFF161A20;
            default -> 0xFFFFFFFF;
        };
    }

    private static final class Layout {
        int x, y, panelW, panelH, pad, contentW, bottomY;
    }

    private static final class ColorButtonOverlay {
        final int x, y, w, h;
        final String color;
        final String label;
        ColorButtonOverlay(int x, int y, int w, int h, String color, String label) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.color = color;
            this.label = label;
        }
    }

    private static final class NpcView {
        String mode = "CREATE";
        String guildName = "";
        String color = "magenta";
        int cost = 3;
        int members = 0;
        int memberLimit = 20;
        int golems = 0;
        int ordinaryGolems = 0;
        int eliteGolems = 0;
        int maxGolems = 1;
        int maxOrdinaryGolems = 1;
        int maxEliteGolems = 0;
        int normalGolemCost = 1;
        int eliteGolemCost = 0;
        int nextEliteGolemUnlockLevel = 6;
        int guildLevel = 1;
        int guildXp = 0;
        int guildXpInLevel = 0;
        int guildXpLevelSize = 500;
        int healthBonus = 10;
        int armorBonus = 0;
        int damageBonus = 0;
        boolean nightVision = false;
        int territoryLimit = 1;

        static NpcView parse(String raw) {
            NpcView view = new NpcView();
            for (String line : raw.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "MODE" -> view.mode = value;
                    case "GUILD" -> view.guildName = value;
                    case "COLOR" -> view.color = value;
                    case "COST" -> view.cost = parseInt(value, 3);
                    case "MEMBERS" -> view.members = parseInt(value, 0);
                    case "MEMBER_LIMIT" -> view.memberLimit = parseInt(value, 20);
                    case "GOLEMS" -> view.golems = parseInt(value, 0);
                    case "ORDINARY_GOLEMS" -> view.ordinaryGolems = parseInt(value, 0);
                    case "ELITE_GOLEMS" -> view.eliteGolems = parseInt(value, 0);
                    case "MAX_GOLEMS" -> view.maxGolems = parseInt(value, 1);
                    case "MAX_ORDINARY_GOLEMS" -> view.maxOrdinaryGolems = parseInt(value, 1);
                    case "MAX_ELITE_GOLEMS" -> view.maxEliteGolems = parseInt(value, 0);
                    case "NORMAL_GOLEM_COST", "NEXT_COST" -> view.normalGolemCost = parseInt(value, 1);
                    case "ELITE_GOLEM_COST" -> view.eliteGolemCost = parseInt(value, 0);
                    case "NEXT_ELITE_GOLEM_UNLOCK_LEVEL" -> view.nextEliteGolemUnlockLevel = parseInt(value, 5);
                    case "GUILD_LEVEL" -> view.guildLevel = parseInt(value, 1);
                    case "GUILD_XP" -> view.guildXp = parseInt(value, 0);
                    case "GUILD_XP_IN_LEVEL" -> view.guildXpInLevel = parseInt(value, 0);
                    case "GUILD_XP_LEVEL_SIZE" -> view.guildXpLevelSize = parseInt(value, 500);
                    case "HEALTH_BONUS" -> view.healthBonus = parseInt(value, 10);
                    case "ARMOR_BONUS" -> view.armorBonus = parseInt(value, 0);
                    case "DAMAGE_BONUS" -> view.damageBonus = parseInt(value, 0);
                    case "NIGHT_VISION" -> view.nightVision = Boolean.parseBoolean(value);
                    case "TERRITORY_LIMIT" -> view.territoryLimit = parseInt(value, 1);
                }
            }
            view.maxGolems = Math.max(view.maxGolems, view.maxOrdinaryGolems + view.maxEliteGolems);
            view.golems = Math.max(view.golems, view.ordinaryGolems + view.eliteGolems);
            return view;
        }

        private static int parseInt(String raw, int fallback) {
            try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; }
        }
    }
}
