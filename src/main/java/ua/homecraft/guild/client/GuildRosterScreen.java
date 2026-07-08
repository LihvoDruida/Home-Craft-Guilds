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

public final class GuildRosterScreen extends Screen {
    private final String snapshot;
    private final GuildView view;
    private final List<MemberRow> memberRows = new ArrayList<>();
    private final List<InviteRow> inviteRows = new ArrayList<>();
    private final List<GolemAction> golemActions = new ArrayList<>();
    private final List<ContextAction> contextActions = new ArrayList<>();
    private final List<MemberAction> memberActions = new ArrayList<>();
    private final List<String> helpLines = buildHelpLines();
    private final List<TooltipArea> tooltipAreas = new ArrayList<>();

    private MemberRow kickConfirmMember;
    private int confirmBoxX;
    private int confirmBoxY;
    private int confirmBoxW;
    private int confirmBoxH;
    private int confirmYesX;
    private int confirmYesY;
    private int confirmYesW;
    private int confirmYesH;
    private int confirmNoX;
    private int confirmNoY;
    private int confirmNoW;
    private int confirmNoH;

    private EditBox inviteBox;
    private MemberRow selectedMember;
    private MemberRow contextMember;
    private int contextX;
    private int contextY;
    private int contextW;
    private int contextH;

    private final ScrollArea membersArea = new ScrollArea();
    private final ScrollArea infoArea = new ScrollArea();
    private final ScrollArea territoriesArea = new ScrollArea();
    private final ScrollArea golemsArea = new ScrollArea();
    private final ScrollArea achievementsArea = new ScrollArea();
    private final ScrollArea achievementDetailsArea = new ScrollArea();
    private final ScrollArea helpArea = new ScrollArea();

    private int membersScroll;
    private int infoScroll;
    private int territoriesScroll;
    private int golemsScroll;
    private int achievementsScroll;
    private int achievementDetailsScroll;
    private int selectedAchievementIndex;
    private int helpScroll;
    private boolean achievementsOpen;
    private boolean helpOpen;
    private int achievementsCloseX;
    private int achievementsCloseY;
    private int achievementsCloseW;
    private int achievementsCloseH;
    private int helpCloseX;
    private int helpCloseY;
    private int helpCloseW;
    private int helpCloseH;

    public GuildRosterScreen(String snapshot) {
        super(HomeCraftGuildI18n.c("screen.homecraftguild.roster.title"));
        this.snapshot = snapshot == null ? "" : snapshot;
        this.view = GuildView.parse(this.snapshot);
    }

