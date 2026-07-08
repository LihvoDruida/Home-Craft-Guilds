package ua.homecraft.guild.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import ua.homecraft.guild.HomeCraftGuildMod;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import ua.homecraft.guild.network.GuildActionPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GuildTalentScreen extends Screen {
    private final String snapshot;
    private final TalentsView view;
    private final int scrollRows;
    private final String activeBranch;
    private final List<TooltipArea> tooltipAreas = new ArrayList<>();

    public GuildTalentScreen(String snapshot) {
        this(snapshot, 0, "member");
    }

    public GuildTalentScreen(String snapshot, int scrollRows) {
        this(snapshot, scrollRows, "member");
    }

    public GuildTalentScreen(String snapshot, int scrollRows, String activeBranch) {
        super(HomeCraftGuildI18n.c("screen.homecraftguild.talents.title"));
        this.snapshot = snapshot == null ? "" : snapshot;
        this.view = TalentsView.parse(this.snapshot);
        this.scrollRows = Math.max(0, scrollRows);
        this.activeBranch = "golem".equals(activeBranch) ? "golem" : "member";
    }

    public GuildTalentScreen withSnapshot(String nextSnapshot) {
        return new GuildTalentScreen(nextSnapshot, this.scrollRows, this.activeBranch);
    }

    @Override
    protected void init() {
        Layout l = layout();
        int topY = l.y + 36;
        int buttonW = l.compact ? 68 : 78;
        addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("button.homecraftguild.back"), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(new GuildRosterScreen(this.snapshot));
        }).bounds(l.x + l.pad, topY, buttonW, 22).build());
        addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("button.homecraftguild.refresh"), b -> send("request", "", ""))
                .bounds(l.x + l.pad + buttonW + 8, topY, l.compact ? 76 : 82, 22).build());

        if (l.compact) {
            int tabsY = topY + 28;
            int tabW = Math.max(86, (l.panelW - l.pad * 2 - 8) / 2);
            addRenderableWidget(Button.builder(Component.literal(("member".equals(activeBranch) ? "✓ " : "") + HomeCraftGuildI18n.t("talent_branch.homecraftguild.member")), b -> setBranch("member"))
                    .bounds(l.x + l.pad, tabsY, tabW, 22).build());
            addRenderableWidget(Button.builder(Component.literal(("golem".equals(activeBranch) ? "✓ " : "") + HomeCraftGuildI18n.t("talent_branch.homecraftguild.golem")), b -> setBranch("golem"))
                    .bounds(l.x + l.pad + tabW + 8, tabsY, tabW, 22).build());
        }

        addRenderableWidget(Button.builder(Component.literal("▲"), b -> setScroll(scrollRows - 1))
                .bounds(l.x + l.panelW - l.pad - 56, topY, 24, 22).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> setScroll(scrollRows + 1))
                .bounds(l.x + l.panelW - l.pad - 28, topY, 24, 22).build());

        if (l.compact) {
            addResetButtonIfAllowed(activeBranch, l.x + l.pad, l.branchY + 26, l.panelW - l.pad * 2);
            addUnlockButtonsForBranch(activeBranch, l.x + l.pad, l.branchY + 58, l.panelW - l.pad * 2, l.bottomY - 10, rowHeight(l.panelW - l.pad * 2));
            return;
        }

        int gap = 12;
        int colW = (l.panelW - l.pad * 2 - gap) / 2;
        addResetButtonIfAllowed("member", l.x + l.pad, l.branchY + 26, colW);
        addResetButtonIfAllowed("golem", l.x + l.pad + colW + gap, l.branchY + 26, colW);
        addUnlockButtonsForBranch("member", l.x + l.pad, l.branchY + 58, colW, l.bottomY - 10, rowHeight(colW));
        addUnlockButtonsForBranch("golem", l.x + l.pad + colW + gap, l.branchY + 58, colW, l.bottomY - 10, rowHeight(colW));
    }

    private void addResetButtonIfAllowed(String branch, int x, int y, int colW) {
        if (!view.canManageTalents) return;
        String label = "member".equals(branch) ? HomeCraftGuildI18n.t("button.homecraftguild.reset_members") : HomeCraftGuildI18n.t("button.homecraftguild.reset_golems");
        int w = Math.min(colW - 8, colW < 220 ? 118 : 144);
        addRenderableWidget(Button.builder(Component.literal(label), b -> send("reset_talent_branch", branch, ""))
                .bounds(x + colW - w, y, w, 20).build());
    }

    private void addUnlockButtonsForBranch(String branch, int x, int yStart, int colW, int bottomY, int rowH) {
        List<TalentRow> rows = view.branchRows(branch);
        for (int i = 0; i < rows.size(); i++) {
            TalentRow row = rows.get(i);
            int y = yStart + (i - scrollRows) * rowH;
            if (y < yStart || y + rowH > bottomY) continue;
            if (view.canManageTalents && !row.unlocked && row.isAvailable()) {
                int w = colW < 230 ? 58 : 72;
                addRenderableWidget(Button.builder(Component.literal(colW < 230 ? "+" : HomeCraftGuildI18n.t("button.homecraftguild.unlock")), b -> send("unlock_talent", row.id, ""))
                        .bounds(x + colW - w - 8, y + rowH - 26, w, 18).build());
            }
        }
    }

    private void setBranch(String branch) {
        if (this.minecraft != null) this.minecraft.setScreen(new GuildTalentScreen(this.snapshot, 0, branch));
    }

    private void setScroll(int next) {
        Layout l = layout();
        int max;
        if (l.compact) {
            int rows = view.branchRows(activeBranch).size();
            int visible = visibleRowsFor(l.panelW - l.pad * 2, l.branchY + 58, l.bottomY - 10);
            max = Math.max(0, rows - visible);
        } else {
            int gap = 12;
            int colW = (l.panelW - l.pad * 2 - gap) / 2;
            int visible = visibleRowsFor(colW, l.branchY + 58, l.bottomY - 10);
            max = Math.max(0, Math.max(view.branchRows("member").size(), view.branchRows("golem").size()) - visible);
        }
        next = Math.max(0, Math.min(max, next));
        if (this.minecraft != null) this.minecraft.setScreen(new GuildTalentScreen(this.snapshot, next, this.activeBranch));
    }

    private void send(String action, String a, String b) {
        ClientPacketDistributor.sendToServer(new GuildActionPayload(action, a == null ? "" : a, b == null ? "" : b));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0) { setScroll(scrollRows - 1); return true; }
        if (scrollY < 0) { setScroll(scrollRows + 1); return true; }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        tooltipAreas.clear();
        graphics.fill(0, 0, this.width, this.height, 0xAA05070D);
        Layout l = layout();
        graphics.fill(l.x, l.y, l.x + l.panelW, l.y + l.panelH, 0xEC10131A);
        graphics.fill(l.x + 1, l.y + 1, l.x + l.panelW - 1, l.y + 28, 0xFF202633);
        graphics.drawString(this.font, trimToWidth(HomeCraftGuildI18n.t("screen.homecraftguild.talents.title"), Math.max(80, l.panelW / 3)), l.x + l.pad, l.y + 10, 0xFFFFD99A, false);
        String levelLine = HomeCraftGuildI18n.t("screen.homecraftguild.talents.level", view.guildLevel);
        graphics.drawString(this.font, levelLine, l.x + l.pad + Math.min(150, Math.max(92, l.panelW / 3)), l.y + 10, 0xFFD8DEE9, false);
        if (!l.compact) {
            String mode = view.canManageTalents ? HomeCraftGuildI18n.t("screen.homecraftguild.talents.manage_mode") : HomeCraftGuildI18n.t("screen.homecraftguild.talents.view_mode");
            graphics.drawString(this.font, trimToWidth(mode, 230), l.x + l.panelW - l.pad - 230, l.y + 10, view.canManageTalents ? 0xFF9AFFB4 : 0xFFFFC06A, false);
        }

        if (l.compact) {
            drawTabState(graphics, l.x + l.pad, l.y + 64, (l.panelW - l.pad * 2 - 8) / 2, "member", HomeCraftGuildI18n.t("talent_branch.homecraftguild.member"));
            drawTabState(graphics, l.x + l.pad + (l.panelW - l.pad * 2 - 8) / 2 + 8, l.y + 64, (l.panelW - l.pad * 2 - 8) / 2, "golem", HomeCraftGuildI18n.t("talent_branch.homecraftguild.golem"));
            renderBranch(graphics, activeBranch, l.x + l.pad, l.branchY, l.panelW - l.pad * 2, l.bottomY - 10);
        } else {
            int gap = 12;
            int colW = (l.panelW - l.pad * 2 - gap) / 2;
            renderBranch(graphics, "member", l.x + l.pad, l.branchY, colW, l.bottomY - 10);
            renderBranch(graphics, "golem", l.x + l.pad + colW + gap, l.branchY, colW, l.bottomY - 10);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        renderHoveredTooltip(graphics, mouseX, mouseY);
    }

    private void drawTabState(GuiGraphics graphics, int x, int y, int w, String branch, String label) {
        boolean selected = branch.equals(activeBranch);
        graphics.fill(x, y, x + w, y + 20, selected ? 0xFF3A3521 : 0xFF222633);
        graphics.drawCenteredString(this.font, label, x + w / 2, y + 6, selected ? 0xFFFFD99A : 0xFFD8DEE9);
    }

    private void renderBranch(GuiGraphics graphics, String branch, int x, int y, int w, int bottom) {
        boolean member = "member".equals(branch);
        int available = member ? view.memberAvailable : view.golemAvailable;
        int total = member ? view.memberTotal : view.golemTotal;
        int spent = member ? view.memberSpent : view.golemSpent;
        String title = member ? HomeCraftGuildI18n.t("talent_branch.homecraftguild.member") : HomeCraftGuildI18n.t("talent_branch.homecraftguild.golem");
        graphics.fill(x, y, x + w, y + 24, member ? 0xFF223022 : 0xFF222A35);
        graphics.drawString(this.font, trimToWidth(HomeCraftGuildI18n.t("screen.homecraftguild.talents.points_line", title, available, total, spent), w - 16), x + 8, y + 8, 0xFFFFFFFF, false);

        List<TalentRow> rows = view.branchRows(branch);
        int rowH = rowHeight(w);
        int yStart = y + 58;
        for (int i = 0; i < rows.size(); i++) {
            TalentRow row = rows.get(i);
            int ry = yStart + (i - scrollRows) * rowH;
            if (ry < yStart || ry + rowH > bottom) continue;
            int bg = row.unlocked ? 0xFF1F3A24 : (row.isAvailable() ? 0xFF303021 : 0xFF262932);
            graphics.fill(x, ry, x + w, ry + rowH - 4, bg);
            drawTalentIcon(graphics, row, x + 8, ry + 8);

            int reserveButton = (view.canManageTalents && !row.unlocked && row.isAvailable()) ? (w < 230 ? 70 : 88) : 8;
            int tx = x + 36;
            int textW = Math.max(64, w - 44 - reserveButton);
            String rowTitle = HomeCraftGuildI18n.talentTitle(row.id, row.title);
            String rowEffect = HomeCraftGuildI18n.talentEffect(row.id, row.effect);
            graphics.drawString(this.font, trimToWidth(rowTitle, textW), tx, ry + 6, row.unlocked ? 0xFF9AFFB4 : 0xFFFFD99A, false);
            List<String> effectLines = wrapText(rowEffect, textW);
            int lineY = ry + 20;
            for (int n = 0; n < Math.min(2, effectLines.size()); n++) {
                graphics.drawString(this.font, effectLines.get(n), tx, lineY, 0xFFD8DEE9, false);
                lineY += 11;
            }
            String req = HomeCraftGuildI18n.t("screen.homecraftguild.talents.req_line", row.requiredLevel, HomeCraftGuildI18n.talentStatus(row.status));
            graphics.drawString(this.font, trimToWidth(req, Math.max(64, w - 46)), tx, ry + rowH - 17, row.unlocked ? 0xFF9AFFB4 : (row.isAvailable() ? 0xFFFFC06A : 0xFF9AA4B2), false);
            tooltipAreas.add(new TooltipArea(x, ry, w, rowH - 4, talentTooltip(row)));
        }
        int visible = visibleRowsFor(w, yStart, bottom);
        if (rows.size() > visible) {
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.common.shown", scrollRows + 1, Math.min(rows.size(), scrollRows + visible), rows.size()), x + 8, bottom - 11, 0xFF9AA4B2, false);
        }
    }

    private List<String> talentTooltip(TalentRow row) {
        List<String> out = new ArrayList<>();
        out.add(HomeCraftGuildI18n.talentTitle(row.id, row.title));
        out.addAll(wrapPlain(HomeCraftGuildI18n.talentDescription(row.id, row.description), 48));
        String effect = HomeCraftGuildI18n.talentEffect(row.id, row.effect);
        if (!effect.isBlank()) out.add(HomeCraftGuildI18n.t("screen.homecraftguild.talents.tooltip_effect", effect));
        out.add(HomeCraftGuildI18n.t("screen.homecraftguild.talents.tooltip_cost", row.cost));
        out.add(HomeCraftGuildI18n.t("screen.homecraftguild.talents.tooltip_required", row.requiredLevel));
        if (!row.prerequisite.isBlank()) out.add(HomeCraftGuildI18n.t("screen.homecraftguild.talents.tooltip_prereq", row.prerequisite.replace(',', '+')));
        out.add(HomeCraftGuildI18n.t("screen.homecraftguild.talents.tooltip_status", HomeCraftGuildI18n.talentStatus(row.status)));
        return out;
    }

    private void drawTalentIcon(GuiGraphics graphics, TalentRow row, int x, int y) {
        try {
            String icon = row == null || row.icon == null || row.icon.isBlank() ? "unknown" : row.icon.trim().toLowerCase(Locale.ROOT);
            Identifier texture = Identifier.fromNamespaceAndPath(HomeCraftGuildMod.MOD_ID, "textures/gui/talents/" + icon + ".png");
            graphics.blit(texture, x, y, 0, 0, 18, 18, 18, 18);
        } catch (Throwable ignored) {
            graphics.fill(x, y, x + 18, y + 18, 0xFF2D3340);
            graphics.drawString(this.font, "✦", x + 5, y + 5, 0xFFFFD99A, false);
        }
    }

    private int rowHeight(int w) {
        if (w < 230) return 76;
        if (w < 340) return 68;
        return 62;
    }

    private int visibleRowsFor(int w, int yStart, int bottom) {
        return Math.max(1, Math.max(0, bottom - yStart) / Math.max(1, rowHeight(w)));
    }

    private String trimToWidth(String value, int maxPx) {
        if (value == null) return "";
        if (this.font.width(value) <= maxPx) return value;
        String suffix = "…";
        String out = value;
        while (!out.isEmpty() && this.font.width(out + suffix) > maxPx) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? suffix : out + suffix;
    }

    private List<String> wrapText(String text, int maxWidthPx) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && this.font.width(candidate) > maxWidthPx) {
                out.add(trimToWidth(line.toString(), maxWidthPx));
                line.setLength(0);
            }
            if (this.font.width(word) > maxWidthPx) {
                if (!line.isEmpty()) { out.add(trimToWidth(line.toString(), maxWidthPx)); line.setLength(0); }
                out.addAll(breakLongWord(word, maxWidthPx));
            } else {
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
        }
        if (!line.isEmpty()) out.add(trimToWidth(line.toString(), maxWidthPx));
        return out;
    }

    private List<String> breakLongWord(String word, int maxWidthPx) {
        List<String> out = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            String candidate = chunk.toString() + word.charAt(i);
            if (!chunk.isEmpty() && this.font.width(candidate) > maxWidthPx) {
                out.add(chunk.toString());
                chunk.setLength(0);
            }
            chunk.append(word.charAt(i));
        }
        if (!chunk.isEmpty()) out.add(chunk.toString());
        return out;
    }

    private List<String> wrapPlain(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > maxChars) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (TooltipArea area : tooltipAreas) {
            if (!area.contains(mouseX, mouseY)) continue;
            int max = 0;
            for (String line : area.lines) max = Math.max(max, this.font.width(line));
            int w = Math.min(this.width - 12, max + 10);
            int h = area.lines.size() * 12 + 6;
            int x = Math.min(this.width - w - 6, mouseX + 10);
            int y = Math.min(this.height - h - 6, mouseY + 10);
            graphics.fill(x, y, x + w, y + h, 0xF0151920);
            graphics.fill(x, y, x + w, y + 2, 0xFFFFA914);
            int ty = y + 4;
            for (String line : area.lines) {
                graphics.drawString(this.font, trimToWidth(line, w - 10), x + 5, ty, 0xFFFFF3DC, false);
                ty += 12;
            }
            return;
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private Layout layout() {
        Layout l = new Layout();
        l.panelW = Math.min(980, Math.max(280, this.width - 20));
        l.panelH = Math.min(680, Math.max(260, this.height - 20));
        l.x = (this.width - l.panelW) / 2;
        l.y = Math.max(5, (this.height - l.panelH) / 2);
        l.pad = this.width < 420 ? 8 : (this.width < 620 ? 12 : 18);
        l.compact = l.panelW < 700;
        l.branchY = l.y + (l.compact ? 92 : 78);
        l.bottomY = l.y + l.panelH - 10;
        return l;
    }

    private static final class Layout {
        int x, y, panelW, panelH, pad, branchY, bottomY;
        boolean compact;
    }

    private static final class TooltipArea {
        final int x, y, w, h;
        final List<String> lines;
        TooltipArea(int x, int y, int w, int h, List<String> lines) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.lines = lines == null ? List.of() : lines;
        }
        boolean contains(double mx, double my) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }
    }

    private static final class TalentsView {
        int guildLevel = 1;
        boolean canManageTalents;
        int memberTotal = 1;
        int memberSpent = 0;
        int memberAvailable = 1;
        int golemTotal = 1;
        int golemSpent = 0;
        int golemAvailable = 1;
        final List<TalentRow> talents = new ArrayList<>();

        List<TalentRow> branchRows(String branch) {
            List<TalentRow> out = new ArrayList<>();
            for (TalentRow row : talents) if (row != null && branch.equals(row.branch)) out.add(row);
            out.sort(Comparator.comparing((TalentRow r) -> !r.unlocked).thenComparingInt(r -> r.row).thenComparing(r -> r.title));
            return out;
        }

        static TalentsView parse(String snapshot) {
            TalentsView out = new TalentsView();
            String section = "";
            for (String raw : snapshot.split("\\R")) {
                String line = raw == null ? "" : raw.trim();
                if (line.isEmpty()) continue;
                if (line.equals("TALENTS_BEGIN")) { section = line; continue; }
                if (line.equals("TALENTS_END")) { section = ""; continue; }
                if ("TALENTS_BEGIN".equals(section)) { out.talents.add(TalentRow.parse(line)); continue; }
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "GUILD_LEVEL" -> out.guildLevel = parseInt(value, 1);
                    case "CAN_MANAGE_TALENTS" -> out.canManageTalents = Boolean.parseBoolean(value);
                    case "MEMBER_TALENT_TOTAL" -> out.memberTotal = parseInt(value, 1);
                    case "MEMBER_TALENT_SPENT" -> out.memberSpent = parseInt(value, 0);
                    case "MEMBER_TALENT_AVAILABLE" -> out.memberAvailable = parseInt(value, 1);
                    case "GOLEM_TALENT_TOTAL" -> out.golemTotal = parseInt(value, 1);
                    case "GOLEM_TALENT_SPENT" -> out.golemSpent = parseInt(value, 0);
                    case "GOLEM_TALENT_AVAILABLE" -> out.golemAvailable = parseInt(value, 1);
                }
            }
            return out;
        }

        private static int parseInt(String value, int fallback) {
            try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
        }
    }

    private static final class TalentRow {
        String id = "";
        String branch = "";
        int row = 1;
        String title = "";
        String description = "";
        String effect = "";
        int cost = 1;
        int requiredLevel = 1;
        String prerequisite = "";
        boolean unlocked;
        String status = "заблоковано";
        String icon = "unknown";

        boolean isAvailable() { return "доступний".equalsIgnoreCase(status); }

        static TalentRow parse(String line) {
            String[] p = line.split("\\|", -1);
            TalentRow out = new TalentRow();
            out.id = part(p, 0);
            out.branch = part(p, 1);
            out.row = parseInt(part(p, 2), 1);
            out.title = part(p, 3);
            out.description = part(p, 4);
            out.effect = part(p, 5);
            out.cost = parseInt(part(p, 6), 1);
            out.requiredLevel = parseInt(part(p, 7), 1);
            out.prerequisite = part(p, 8);
            out.unlocked = Boolean.parseBoolean(part(p, 9));
            out.status = part(p, 10);
            out.icon = part(p, 11);
            if (out.icon == null || out.icon.isBlank()) out.icon = out.id;
            return out;
        }

        private static String part(String[] p, int index) { return p != null && index >= 0 && index < p.length ? p[index] : ""; }
        private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; } }
    }
}