    @Override
    protected void init() {
        memberRows.clear();
        inviteRows.clear();
        golemActions.clear();
        contextActions.clear();
        memberActions.clear();
        selectedMember = null;
        contextMember = null;
        inviteBox = null;
        tooltipAreas.clear();
        kickConfirmMember = null;
        if (selectedAchievementIndex < 0 || selectedAchievementIndex >= view.achievements.size()) selectedAchievementIndex = 0;

        Layout l = layout();
        if (view.mode.equals("INVITES")) {
            int listY = l.contentY + 44;
            int rowH = 32;
            int maxRows = Math.max(1, (l.bottomY - listY - 42) / 38);
            for (int i = 0; i < view.invites.size() && i < maxRows; i++) {
                InviteRow row = view.invites.get(i);
                int ry = listY + i * 38;
                row.x = l.x + l.pad;
                row.y = ry;
                row.w = l.panelW - l.pad * 2;
                row.h = rowH;
                inviteRows.add(row);
                int bw = Math.max(72, Math.min(96, (row.w - 18) / 4));
                addRenderableWidget(Button.builder(Component.literal("Прийняти"), b -> send("accept", row.guildId, ""))
                        .bounds(row.x + row.w - bw * 2 - 8, ry + 5, bw, 21).build());
                addRenderableWidget(Button.builder(Component.literal("Відмовити"), b -> send("decline", row.guildId, ""))
                        .bounds(row.x + row.w - bw, ry + 5, bw, 21).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Оновити"), b -> send("request", "", ""))
                    .bounds(l.x + l.pad, l.bottomY - 26, 100, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Закрити"), b -> onClose())
                    .bounds(l.x + l.panelW - l.pad - 100, l.bottomY - 26, 100, 22).build());
            return;
        }

        int footerY = l.bottomY - 28;
        if (view.readOnly) {
            addRenderableWidget(Button.builder(Component.literal("Таланти"), b -> openTalents())
                    .bounds(l.x + l.panelW - l.pad - 316, footerY, 100, 22).build());
            addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("screen.homecraftguild.achievements.button"), b -> achievementsOpen = true)
                    .bounds(l.x + l.panelW - l.pad - 208, footerY, 100, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Закрити"), b -> onClose())
                    .bounds(l.x + l.panelW - l.pad - 100, footerY, 100, 22).build());
        } else if (view.isGuildmaster) {
            boolean footerCompact = l.panelW < 560;
            int inviteY = footerCompact ? footerY - 84 : footerY - 28;
            int fullW = l.panelW - l.pad * 2;
            int fieldW = Math.max(110, Math.min(240, fullW - 112));
            int buttonW = 96;
            int fieldX = l.x + l.pad;
            this.inviteBox = new EditBox(this.font, fieldX, inviteY, fieldW, 22, Component.literal("Нік для запрошення"));
            this.inviteBox.setHint(Component.literal("Нік гравця"));
            this.inviteBox.setMaxLength(32);
            addRenderableWidget(inviteBox);
            addRenderableWidget(Button.builder(Component.literal("Запросити"), b -> {
                if (inviteBox != null && !inviteBox.getValue().isBlank()) send("invite", inviteBox.getValue(), "");
            }).bounds(fieldX + fieldW + 8, inviteY, buttonW, 22).build());
            int x0 = l.x + l.pad;
            if (footerCompact) {
                addRenderableWidget(Button.builder(Component.literal("Оновити"), b -> send("request", "", ""))
                        .bounds(x0, footerY - 56, 86, 22).build());
                addRenderableWidget(Button.builder(Component.literal("До тотема"), b -> send("teleport_guild", "", ""))
                        .bounds(x0 + 94, footerY - 56, 104, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Таланти"), b -> openTalents())
                        .bounds(x0, footerY - 28, 86, 22).build());
                addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("screen.homecraftguild.achievements.button"), b -> achievementsOpen = true)
                        .bounds(x0 + 94, footerY - 28, 110, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Закрити"), b -> onClose())
                        .bounds(l.x + l.panelW - l.pad - 100, footerY - 28, 100, 22).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("Оновити"), b -> send("request", "", ""))
                        .bounds(x0, footerY, 94, 22).build());
                x0 += 102;
                addRenderableWidget(Button.builder(Component.literal("До тотема"), b -> send("teleport_guild", "", ""))
                        .bounds(x0, footerY, 118, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Таланти"), b -> openTalents())
                        .bounds(l.x + l.panelW - l.pad - 316, footerY, 100, 22).build());
                addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("screen.homecraftguild.achievements.button"), b -> achievementsOpen = true)
                        .bounds(l.x + l.panelW - l.pad - 208, footerY, 100, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Закрити"), b -> onClose())
                        .bounds(l.x + l.panelW - l.pad - 100, footerY, 100, 22).build());
            }
        } else {
            int x0 = l.x + l.pad;
            boolean footerCompact = l.panelW < 560;
            if (footerCompact) {
                addRenderableWidget(Button.builder(Component.literal("До тотема"), b -> send("teleport_guild", "", ""))
                        .bounds(x0, footerY - 56, 90, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Оновити"), b -> send("request", "", ""))
                        .bounds(x0 + 98, footerY - 56, 74, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Закрити"), b -> onClose())
                        .bounds(l.x + l.panelW - l.pad - 80, footerY - 56, 80, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Вийти з гільдії"), b -> send("leave", "", ""))
                        .bounds(x0, footerY - 28, 122, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Таланти"), b -> openTalents())
                        .bounds(x0 + 130, footerY - 28, 86, 22).build());
                addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("screen.homecraftguild.achievements.button"), b -> achievementsOpen = true)
                        .bounds(x0 + 224, footerY - 28, 104, 22).build());
            } else {
                int teleportW = Math.min(128, Math.max(108, l.panelW / 3));
                addRenderableWidget(Button.builder(Component.literal("До тотема"), b -> send("teleport_guild", "", ""))
                        .bounds(x0, footerY - 28, teleportW, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Вийти з гільдії"), b -> send("leave", "", ""))
                        .bounds(x0, footerY, 122, 22).build());
                x0 += 130;
                addRenderableWidget(Button.builder(Component.literal("Оновити"), b -> send("request", "", ""))
                        .bounds(x0, footerY, 94, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Таланти"), b -> openTalents())
                        .bounds(l.x + l.panelW - l.pad - 316, footerY, 100, 22).build());
                addRenderableWidget(Button.builder(HomeCraftGuildI18n.c("screen.homecraftguild.achievements.button"), b -> achievementsOpen = true)
                        .bounds(l.x + l.panelW - l.pad - 208, footerY, 100, 22).build());
                addRenderableWidget(Button.builder(Component.literal("Закрити"), b -> onClose())
                        .bounds(l.x + l.panelW - l.pad - 100, footerY, 100, 22).build());
            }
        }
    }

    private void openTalents() {
        if (this.minecraft != null) this.minecraft.setScreen(new GuildTalentScreen(this.snapshot));
    }

    private Layout layout() {
        Layout l = new Layout();
        l.panelW = Math.min(940, Math.max(300, this.width - 20));
        l.panelH = Math.min(640, Math.max(300, this.height - 20));
        l.x = (this.width - l.panelW) / 2;
        l.y = Math.max(5, (this.height - l.panelH) / 2);
        l.pad = this.width < 420 ? 10 : (this.width < 620 ? 14 : 20);
        l.contentY = l.y + 74;
        l.bottomY = l.y + l.panelH - 10;
        return l;
    }

    private void send(String action, String a, String b) {
        ClientPacketDistributor.sendToServer(new GuildActionPayload(action, a == null ? "" : a, b == null ? "" : b));
        if (!("request".equals(action) || "rank".equals(action) || "kick".equals(action) || "delete_golem".equals(action) || "teleport_golem_totem".equals(action) || "revive_golem".equals(action))) onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = mouseEventDouble(event, 0.0D, "x", "mouseX");
        double mouseY = mouseEventDouble(event, 1.0D, "y", "mouseY");
        int button = mouseEventButton(event);
        if (handleGuildMouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int delta = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
        if (delta == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        if (achievementsOpen) {
            if (achievementsArea.contains(mouseX, mouseY)) {
                int max = Math.max(0, view.achievements.size() - achievementsArea.visibleRows);
                achievementsScroll = clamp(achievementsScroll + delta, 0, max);
                return true;
            }
            if (achievementDetailsArea.contains(mouseX, mouseY)) {
                int max = Math.max(0, achievementDetailLines(selectedAchievement(), achievementDetailsArea.w).size() - visibleLineCount(achievementDetailsArea.h, 12));
                achievementDetailsScroll = clamp(achievementDetailsScroll + delta, 0, max);
                return true;
            }
            return true;
        }
        if (helpOpen && helpArea.contains(mouseX, mouseY)) {
            int max = Math.max(0, wrapHelpLines(helpLines, helpArea.w).size() - visibleLineCount(helpArea.h, 12));
            helpScroll = clamp(helpScroll + delta, 0, max);
            return true;
        }
        if (membersArea.contains(mouseX, mouseY)) {
            int max = Math.max(0, view.members.size() - membersArea.visibleRows);
            membersScroll = clamp(membersScroll + delta, 0, max);
            return true;
        }
        if (infoArea.contains(mouseX, mouseY)) {
            int max = Math.max(0, wrapLineEntries(buildInfoBuffLines(), infoArea.w).size() - visibleLineCount(infoArea.h, 12));
            infoScroll = clamp(infoScroll + delta, 0, max);
            return true;
        }
        if (territoriesArea.contains(mouseX, mouseY)) {
            int max = Math.max(0, view.territories.size() - territoriesArea.visibleRows);
            territoriesScroll = clamp(territoriesScroll + delta, 0, max);
            return true;
        }
        if (golemsArea.contains(mouseX, mouseY)) {
            int max = Math.max(0, view.golems.size() - golemsArea.visibleRows);
            golemsScroll = clamp(golemsScroll + delta, 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean handleGuildMouseClicked(double mouseX, double mouseY, int button) {
        Layout l = layout();
        int infoButtonSize = 18;
        int infoButtonX = l.x + l.panelW - l.pad - infoButtonSize;
        int infoButtonY = l.y + 10;

        if (kickConfirmMember != null) {
            if (inside(mouseX, mouseY, confirmYesX, confirmYesY, confirmYesW, confirmYesH)) {
                send("kick", kickConfirmMember.uuid, "");
                kickConfirmMember = null;
                return true;
            }
            if (inside(mouseX, mouseY, confirmNoX, confirmNoY, confirmNoW, confirmNoH)) {
                kickConfirmMember = null;
                return true;
            }
            if (!inside(mouseX, mouseY, confirmBoxX, confirmBoxY, confirmBoxW, confirmBoxH)) {
                kickConfirmMember = null;
                return true;
            }
            return true;
        }

        if (achievementsOpen) {
            if (inside(mouseX, mouseY, achievementsCloseX, achievementsCloseY, achievementsCloseW, achievementsCloseH)) {
                achievementsOpen = false;
                return true;
            }
            if (achievementsArea.contains(mouseX, mouseY)) {
                int rowH = 26;
                int index = achievementsScroll + (int)((mouseY - achievementsArea.y) / rowH);
                if (index >= 0 && index < view.achievements.size()) {
                    selectedAchievementIndex = index;
                    achievementDetailsScroll = 0;
                }
                return true;
            }
            if (achievementDetailsArea.contains(mouseX, mouseY)) return true;
            achievementsOpen = false;
            return true;
        }

        if (helpOpen) {
            if (inside(mouseX, mouseY, helpCloseX, helpCloseY, helpCloseW, helpCloseH)) {
                helpOpen = false;
                return true;
            }
            if (helpArea.contains(mouseX, mouseY)) return true;
            helpOpen = false;
            return true;
        }

        if (inside(mouseX, mouseY, infoButtonX, infoButtonY, infoButtonSize, infoButtonSize)) {
            helpOpen = true;
            return true;
        }

        if (!contextActions.isEmpty()) {
            for (ContextAction action : contextActions) {
                if (inside(mouseX, mouseY, action.x, action.y, action.w, action.h)) {
                    send(action.action, action.target, action.extra);
                    contextActions.clear();
                    contextMember = null;
                    return true;
                }
            }
            if (!inside(mouseX, mouseY, contextX, contextY, contextW, contextH)) {
                contextActions.clear();
                contextMember = null;
            }
        }

        for (MemberAction action : memberActions) {
            if (inside(mouseX, mouseY, action.x, action.y, action.w, action.h)) {
                if ("role_menu".equals(action.kind)) {
                    openContext(action.member, action.x, action.y + action.h + 2);
                } else if ("kick".equals(action.kind)) {
                    kickConfirmMember = action.member;
                }
                return true;
            }
        }

        if (!view.readOnly && view.isGuildmaster) {
            for (GolemAction action : golemActions) {
                if (inside(mouseX, mouseY, action.x, action.y, action.w, action.h)) {
                    send(action.action, action.uuid, "");
                    return true;
                }
            }
        }

        for (MemberRow row : memberRows) {
            if (inside(mouseX, mouseY, row.x, row.y, row.w, row.h)) {
                selectedMember = row;
                return true;
            }
        }
        return false;
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

    private void openContext(MemberRow row, int x, int y) {
        contextActions.clear();
        contextMember = row;
        contextW = 178;
        contextH = 108;
        contextX = Math.min(Math.max(8, x), this.width - contextW - 8);
        contextY = Math.min(Math.max(8, y), this.height - contextH - 8);
        int ry = contextY + 24;
        addContext("Будівельник", "rank", row.uuid, "BUILDER", row.role.equalsIgnoreCase("BUILDER"), ry); ry += 20;
        addContext("Завгосп", "rank", row.uuid, "QUARTERMASTER", row.role.equalsIgnoreCase("QUARTERMASTER"), ry); ry += 20;
        addContext("Воїн", "rank", row.uuid, "WARRIOR", row.role.equalsIgnoreCase("WARRIOR"), ry); ry += 20;
        addContext("Фермер", "rank", row.uuid, "FARMER", row.role.equalsIgnoreCase("FARMER"), ry);
    }

    private void addContext(String label, String action, String target, String extra, boolean active, int y) {
        ContextAction a = new ContextAction();
        a.label = label;
        a.action = action;
        a.target = target;
        a.extra = extra;
        a.active = active;
        a.x = contextX + 7;
        a.y = y;
        a.w = contextW - 14;
        a.h = 18;
        contextActions.add(a);
    }

    private boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xAA05070D);
        Layout l = layout();
        graphics.fill(l.x, l.y, l.x + l.panelW, l.y + l.panelH, 0xF0101119);
        graphics.fill(l.x, l.y, l.x + l.panelW, l.y + 3, 0xFFFFA914);

        if (view.mode.equals("INVITES")) {
            renderInvites(graphics, mouseX, mouseY, l);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        renderGuild(graphics, mouseX, mouseY, l);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderHelpOverlay(graphics, mouseX, mouseY);
        renderAchievementsOverlay(graphics, mouseX, mouseY);
    }

    private void renderInvites(GuiGraphics graphics, int mouseX, int mouseY, Layout l) {
        graphics.drawCenteredString(this.font, "Запрошення до гільдій", this.width / 2, l.y + 16, 0xFFFFF3DC);
        graphics.drawCenteredString(this.font, "Тут показані лише гільдії, які запросили тебе", this.width / 2, l.y + 34, 0xFF9AA4B2);
        if (view.invites.isEmpty()) {
            graphics.drawCenteredString(this.font, "У вас немає запрошень до гільдій", this.width / 2, l.contentY + 46, 0xFFD8DEE9);
        } else {
            graphics.drawString(this.font, "Оберіть гільдію:", l.x + l.pad, l.contentY + 12, 0xFFFFD99A, false);
            for (InviteRow row : inviteRows) {
                graphics.fill(row.x, row.y, row.x + row.w, row.y + row.h, 0x70202A34);
                graphics.drawString(this.font, trim(row.guildName, Math.max(10, row.w / 7 - 28)), row.x + 8, row.y + 5, 0xFFFFF3DC, false);
                graphics.drawString(this.font, "Запросив: " + emptyDash(row.invitedBy), row.x + 8, row.y + 19, 0xFF9AA4B2, false);
            }
        }
    }

    private void renderGuild(GuiGraphics graphics, int mouseX, int mouseY, Layout l) {
        tooltipAreas.clear();
        String title = "Рівень " + view.guildLevel + " · " + trim(view.guildName, Math.max(14, (l.panelW - l.pad * 2) / 7));
        graphics.drawCenteredString(this.font, title, this.width / 2, l.y + 12, 0xFFFFF3DC);
        drawInfoButton(graphics, mouseX, mouseY, l);
        drawXpBar(graphics, l.x + l.pad, l.y + 30, l.panelW - l.pad * 2, 10);
        int memberColor = view.members.size() >= view.memberLimit ? 0xFFFF7777 : 0xFFD8DEE9;
        String roleLine = view.readOnly ? "Перегляд складу" : "Роль: " + view.roleLabel;
        graphics.drawString(this.font, roleLine + " · учасники " + view.members.size() + "/" + view.memberLimit, l.x + l.pad, l.y + 46, memberColor, false);
        if (!view.readOnly && view.isGuildmaster && l.panelW > 620) {
            graphics.drawString(this.font, "Кнопки біля учасника: роль або виключення", l.x + l.panelW - l.pad - 252, l.y + 46, 0xFF9AA4B2, false);
        }

        int fullW = l.panelW - l.pad * 2;
        boolean compact = fullW < 620 || l.panelH < 470;
        int gap = compact ? 8 : 14;
        int leftX = l.x + l.pad;
        int rightX = compact ? leftX : leftX + Math.max(260, (fullW - gap) / 2) + gap;
        int leftW = compact ? fullW : Math.max(260, (fullW - gap) / 2);
        int rightW = compact ? fullW : fullW - leftW - gap;
        int footerReserve = l.panelW < 560 ? (view.isGuildmaster ? 118 : 88) : (view.readOnly ? 40 : ((view.isGuildmaster || view.guildLevel >= 3) ? 68 : 40));
        int maxBottom = l.bottomY - footerReserve;
        int y = l.contentY;

        if (compact) {
            y = renderMembers(graphics, mouseX, mouseY, leftX, y, leftW, Math.min(maxBottom, y + 146));
            y += 10;
            int sectionGap = 10;
            int sectionHeight = Math.max(64, (maxBottom - y - sectionGap * 2) / 3);
            int infoBottom = Math.min(maxBottom, y + sectionHeight);
            y = renderInfoAndBuffs(graphics, leftX, y, leftW, infoBottom);
            y += sectionGap;
            int territoriesBottom = Math.min(maxBottom, y + sectionHeight);
            renderTerritories(graphics, leftX, y, leftW, territoriesBottom);
            y = territoriesBottom + sectionGap;
            renderGolems(graphics, mouseX, mouseY, leftX, y, leftW, maxBottom);
        } else {
            int leftBottom = maxBottom;
            renderMembers(graphics, mouseX, mouseY, leftX, y, leftW, leftBottom);
            int sectionGap = 16;
            int sectionHeight = Math.max(74, (maxBottom - y - sectionGap * 2) / 3);
            int infoBottom = Math.min(maxBottom, y + sectionHeight);
            renderInfoAndBuffs(graphics, rightX, y, rightW, infoBottom);
            int territoryY = infoBottom + sectionGap;
            int territoryBottom = Math.min(maxBottom, territoryY + sectionHeight);
            renderTerritories(graphics, rightX, territoryY, rightW, territoryBottom);
            int golemY = territoryBottom + sectionGap;
            renderGolems(graphics, mouseX, mouseY, rightX, golemY, rightW, maxBottom);
        }

        if (!view.readOnly && view.isGuildmaster) {
            int inviteLabelY = l.panelW < 560 ? l.bottomY - 114 : l.bottomY - 58;
            graphics.drawString(this.font, view.members.size() >= view.memberLimit ? "Гільдія заповнена" : "Запросити гравця", l.x + l.pad, inviteLabelY - 11, view.members.size() >= view.memberLimit ? 0xFFFF7777 : 0xFFFFD99A, false);
        }
        renderContextMenu(graphics, mouseX, mouseY);
        renderKickConfirm(graphics, mouseX, mouseY);
        renderHoveredTooltip(graphics, mouseX, mouseY);
    }

    private void drawInfoButton(GuiGraphics graphics, int mouseX, int mouseY, Layout l) {
        int s = 18;
        int x = l.x + l.panelW - l.pad - s;
        int y = l.y + 10;
        boolean hover = inside(mouseX, mouseY, x, y, s, s);
        graphics.fill(x, y, x + s, y + s, hover ? 0xFF4E5661 : 0xFF313842);
        graphics.drawCenteredString(this.font, "i", x + s / 2, y + 5, 0xFFFFF3DC);
        tooltipAreas.add(new TooltipArea(x, y, s, s, java.util.List.of("Інформація", "Повний опис системи гільдії, ролей, бафів, територій і големів.")));
    }

    private void drawXpBar(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF252B35);
        int levelSize = Math.max(0, view.guildXpLevelSize);
        int fill = levelSize <= 0 ? w : Math.max(0, Math.min(w, (int) Math.round(w * (view.guildXpInLevel / (double) Math.max(1, levelSize)))));
        graphics.fill(x, y, x + fill, y + h, 0xFFFFA914);
        String text = levelSize <= 0 ? "Максимальний рівень" : view.guildXpInLevel + "/" + levelSize + " досвіду до рівня " + (view.guildLevel + 1);
        graphics.drawCenteredString(this.font, text, x + w / 2, y + 1, 0xFFFFFFFF);
    }

    private int renderMembers(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int bottom) {
        drawSection(graphics, x, y, w, Math.max(56, bottom - y), "Учасники " + view.members.size() + "/" + view.memberLimit);
        memberRows.clear();
        memberActions.clear();
        int innerX = x + 6;
        int innerY = y + 8;
        int innerW = w - 12;
        int innerH = Math.max(20, bottom - y - 14);
        int rowH = w < 360 ? 38 : 34;
        int visibleRows = Math.max(1, innerH / (rowH + 3));
        membersArea.set(innerX, innerY, innerW, innerH, visibleRows);
        membersScroll = clamp(membersScroll, 0, Math.max(0, view.members.size() - visibleRows));

        for (int visibleIndex = 0; visibleIndex < visibleRows; visibleIndex++) {
            int i = membersScroll + visibleIndex;
            if (i >= view.members.size()) break;
            MemberRow row = view.members.get(i);
            int ry = innerY + visibleIndex * (rowH + 3);
            row.x = innerX;
            row.y = ry;
            row.w = innerW;
            row.h = rowH;
            memberRows.add(row);
            int bg = row == selectedMember ? 0x905A3A13 : 0x55202A34;
            graphics.fill(row.x, row.y, row.x + row.w, row.y + row.h, bg);
            graphics.fill(row.x, row.y, row.x + 2, row.y + row.h, row.guildmaster ? 0xFFFFD99A : (row.online ? 0xFF7FE39A : 0xFF4A5564));
            String crown = row.guildmaster ? "★ " : "";
            String online = row.online ? "●" : "○";

            int roleButtonW = (!view.readOnly && view.isGuildmaster && !row.guildmaster) ? 42 : 0;
            int kickButtonW = (!view.readOnly && view.isGuildmaster && !row.guildmaster) ? 34 : 0;
            int actionsW = roleButtonW + kickButtonW + (roleButtonW > 0 && kickButtonW > 0 ? 6 : 0);
            int roleW = Math.min(122, Math.max(80, row.w / 3));
            int nameW = Math.max(60, row.w - roleW - actionsW - 26);

            graphics.drawString(this.font, trim(crown + row.name, Math.max(8, nameW / 6)), row.x + 8, row.y + 4, row.guildmaster ? 0xFFFFD99A : 0xFFD8DEE9, false);
            graphics.drawString(this.font, trim(row.roleLabel, 14) + " " + online, row.x + row.w - roleW - actionsW - 4, row.y + 4, row.online ? 0xFF7FE39A : 0xFF7D8796, false);
            String bedLine = "Ліжко: " + (row.bedCoords == null || row.bedCoords.isBlank() ? "—" : row.bedCoords);
            int bedColor = "—".equals(row.bedCoords) ? 0xFF7D8796 : 0xFF9AFFB4;
            graphics.drawString(this.font, trim(bedLine, Math.max(10, (row.w - actionsW - 18) / 6)), row.x + 8, row.y + 17, bedColor, false);
            tooltipAreas.add(new TooltipArea(row.x + 8, row.y + 16, Math.max(40, row.w - actionsW - 16), 12, java.util.List.of("Координати ліжка", "Показує закріплене ліжко учасника на території гільдії.")));

            if (roleButtonW > 0) {
                int bx = row.x + row.w - actionsW;
                drawMiniButton(graphics, mouseX, mouseY, bx, row.y + 8, roleButtonW, 16, "Роль");
                tooltipAreas.add(new TooltipArea(bx, row.y + 8, roleButtonW, 16, splitTooltip("Змінити роль\n" + roleTooltip(row.role))));
                MemberAction roleAction = new MemberAction();
                roleAction.kind = "role_menu";
                roleAction.member = row;
                roleAction.x = bx;
                roleAction.y = row.y + 8;
                roleAction.w = roleButtonW;
                roleAction.h = 16;
                memberActions.add(roleAction);

                int kx = bx + roleButtonW + 6;
                drawMiniButton(graphics, mouseX, mouseY, kx, row.y + 8, kickButtonW, 16, "✕");
                tooltipAreas.add(new TooltipArea(kx, row.y + 8, kickButtonW, 16, java.util.List.of("Виключити з гільдії", "Потрібне підтвердження перед виключенням гравця.")));
                MemberAction kickAction = new MemberAction();
                kickAction.kind = "kick";
                kickAction.member = row;
                kickAction.x = kx;
                kickAction.y = row.y + 8;
                kickAction.w = kickButtonW;
                kickAction.h = 16;
                memberActions.add(kickAction);
            }
        }

        if (view.members.size() > visibleRows) {
            graphics.drawString(this.font, "Показано: " + (membersScroll + 1) + "-" + Math.min(view.members.size(), membersScroll + visibleRows) + " з " + view.members.size(), x + 8, bottom - 11, 0xFF9AA4B2, false);
        }
        return bottom;
    }

    private int renderInfoAndBuffs(GuiGraphics graphics, int x, int y, int w, int bottom) {
        drawSection(graphics, x, y, w, Math.max(56, bottom - y), "Активні бафи гільдії");
        int innerX = x + 8;
        int innerY = y + 8;
        int innerW = w - 16;
        int innerH = Math.max(18, bottom - y - 14);
        List<LineEntry> lines = wrapLineEntries(buildInfoBuffLines(), innerW);
        int visibleLines = visibleLineCount(innerH, 12);
        infoArea.set(innerX, innerY, innerW, innerH, visibleLines);
        infoScroll = clamp(infoScroll, 0, Math.max(0, lines.size() - visibleLines));
        int drawY = innerY;
        for (int i = infoScroll; i < lines.size() && drawY <= innerY + innerH - 10; i++) {
            LineEntry line = lines.get(i);
            graphics.drawString(this.font, line.text, innerX, drawY, line.color, false);
            if (line.tooltip != null && !line.tooltip.isBlank()) tooltipAreas.add(new TooltipArea(innerX, drawY, innerW, 11, splitTooltip(line.tooltip)));
            drawY += 12;
        }
        if (lines.size() > visibleLines) {
            graphics.drawString(this.font, "Показано: " + (infoScroll + 1) + "-" + Math.min(lines.size(), infoScroll + visibleLines) + " з " + lines.size(), x + 8, bottom - 11, 0xFF9AA4B2, false);
        }
        return bottom;
    }

    private List<LineEntry> buildInfoBuffLines() {
        List<LineEntry> lines = new ArrayList<>();
        lines.add(new LineEntry("Активні бафи учасників", 0xFFFFD99A, "Бонуси учасників активні за членство в гільдії. Територія не потрібна для XP, зіль, шкоди, броні, швидкості, стрибка або постійних vanilla-ефектів."));
        boolean memberBuffsActive = true;
        addActiveBuff(lines, "Досвід гравця", "+" + view.xpBonus + "%", memberBuffsActive && view.xpBonus > 0, 0xFF7BD7FF, "Базовий бонус + талантові покращення. Бонус досвіду не повинен повертатися до старих завищених значень без талантів.");
        addActiveBuff(lines, "Тривалість корисних зіль", "+" + view.potionBonus + "%", memberBuffsActive && view.potionBonus > 0, 0xFFB58CFF, "Працює через серверне продовження тривалості ефектів, а не через клієнтський розрахунок.");
        addActiveBuff(lines, "Шкода зброєю", "+" + view.damageBonus + "%", memberBuffsActive && view.damageBonus > 0, 0xFFFFB347, "Працює тільки на справжню зброю: sword/axe/mace/trident/bow/crossbow і підтримані modded weapon suffixes.");
        addActiveBuff(lines, "Броня", "+" + view.armorBonus + "%", memberBuffsActive && view.armorBonus > 0, 0xFF6BA8FF, "Працює тільки для реально вдягненої броні в armor slot.");
        addActiveBuff(lines, "Швидкість руху", "+" + view.speedBonus + "%", memberBuffsActive && view.speedBonus > 0, 0xFF9AFFB4, "Дається тільки талантами гілки учасників.");
        addActiveBuff(lines, "Стрибок", "+" + view.jumpBonus, memberBuffsActive && !"0".equals(view.jumpBonus), 0xFFB7A7FF, "Легкий бонус до сили стрибка, не політ.");
        addActiveBuff(lines, "Стійкість проти hostile mobs", "-" + view.damageReductionBonus + "% отриманої шкоди", memberBuffsActive && view.damageReductionBonus > 0, 0xFFFFD38A, "Не застосовується до PvP, якщо PvP-логіка не готова.");
        addActiveBuff(lines, "Нічне бачення", "безкінечний ефект", memberBuffsActive && view.nightVision, 0xFF86FFB7, "Окремий гільдійний marker effect + vanilla Night Vision.");
        addActiveBuff(lines, "Подих глибин", "безкінечний ефект", memberBuffsActive && view.waterBreathing, 0xFF5FD7FF, "Окремий гільдійний marker effect + vanilla Water Breathing.");
        addActiveBuff(lines, "Вогняний захист", "безкінечний ефект", memberBuffsActive && view.fireResistance, 0xFFFF8A3D, "Окремий гільдійний marker effect + vanilla Fire Resistance.");
        addActiveBuff(lines, "Досвід гільдії з мобів", "+" + view.mobKillGuildXpBonus + "%", view.mobKillGuildXpBonus > 0, 0xFFFFD66B, "Рівневий бонус до досвіду гільдії з убивств мобів.");

        lines.add(new LineEntry("", 0xFFFFFFFF, ""));
        lines.add(new LineEntry("Активні бафи големів", 0xFFFFD99A, "Показані тільки talent-based бонуси големів. Статичні 4x/6x HP і 1.5x/1.75x damage лишаються базою гільдійних големів."));
        addActiveBuff(lines, "Max HP големів", "+" + view.golemTalentHealth + "%", view.golemTalentHealth > 0, 0xFF9AFFB4, "Додається поверх статичних гільдійних множників, не поверх vanilla 100 HP.");
        addActiveBuff(lines, "Шкода големів", "+" + view.golemTalentDamage + "%", view.golemTalentDamage > 0, 0xFFFFB347, "Додається поверх staticDamageMultiplier звичайного/елітного голема.");
        addActiveBuff(lines, "Швидкість големів", "+" + view.golemTalentSpeed + "%", view.golemTalentSpeed > 0, 0xFFBEE7FF, "Додається як talent speed modifier для гільдійних големів.");
        addActiveBuff(lines, "Лікування біля тотема", "+" + view.golemTalentHealing + "%", view.golemTalentHealing > 0, 0xFF86FFB7, "Підсилює швидкість лікування біля тотема.");
        addActiveBuff(lines, "Позабойова регенерація", "1 HP кожні " + view.golemOocRegenSeconds + " сек.", view.golemOocRegenSeconds > 0, 0xFF86FFB7, "Працює тільки поза боєм, на території гільдії, зі stagger по UUID.");
        addActiveBuff(lines, "Безпечні маршрути", "+" + view.golemRouteEfficiency + "%", view.golemRouteEfficiency > 0, 0xFFB7A7FF, "Зменшує fallback cooldown і підвищує шанс safe detour без глобальних scan-сплесків.");
        addActiveBuff(lines, "Перехоплення загрози", "активно", view.golemInterceptTalent, 0xFFFFD38A, "Големи швидше кидають patrol task і реагують на напад біля учасників.");
        addActiveBuff(lines, "Нічна варта", "активно", view.golemNightWatchTalent, 0xFFBEE7FF, "Покращує нічне патрулювання поруч з учасниками.");
        addActiveBuff(lines, "Командирська ланка", "активно", view.golemEliteCommandTalent, 0xFFFFD99A, "Елітний голем краще координує route reservation і розподіл точок.");
        addActiveBuff(lines, "Нищівна сила", "+" + view.golemCrushingForce + "% проти hostile mobs", view.golemCrushingForce > 0, 0xFFFF7777, "Не застосовується до friendly players і учасників гільдії.");

        boolean onlyDefaults = lines.size() <= 4;
        if (onlyDefaults) lines.add(new LineEntry("Поки немає активних талантових бафів. Базові бонуси вже показані вище.", 0xFF9AA4B2, "Відкрий дерево талантів і вибери напрям розвитку."));
        return lines;
    }

    private void addActiveBuff(List<LineEntry> lines, String name, String value, boolean active, int color, String tooltip) {
        if (!active) return;
        lines.add(new LineEntry("• " + name + ": " + value, color, tooltip));
    }

    private int countOrdinaryGolemsInView() {
        int count = 0;
        for (GolemRow row : view.golems) if (row != null && !row.elite) count++;
        return count;
    }

    private int countEliteGolemsInView() {
        int count = 0;
        for (GolemRow row : view.golems) if (row != null && row.elite) count++;
        return count;
    }

    private List<LineEntry> wrapLineEntries(List<LineEntry> source, int maxWidthPx) {
        List<LineEntry> out = new ArrayList<>();
        if (source == null) return out;
        for (LineEntry entry : source) {
            if (entry == null) continue;
            if (entry.text == null || entry.text.isBlank()) {
                out.add(new LineEntry("", entry.color, entry.tooltip));
                continue;
            }
            List<String> wrapped = wrapText(entry.text, maxWidthPx);
            if (wrapped.isEmpty()) out.add(entry);
            else for (String line : wrapped) out.add(new LineEntry(line, entry.color, entry.tooltip));
        }
        return out;
    }

    private void renderTerritories(GuiGraphics graphics, int x, int y, int w, int bottom) {
        drawSection(graphics, x, y, w, Math.max(44, bottom - y), "Території " + view.territories.size() + "/" + view.territoryLimit);
        int innerX = x + 8;
        int innerY = y + 8;
        int innerH = Math.max(18, bottom - y - 14);
        int rowH = 24;
        int visibleRows = Math.max(1, innerH / rowH);
        territoriesArea.set(innerX, innerY, w - 16, innerH, visibleRows);

        if (view.territories.isEmpty()) {
            graphics.drawString(this.font, "Гільдійних територій немає", innerX, innerY + 4, 0xFF9AA4B2, false);
            return;
        }

        territoriesScroll = clamp(territoriesScroll, 0, Math.max(0, view.territories.size() - visibleRows));
        int rowY = innerY;
        for (int visibleIndex = 0; visibleIndex < visibleRows; visibleIndex++) {
            int i = territoriesScroll + visibleIndex;
            if (i >= view.territories.size()) break;
            TerritoryRow t = view.territories.get(i);
            graphics.drawString(this.font, trim("#" + (i + 1) + " " + t.dimension + " · центр " + t.x + ", " + t.y + ", " + t.z, Math.max(12, (w - 16) / 6)), innerX, rowY, 0xFFD8DEE9, false);
            graphics.drawString(this.font, trim("межі X " + t.minX + ".." + t.maxX + " · Z " + t.minZ + ".." + t.maxZ + " · " + t.size + "x" + t.size + (t.spawnClipped ? " · біля спавну" : ""), Math.max(12, (w - 16) / 6)), innerX, rowY + 11, 0xFF9AA4B2, false);
            rowY += rowH;
        }
        if (view.territories.size() > visibleRows) graphics.drawString(this.font, "Показано: " + (territoriesScroll + 1) + "-" + Math.min(view.territories.size(), territoriesScroll + visibleRows) + " з " + view.territories.size(), x + 8, bottom - 11, 0xFF9AA4B2, false);
    }

    private void renderGolems(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int bottom) {
        drawSection(graphics, x, y, w, Math.max(44, bottom - y), "Големи " + view.golems.size() + "/" + view.maxGolems + " · звичайні " + view.maxOrdinaryGolems + " · елітні " + view.maxEliteGolems);
        golemActions.clear();
        int innerX = x + 8;
        int innerY = y + 8;
        int innerH = Math.max(18, bottom - y - 14);
        int rowH = w < 360 ? 42 : 36;
        int visibleRows = Math.max(1, innerH / rowH);
        golemsArea.set(innerX, innerY, w - 16, innerH, visibleRows);

        if (view.golems.isEmpty()) {
            graphics.drawString(this.font, "Найнятих големів немає", innerX, innerY + 4, 0xFF9AA4B2, false);
            return;
        }

        golemsScroll = clamp(golemsScroll, 0, Math.max(0, view.golems.size() - visibleRows));
        int rowY = innerY;
        for (int visibleIndex = 0; visibleIndex < visibleRows; visibleIndex++) {
            int i = golemsScroll + visibleIndex;
            if (i >= view.golems.size()) break;
            GolemRow golem = view.golems.get(i);
            boolean canManage = !view.readOnly && view.isGuildmaster;
            int actionsW = canManage ? (golem.dead ? 160 : 96) : 0;
            int textMax = Math.max(10, (w - actionsW - 20) / 6);
            String label = golem.name + " · " + (golem.elite ? "елітний захисник" : "звичайний захисник");
            int nameColor = golem.dead ? 0xFFFF7777 : (golem.elite ? 0xFFFFD99A : 0xFFD8DEE9);
            graphics.drawString(this.font, trim(label, textMax), innerX, rowY, nameColor, false);
            String health = golem.hpText == null || golem.hpText.isBlank() ? "Здоров’я ?" : "Здоров’я " + golem.hpText;
            graphics.drawString(this.font, trim(health + " · " + golem.status, textMax), innerX, rowY + 12, golem.dead ? 0xFFFF9A9A : 0xFF9AA4B2, false);
            graphics.drawString(this.font, trim("Місце: " + golem.x + ", " + golem.y + ", " + golem.z, textMax), innerX, rowY + 23, 0xFF7D8796, false);
            if (canManage) {
                int bx = x + w - actionsW - 8;
                int by = rowY + 8;
                if (golem.dead) {
                    drawGolemActionButton(graphics, mouseX, mouseY, bx, by, 96, 18, "Відродити");
                    tooltipAreas.add(new TooltipArea(bx, by, 96, 18, java.util.List.of("Відродити голема", "Голем повернеться біля безпечного тотема.")));
                    addGolemAction(golem.uuid, "revive_golem", bx, by, 96, 18);

                    int dx = bx + 104;
                    drawGolemDeleteButton(graphics, mouseX, mouseY, dx, by, 54, 18);
                    tooltipAreas.add(new TooltipArea(dx, by, 54, 18, java.util.List.of("Видалити голема", "Повністю прибирає його зі списку гільдії і чистить runtime-стан.")));
                    addGolemAction(golem.uuid, "delete_golem", dx, by, 54, 18);
                } else {
                    drawGolemActionButton(graphics, mouseX, mouseY, bx, by, 32, 18, "До");
                    tooltipAreas.add(new TooltipArea(bx, by, 32, 18, java.util.List.of("До тотема", "Повертає голема біля найближчого тотема гільдії.")));
                    addGolemAction(golem.uuid, "teleport_golem_totem", bx, by, 32, 18);

                    int dx = bx + 38;
                    drawGolemDeleteButton(graphics, mouseX, mouseY, dx, by, 54, 18);
                    tooltipAreas.add(new TooltipArea(dx, by, 54, 18, java.util.List.of("Видалити голема", "Повністю прибирає його зі списку гільдії і чистить runtime-стан.")));
                    addGolemAction(golem.uuid, "delete_golem", dx, by, 54, 18);
                }
            }
            rowY += rowH;
        }
        if (view.golems.size() > visibleRows) graphics.drawString(this.font, "Показано: " + (golemsScroll + 1) + "-" + Math.min(view.golems.size(), golemsScroll + visibleRows) + " з " + view.golems.size(), x + 8, bottom - 11, 0xFF9AA4B2, false);
    }

    private void drawGolemActionButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h, String label) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        graphics.fill(x, y, x + w, y + h, hover ? 0xFF4E5661 : 0xFF313842);
        graphics.drawCenteredString(this.font, label, x + w / 2, y + 5, 0xFFFFFFFF);
    }

    private void drawGolemDeleteButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        graphics.fill(x, y, x + w, y + h, hover ? 0xFF7A2B2B : 0xFF542222);
        graphics.drawCenteredString(this.font, "Видал.", x + w / 2, y + 5, 0xFFFFD8D8);
    }

    private void addGolemAction(String uuid, String actionName, int x, int y, int w, int h) {
        GolemAction action = new GolemAction();
        action.uuid = uuid;
        action.action = actionName;
        action.x = x;
        action.y = y;
        action.w = w;
        action.h = h;
        golemActions.add(action);
    }

    private void drawMiniButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h, String label) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        graphics.fill(x, y, x + w, y + h, hover ? 0xFF4E5661 : 0xFF313842);
        graphics.drawCenteredString(this.font, label, x + w / 2, y + 4, 0xFFFFFFFF);
    }

    private void drawSection(GuiGraphics graphics, int x, int y, int w, int h, String title) {
        graphics.drawString(this.font, title, x, y - 12, 0xFFFFD99A, false);
        graphics.fill(x, y, x + w, y + Math.max(20, h), 0x60202A34);
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        if (contextActions.isEmpty() || contextMember == null) return;
        graphics.fill(contextX, contextY, contextX + contextW, contextY + contextH, 0xF0181B22);
        graphics.fill(contextX, contextY, contextX + contextW, contextY + 2, 0xFFFFA914);
        graphics.drawString(this.font, "Роль: " + trim(contextMember.name, 16), contextX + 7, contextY + 8, 0xFFFFF3DC, false);
        for (ContextAction action : contextActions) {
            boolean hover = inside(mouseX, mouseY, action.x, action.y, action.w, action.h);
            int bg = action.active ? 0xFF6C5414 : (hover ? 0xFF4E5661 : 0xFF313842);
            graphics.fill(action.x, action.y, action.x + action.w, action.y + action.h, bg);
            graphics.drawString(this.font, action.label, action.x + 5, action.y + 5, 0xFFFFFFFF, false);
        }
    }

    private void renderHelpOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!helpOpen) return;
        int w = Math.min(520, this.width - 40);
        int h = Math.min(360, this.height - 40);
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        graphics.fill(0, 0, this.width, this.height, 0xAA000000);
        graphics.fill(x, y, x + w, y + h, 0xF0151920);
        graphics.fill(x, y, x + w, y + 3, 0xFFFFA914);
        graphics.drawString(this.font, "Інформація про гільдію", x + 12, y + 10, 0xFFFFF3DC, false);

        helpCloseW = 20;
        helpCloseH = 18;
        helpCloseX = x + w - 28;
        helpCloseY = y + 8;
        graphics.fill(helpCloseX, helpCloseY, helpCloseX + helpCloseW, helpCloseY + helpCloseH, inside(mouseX, mouseY, helpCloseX, helpCloseY, helpCloseW, helpCloseH) ? 0xFF7A2B2B : 0xFF542222);
        graphics.drawCenteredString(this.font, "✕", helpCloseX + helpCloseW / 2, helpCloseY + 5, 0xFFFFFFFF);

        int innerX = x + 12;
        int innerY = y + 30;
        int innerW = w - 24;
        int innerH = h - 42;
        List<String> wrappedHelpLines = wrapHelpLines(helpLines, innerW);
        int visibleLines = visibleLineCount(innerH, 12);
        helpArea.set(innerX, innerY, innerW, innerH, visibleLines);
        helpScroll = clamp(helpScroll, 0, Math.max(0, wrappedHelpLines.size() - visibleLines));
        int drawY = innerY;
        for (int i = helpScroll; i < wrappedHelpLines.size() && drawY <= innerY + innerH - 10; i++) {
            String line = wrappedHelpLines.get(i);
            int color = line.endsWith(":") ? 0xFFFFD99A : 0xFFD8DEE9;
            graphics.drawString(this.font, line, innerX, drawY, color, false);
            drawY += 12;
        }
        if (wrappedHelpLines.size() > visibleLines) {
            graphics.drawString(this.font, "Показано: " + (helpScroll + 1) + "-" + Math.min(wrappedHelpLines.size(), helpScroll + visibleLines) + " з " + wrappedHelpLines.size(), x + 12, y + h - 12, 0xFF9AA4B2, false);
        }
    }

    private void renderAchievementsOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!achievementsOpen) return;
        int w = Math.min(720, this.width - 36);
        int h = Math.min(420, this.height - 36);
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        graphics.fill(0, 0, this.width, this.height, 0xAA000000);
        graphics.fill(x, y, x + w, y + h, 0xF0151920);
        graphics.fill(x, y, x + w, y + 3, 0xFFFFA914);
        graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.title"), x + 12, y + 10, 0xFFFFF3DC, false);
        long unlocked = view.achievements.stream().filter(a -> a.unlocked).count();
        graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.unlocked_count", unlocked, view.achievements.size()), x + 116, y + 10, 0xFF9AA4B2, false);

        achievementsCloseW = 20;
        achievementsCloseH = 18;
        achievementsCloseX = x + w - 28;
        achievementsCloseY = y + 8;
        graphics.fill(achievementsCloseX, achievementsCloseY, achievementsCloseX + achievementsCloseW, achievementsCloseY + achievementsCloseH, inside(mouseX, mouseY, achievementsCloseX, achievementsCloseY, achievementsCloseW, achievementsCloseH) ? 0xFF7A2B2B : 0xFF542222);
        graphics.drawCenteredString(this.font, "✕", achievementsCloseX + achievementsCloseW / 2, achievementsCloseY + 5, 0xFFFFFFFF);

        int listX = x + 12;
        int listY = y + 34;
        boolean narrow = w < 620 || h < 360;
        int innerH = h - 50;
        int listW = narrow ? w - 24 : Math.max(220, Math.min(280, w / 3));
        int listH = narrow ? Math.min(Math.max(92, innerH / 2), 150) : innerH;
        int detailsX = narrow ? listX : listX + listW + 12;
        int detailsY = narrow ? listY + listH + 8 : listY;
        int detailsW = narrow ? listW : w - listW - 36;
        int detailsH = narrow ? Math.max(84, innerH - listH - 8) : innerH;
        graphics.fill(listX, listY, listX + listW, listY + listH, 0x55202A34);
        graphics.fill(detailsX, detailsY, detailsX + detailsW, detailsY + detailsH, 0x55202A34);

        int rowH = 26;
        int visibleRows = Math.max(1, listH / rowH);
        achievementsArea.set(listX, listY, listW, listH, visibleRows);
        achievementsScroll = clamp(achievementsScroll, 0, Math.max(0, view.achievements.size() - visibleRows));
        if (view.achievements.isEmpty()) {
            graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.not_loaded"), listX + 8, listY + 8, 0xFF9AA4B2, false);
        }
        for (int visibleIndex = 0; visibleIndex < visibleRows; visibleIndex++) {
            int i = achievementsScroll + visibleIndex;
            if (i >= view.achievements.size()) break;
            AchievementRow a = view.achievements.get(i);
            int ry = listY + visibleIndex * rowH;
            boolean selected = i == selectedAchievementIndex;
            boolean hover = inside(mouseX, mouseY, listX, ry, listW, rowH);
            int bg = selected ? 0x905A3A13 : (hover ? 0x70404A56 : 0x33202A34);
            graphics.fill(listX, ry, listX + listW, ry + rowH - 2, bg);
            graphics.fill(listX, ry, listX + 3, ry + rowH - 2, a.unlocked ? 0xFF7FE39A : 0xFF4A5564);
            String mark = a.unlocked ? "✓ " : "□ ";
            String title = HomeCraftGuildI18n.achievementTitle(a.id, a.title);
            String category = HomeCraftGuildI18n.achievementCategory(a.id, a.category);
            graphics.drawString(this.font, trim(mark + title, Math.max(10, (listW - 16) / 6)), listX + 8, ry + 4, a.unlocked ? 0xFFFFF3DC : 0xFFD8DEE9, false);
            graphics.drawString(this.font, trim(HomeCraftGuildI18n.t("screen.homecraftguild.achievements.row_meta", category, a.xp), Math.max(10, (listW - 16) / 6)), listX + 8, ry + 15, 0xFF9AA4B2, false);
        }

        AchievementRow selected = selectedAchievement();
        int detailsInnerX = detailsX + 10;
        int detailsInnerY = detailsY + 10;
        int detailsInnerW = detailsW - 20;
        int detailsInnerH = detailsH - 18;
        List<String> lines = achievementDetailLines(selected, detailsInnerW);
        int visibleLines = visibleLineCount(detailsInnerH, 12);
        achievementDetailsArea.set(detailsInnerX, detailsInnerY, detailsInnerW, detailsInnerH, visibleLines);
        achievementDetailsScroll = clamp(achievementDetailsScroll, 0, Math.max(0, lines.size() - visibleLines));
        int dy = detailsInnerY;
        for (int i = achievementDetailsScroll; i < lines.size() && dy <= detailsInnerY + detailsInnerH - 10; i++) {
            String line = lines.get(i);
            int color = line.endsWith(":") ? 0xFFFFD99A : (line.startsWith("✓") ? 0xFF9AFFB4 : 0xFFD8DEE9);
            graphics.drawString(this.font, line, detailsInnerX, dy, color, false);
            dy += 12;
        }
        if (view.achievements.size() > visibleRows) graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.list_range", achievementsScroll + 1, Math.min(view.achievements.size(), achievementsScroll + visibleRows)), listX + 8, Math.min(y + h - 12, listY + listH - 12), 0xFF9AA4B2, false);
        if (lines.size() > visibleLines) graphics.drawString(this.font, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.description_range", achievementDetailsScroll + 1, Math.min(lines.size(), achievementDetailsScroll + visibleLines), lines.size()), detailsX + 8, Math.min(y + h - 12, detailsY + detailsH - 12), 0xFF9AA4B2, false);
    }

    private AchievementRow selectedAchievement() {
        if (view.achievements.isEmpty()) return null;
        selectedAchievementIndex = clamp(selectedAchievementIndex, 0, view.achievements.size() - 1);
        return view.achievements.get(selectedAchievementIndex);
    }

    private List<String> achievementDetailLines(AchievementRow a) {
        return achievementDetailLines(a, achievementDetailsArea.w);
    }

    private List<String> achievementDetailLines(AchievementRow a, int maxWidthPx) {
        int wrapWidth = Math.max(80, maxWidthPx <= 0 ? 320 : maxWidthPx);
        List<String> lines = new ArrayList<>();
        if (a == null) {
            addWrappedLines(lines, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.none_selected"), wrapWidth);
            return lines;
        }
        String title = HomeCraftGuildI18n.achievementTitle(a.id, a.title);
        String category = HomeCraftGuildI18n.achievementCategory(a.id, a.category);
        String description = HomeCraftGuildI18n.achievementDescription(a.id, a.description);
        String conditions = HomeCraftGuildI18n.achievementConditions(a.id, a.conditions);
        String reward = HomeCraftGuildI18n.achievementReward(a.id, a.reward == null || a.reward.isBlank() ? HomeCraftGuildI18n.t("screen.homecraftguild.achievements.default_reward", a.xp) : a.reward);
        addWrappedLines(lines, (a.unlocked ? "✓ " : "□ ") + title, wrapWidth);
        addWrappedLines(lines, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.category", category), wrapWidth);
        lines.add("");
        addAchievementSection(lines, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.description"), description, wrapWidth);
        lines.add("");
        addAchievementSection(lines, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.conditions"), conditions, wrapWidth);
        lines.add("");
        addAchievementSection(lines, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.reward"), reward, wrapWidth);
        lines.add("");
        lines.add(HomeCraftGuildI18n.t("screen.homecraftguild.achievements.status"));
        if (a.unlocked) {
            addWrappedLines(lines, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.status_unlocked") + (a.unlockedBy == null || a.unlockedBy.isBlank() ? "" : " · " + a.unlockedBy), wrapWidth);
            if (a.unlockedAt != null && !a.unlockedAt.isBlank()) addWrappedLines(lines, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.when", friendlyDate(a.unlockedAt)), wrapWidth);
        } else {
            addWrappedLines(lines, HomeCraftGuildI18n.t("screen.homecraftguild.achievements.status_locked"), wrapWidth);
        }
        return lines;
    }

    private void addAchievementSection(List<String> lines, String title, String body, int maxWidthPx) {
        lines.add(title);
        addWrappedLines(lines, body == null || body.isBlank() ? "—" : body, maxWidthPx);
    }

    private void addWrappedLines(List<String> lines, String text, int maxWidthPx) {
        if (text == null || text.isBlank()) {
            lines.add("");
            return;
        }
        for (String raw : text.split("\n", -1)) {
            String paragraph = raw.trim();
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            List<String> wrapped = wrapText(paragraph, maxWidthPx);
            if (wrapped.isEmpty()) lines.add(paragraph);
            else lines.addAll(wrapped);
        }
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

    private void renderKickConfirm(GuiGraphics graphics, int mouseX, int mouseY) {
        if (kickConfirmMember == null) return;
        confirmBoxW = Math.min(320, this.width - 28);
        confirmBoxH = 96;
        confirmBoxX = (this.width - confirmBoxW) / 2;
        confirmBoxY = (this.height - confirmBoxH) / 2;
        confirmYesW = 94; confirmYesH = 20; confirmNoW = 94; confirmNoH = 20;
        confirmYesX = confirmBoxX + 18; confirmYesY = confirmBoxY + 64;
        confirmNoX = confirmBoxX + confirmBoxW - confirmNoW - 18; confirmNoY = confirmYesY;
        graphics.fill(0, 0, this.width, this.height, 0x88000000);
        graphics.fill(confirmBoxX, confirmBoxY, confirmBoxX + confirmBoxW, confirmBoxY + confirmBoxH, 0xF0151920);
        graphics.fill(confirmBoxX, confirmBoxY, confirmBoxX + confirmBoxW, confirmBoxY + 3, 0xFFB84D4D);
        graphics.drawCenteredString(this.font, "Підтвердження виключення", confirmBoxX + confirmBoxW / 2, confirmBoxY + 10, 0xFFFFF3DC);
        graphics.drawCenteredString(this.font, "Виключити гравця " + trim(kickConfirmMember.name, 20) + " з гільдії?", confirmBoxX + confirmBoxW / 2, confirmBoxY + 34, 0xFFD8DEE9);
        drawMiniButton(graphics, mouseX, mouseY, confirmYesX, confirmYesY, confirmYesW, confirmYesH, "Так, виключити");
        drawMiniButton(graphics, mouseX, mouseY, confirmNoX, confirmNoY, confirmNoW, confirmNoH, "Скасувати");
    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (helpOpen || kickConfirmMember != null) return;
        for (TooltipArea area : tooltipAreas) {
            if (!inside(mouseX, mouseY, area.x, area.y, area.w, area.h)) continue;
            int max = 0;
            for (String line : area.lines) max = Math.max(max, this.font.width(line));
            int w = max + 10;
            int h = area.lines.size() * 12 + 6;
            int x = Math.min(this.width - w - 6, mouseX + 10);
            int y = Math.min(this.height - h - 6, mouseY + 10);
            graphics.fill(x, y, x + w, y + h, 0xF0151920);
            graphics.fill(x, y, x + w, y + 2, 0xFFFFA914);
            int ty = y + 4;
            for (String line : area.lines) {
                graphics.drawString(this.font, line, x + 5, ty, 0xFFFFF3DC, false);
                ty += 12;
            }
            return;
        }
    }

    private java.util.List<String> splitTooltip(String tooltip) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (tooltip == null || tooltip.isBlank()) return out;
        for (String raw : tooltip.split("\\n")) if (!raw.isBlank()) out.add(raw.trim());
        return out.isEmpty() ? java.util.List.of(tooltip) : out;
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

    private List<String> buildHelpLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Що таке гільдія:");
        lines.add("• Гільдія — це команда з власною територією, ролями, ліжками, розвитком і захисниками.");
        lines.add("• Основна ціль гільдії — створити безпечну базу, розвивати рівень, відкривати більші ліміти територій і купувати охоронців.");
        lines.add("• Гілдмайстер керує ролями, запрошеннями, територіями й покупкою големів. Будівельник допомагає з базою та ліжками.");
        lines.add("");
        lines.add("Гільдійний тотем:");
        lines.add("• Тотем створює захищену територію. Кілька суміжних тотемів однієї гільдії працюють як один простір.");
        lines.add("• Рецепт тотема: ABA / BEB / ABA. A — уламок аметисту, B — будь-який банер, E — смарагд.");
        lines.add("• Поставити тотем може Гілдмайстер. Він не ставиться на spawn, у чужій території, при перетині або якщо ліміт територій вичерпано.");
        lines.add("");
        lines.add("Ліжка гільдії:");
        lines.add("• Учасник має одне активне закріплене ліжко. Якщо вибрати нове — старе стане вільним.");
        lines.add("• Чуже закріплене ліжко не можна перезаписати або зробити своїм spawn.");
        lines.add("• Гілдмайстер і Будівельник можуть ставити багато вільних ліжок для учасників.");
        lines.add("");
        lines.add("Големи і ціни:");
        lines.add("• Звичайні големи: рівні 1-2 — 1 голем, 3-4 — 2, 5-6 — 3, 7 — 4.");
        lines.add("• Ціни звичайних големів: 1-й — 1 смарагд, 2-й — 3, 3-й — 10, 4-й — 20.");
        lines.add("• Елітний голем відкривається з 5 рівня. Ліміт — 1 елітний голем на гільдію.");
        lines.add("• Ціна елітного голема: 50 смарагдів на 5 рівні, 60 на 6 рівні, 70 на 7 рівні.");
        lines.add("");
        lines.add("Стати големів:");
        lines.add("• Звичайний голем має статично зашиті +300% до максимального HP від бази та +50% до урону.");
        lines.add("• Елітний голем має статично зашиті +500% до максимального HP від бази та +75% до урону.");
        lines.add("• Удень, якщо HP нижче 50% від власного гільдійного максимуму, лікування має найвищий пріоритет.");
        lines.add("• Голем лікується до 100% і не має скидатися до vanilla HP після рестарту або переспавну.");
        lines.add("• Уночі големи тримаються ближче до учасників на поверхні, але не йдуть у шахти за підземними цілями.");
        lines.add("");
        lines.add("Коротко про бонуси:");
        lines.add("• Гільдія дає базово +5% досвіду та +10% тривалості корисних зіль; сильніші бонуси відкриваються талантами.");
        lines.add("• Броня отримує бонус тільки коли вона реально вдягнена.");
        lines.add("• Зброя отримує бонус для мечів, сокир, булави, тризуба, луків і арбалетів. Кирки, сапки, лопати, блоки й матеріали бонус зброї не отримують.");
        return lines;
    }

    private String roleTooltip(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "GUILDMASTER" -> "Гілдмайстер: повний контроль над гільдією, ролями, големами, запрошеннями та територіями.";
            case "BUILDER" -> "Будівельник: може будувати на гільдійній території, але не керує ролями чи фермою.";
            case "QUARTERMASTER" -> "Завгосп: має доступ до управління сховищем та внутрішньої логістики гільдії.";
            case "FARMER" -> "Фермер: разом із Гілдмайстром може садити, збирати та керувати фермою на гільдійній території.";
            default -> "Воїн: базовий учасник гільдії без додаткових прав керування.";
        };
    }

    private int visibleLineCount(int innerH, int lineStep) {
        return Math.max(1, innerH / Math.max(1, lineStep));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String trim(String value, int maxChars) {
        if (value == null) return "";
        if (maxChars < 4 || value.length() <= maxChars) return value;
        return value.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    private String emptyDash(String value) { return value == null || value.isBlank() ? "—" : value; }

    private static final class Layout { int x, y, panelW, panelH, pad, contentY, bottomY; }
    private static final class ContextAction { String label, action, target, extra; boolean active; int x, y, w, h; }
    private static final class GolemAction { String uuid, action; int x, y, w, h; }
    private static final class MemberAction { String kind; MemberRow member; int x, y, w, h; }
    private static final class TooltipArea { final int x, y, w, h; final java.util.List<String> lines; TooltipArea(int x, int y, int w, int h, java.util.List<String> lines) { this.x = x; this.y = y; this.w = w; this.h = h; this.lines = lines; } }
    private static final class LineEntry { final String text; final int color; final String tooltip; LineEntry(String text, int color) { this(text, color, ""); } LineEntry(String text, int color, String tooltip) { this.text = text; this.color = color; this.tooltip = tooltip; } }
    private static final class ScrollArea {
        int x, y, w, h, visibleRows;
        void set(int x, int y, int w, int h, int visibleRows) { this.x = x; this.y = y; this.w = w; this.h = h; this.visibleRows = visibleRows; }
        boolean contains(double mx, double my) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }
    }

    private static final class GuildView {
        String mode = "INVITES";
        String guildName = "немає";
        String roleLabel = "немає";
        boolean isGuildmaster;
        boolean readOnly;
        int memberLimit = 20;
        int territoryLimit = 4;
        int guildLevel = 1;
        int guildXp = 0;
        int guildXpInLevel = 0;
        int guildXpLevelSize = 500;
        int maxGolems = 1;
        int maxOrdinaryGolems = 1;
        int maxEliteGolems = 0;
        int normalGolemCost = 1;
        int eliteGolemCost = 0;
        int nextEliteGolemUnlockLevel = 5;
        int healthBonus = 0;
        int armorBonus = 5;
        int damageBonus = 5;
        int speedBonus = 0;
        String jumpBonus = "0";
        int damageReductionBonus = 0;
        int xpBonus = 5;
        int potionBonus = 10;
        int mobKillGuildXpBonus = 5;
        boolean nightVision = false;
        boolean waterBreathing = false;
        boolean fireResistance = false;
        boolean onOwnGuildTerritory = false;
        int golemTalentHealth = 0;
        int golemTalentDamage = 0;
        int golemTalentSpeed = 0;
        int golemTalentHealing = 0;
        int golemCrushingForce = 0;
        int golemOocRegenSeconds = 0;
        int golemRouteEfficiency = 0;
        boolean golemInterceptTalent = false;
        boolean golemEliteCommandTalent = false;
        boolean golemNightWatchTalent = false;
        final List<MemberRow> members = new ArrayList<>();
        final List<InviteRow> invites = new ArrayList<>();
        final List<GolemRow> golems = new ArrayList<>();
        final List<TerritoryRow> territories = new ArrayList<>();
        final List<AchievementRow> achievements = new ArrayList<>();

        static GuildView parse(String snapshot) {
            GuildView out = new GuildView();
            String section = "";
            for (String raw : snapshot.split("\\R")) {
                String line = raw == null ? "" : raw.trim();
                if (line.isEmpty()) continue;
                if (line.equals("MEMBERS_BEGIN") || line.equals("INVITES_BEGIN") || line.equals("GOLEMS_BEGIN") || line.equals("TERRITORIES_BEGIN") || line.equals("ACHIEVEMENTS_BEGIN")) { section = line; continue; }
                if (line.endsWith("_END")) { section = ""; continue; }
                if (section.equals("MEMBERS_BEGIN")) { out.members.add(MemberRow.parse(line)); continue; }
                if (section.equals("INVITES_BEGIN")) { out.invites.add(InviteRow.parse(line)); continue; }
                if (section.equals("GOLEMS_BEGIN")) { out.golems.add(GolemRow.parse(line)); continue; }
                if (section.equals("TERRITORIES_BEGIN")) { out.territories.add(TerritoryRow.parse(line)); continue; }
                if (section.equals("ACHIEVEMENTS_BEGIN")) { out.achievements.add(AchievementRow.parse(line)); continue; }
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "MODE" -> { out.mode = value; out.readOnly = "VIEW".equalsIgnoreCase(value); }
                    case "GUILD" -> out.guildName = value;
                    case "ROLE" -> out.roleLabel = value;
                    case "IS_MASTER" -> out.isGuildmaster = Boolean.parseBoolean(value);
                    case "ON_OWN_GUILD_TERRITORY" -> out.onOwnGuildTerritory = Boolean.parseBoolean(value);
                    case "MEMBER_LIMIT" -> out.memberLimit = parseInt(value, 20);
                    case "TERRITORY_LIMIT" -> out.territoryLimit = parseInt(value, 4);
                    case "GUILD_LEVEL" -> out.guildLevel = parseInt(value, 1);
                    case "GUILD_XP" -> out.guildXp = parseInt(value, 0);
                    case "GUILD_XP_IN_LEVEL" -> out.guildXpInLevel = parseInt(value, 0);
                    case "GUILD_XP_LEVEL_SIZE" -> out.guildXpLevelSize = parseInt(value, 500);
                    case "MAX_GOLEMS" -> out.maxGolems = parseInt(value, 1);
                    case "MAX_ORDINARY_GOLEMS" -> out.maxOrdinaryGolems = parseInt(value, 1);
                    case "MAX_ELITE_GOLEMS" -> out.maxEliteGolems = parseInt(value, 0);
                    case "NORMAL_GOLEM_COST", "NEXT_COST" -> out.normalGolemCost = parseInt(value, 1);
                    case "ELITE_GOLEM_COST" -> out.eliteGolemCost = parseInt(value, 0);
                    case "NEXT_ELITE_GOLEM_UNLOCK_LEVEL" -> out.nextEliteGolemUnlockLevel = parseInt(value, 5);
                    case "HEALTH_BONUS" -> out.healthBonus = parseInt(value, 0);
                    case "ARMOR_BONUS" -> out.armorBonus = parseInt(value, 5);
                    case "DAMAGE_BONUS" -> out.damageBonus = parseInt(value, 5);
                    case "SPEED_BONUS" -> out.speedBonus = parseInt(value, 0);
                    case "JUMP_BONUS" -> out.jumpBonus = value;
                    case "DAMAGE_REDUCTION_BONUS" -> out.damageReductionBonus = parseInt(value, 0);
                    case "XP_BONUS" -> out.xpBonus = parseInt(value, 5);
                    case "POTION_BONUS" -> out.potionBonus = parseInt(value, 10);
                    case "MOB_KILL_GUILD_XP_BONUS" -> out.mobKillGuildXpBonus = parseInt(value, 5);
                    case "NIGHT_VISION" -> out.nightVision = Boolean.parseBoolean(value);
                    case "MEMBER_WATER_BREATHING" -> out.waterBreathing = Boolean.parseBoolean(value);
                    case "MEMBER_FIRE_RESISTANCE" -> out.fireResistance = Boolean.parseBoolean(value);
                    case "GOLEM_TALENT_HEALTH" -> out.golemTalentHealth = parseInt(value, 0);
                    case "GOLEM_TALENT_DAMAGE" -> out.golemTalentDamage = parseInt(value, 0);
                    case "GOLEM_TALENT_SPEED" -> out.golemTalentSpeed = parseInt(value, 0);
                    case "GOLEM_TALENT_HEALING" -> out.golemTalentHealing = parseInt(value, 0);
                    case "GOLEM_CRUSHING_FORCE" -> out.golemCrushingForce = parseInt(value, 0);
                    case "GOLEM_OOC_REGEN_INTERVAL_SECONDS" -> out.golemOocRegenSeconds = parseInt(value, 0);
                    case "GOLEM_ROUTE_EFFICIENCY" -> out.golemRouteEfficiency = parseInt(value, 0);
                    case "GOLEM_INTERCEPT_TALENT" -> out.golemInterceptTalent = Boolean.parseBoolean(value);
                    case "GOLEM_ELITE_COMMAND_TALENT" -> out.golemEliteCommandTalent = Boolean.parseBoolean(value);
                    case "GOLEM_NIGHT_WATCH_TALENT" -> out.golemNightWatchTalent = Boolean.parseBoolean(value);
                }
            }
            return out;
        }
        private static int parseInt(String raw, int fallback) { try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; } }
    }

    private static final class MemberRow {
        String uuid, name, role, roleLabel, bedCoords, bedStatus;
        boolean online, guildmaster;
        int x, y, w, h;
        static MemberRow parse(String line) {
            String[] p = line.split("\\|", -1);
            MemberRow row = new MemberRow();
            row.uuid = part(p, 0);
            row.name = part(p, 1);
            row.role = part(p, 2);
            row.roleLabel = part(p, 3);
            row.online = Boolean.parseBoolean(part(p, 4));
            row.guildmaster = Boolean.parseBoolean(part(p, 5));
            row.bedCoords = part(p, 6);
            row.bedStatus = part(p, 7);
            if (row.bedCoords == null || row.bedCoords.isBlank()) row.bedCoords = "—";
            if (row.bedStatus == null || row.bedStatus.isBlank()) row.bedStatus = row.bedCoords.equals("—") ? "немає" : "закріплено";
            return row;
        }
    }

    private static final class InviteRow {
        String guildId, guildName, invitedBy;
        int x, y, w, h;
        static InviteRow parse(String line) {
            String[] p = line.split("\\|", -1);
            InviteRow row = new InviteRow();
            row.guildId = part(p, 0);
            row.guildName = part(p, 1);
            row.invitedBy = part(p, 2);
            return row;
        }
    }

    private static final class GolemRow {
        String uuid, shortId, type, status, name, x, y, z, hpText;
        int hp, maxHp;
        boolean elite, dead;
        static GolemRow parse(String line) {
            String[] p = line.split("\\|", -1);
            GolemRow row = new GolemRow();
            row.uuid = part(p, 0);
            row.shortId = row.uuid.length() > 8 ? row.uuid.substring(0, 8) : row.uuid;
            row.type = part(p, 1).replace("minecraft:", "");
            row.elite = Boolean.parseBoolean(part(p, 6)) || "elite".equalsIgnoreCase(row.type);
            row.name = part(p, 7);
            row.dead = Boolean.parseBoolean(part(p, 8)) || "загинув".equalsIgnoreCase(part(p, 5));
            row.hp = GuildView.parseInt(part(p, 9), row.dead ? 0 : -1);
            row.maxHp = GuildView.parseInt(part(p, 10), -1);
            if (row.hp >= 0 && row.maxHp > 0) row.hpText = row.hp + "/" + row.maxHp;
            else if (row.maxHp > 0) row.hpText = "?/" + row.maxHp;
            else row.hpText = "?";
            if (row.name == null || row.name.isBlank()) row.name = row.elite ? "РІК" : row.shortId;
            row.x = part(p, 2); row.y = part(p, 3); row.z = part(p, 4);
            row.status = friendlyGolemStatus(part(p, 5), row.dead, row.elite);
            return row;
        }
    }

    private static final class AchievementRow {
        String id, title, category, description, conditions, reward, unlockedAt, unlockedBy;
        int xp, emeralds;
        boolean unlocked;
        static AchievementRow parse(String line) {
            String[] p = line.split("\\|", -1);
            AchievementRow row = new AchievementRow();
            row.id = part(p, 0);
            row.title = part(p, 1);
            row.category = part(p, 2);
            row.description = part(p, 3);
            row.conditions = part(p, 4);
            row.reward = part(p, 5);
            row.xp = GuildView.parseInt(part(p, 6), 0);
            row.emeralds = GuildView.parseInt(part(p, 7), 0);
            row.unlocked = Boolean.parseBoolean(part(p, 8));
            row.unlockedAt = part(p, 9);
            row.unlockedBy = part(p, 10);
            return row;
        }
    }

    private static final class TerritoryRow {
        String id, dimension, x, y, z, size, minX, maxX, minZ, maxZ;
        boolean spawnClipped;
        static TerritoryRow parse(String line) {
            String[] p = line.split("\\|", -1);
            TerritoryRow row = new TerritoryRow();
            row.id = part(p, 0);
            row.dimension = friendlyDimension(part(p, 1));
            row.x = part(p, 2); row.y = part(p, 3); row.z = part(p, 4); row.size = part(p, 5);
            row.minX = part(p, 6); row.maxX = part(p, 7); row.minZ = part(p, 8); row.maxZ = part(p, 9);
            row.spawnClipped = Boolean.parseBoolean(part(p, 10));
            return row;
        }
    }

    private static String friendlyGolemStatus(String raw, boolean dead, boolean elite) {
        String value = raw == null ? "" : raw.trim();
        String normalized = value.toUpperCase(Locale.ROOT);
        if (dead || normalized.contains("DEAD") || value.equalsIgnoreCase("загинув")) return "загинув";
        if (normalized.contains("RESPAWN")) return "очікує відродження";
        if (normalized.contains("REMOVED")) return "видалений";
        if (normalized.contains("ORPHAN") || normalized.contains("DATA") || normalized.contains("MISSING")) return "потрібні дані";
        if (normalized.contains("HEAL") || value.contains("ліку")) return "лікується біля тотема";
        if (normalized.contains("RETURN")) return "повертається до території";
        if (normalized.contains("ENGAGE") || normalized.contains("DEFEND") || normalized.contains("CHASE")) return "захищає територію";
        if (normalized.contains("PATROL")) return "патрулює";
        if (normalized.contains("STRATEGIC") || normalized.contains("COMMAND")) return elite ? "тримає ключову точку" : "патрулює";
        if (normalized.contains("RECOVER")) return "відновлюється";
        if (value.isBlank() || normalized.equals("ALIVE")) return elite ? "тримає ключову точку" : "патрулює";
        return value;
    }

    private static String friendlyDimension(String raw) {
        String value = raw == null ? "" : raw.replace("minecraft:", "").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "overworld" -> "звичайний світ";
            case "the_nether", "nether" -> "Незер";
            case "the_end", "end" -> "Край";
            default -> raw == null || raw.isBlank() ? "світ" : raw.replace("minecraft:", "");
        };
    }

    private static String friendlyDate(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        String value = raw.replace('T', ' ').replace("Z", "").trim();
        return value.length() > 16 ? value.substring(0, 16) : value;
    }

    private static String part(String[] parts, int index) { return index >= 0 && index < parts.length ? parts[index] : ""; }
}
