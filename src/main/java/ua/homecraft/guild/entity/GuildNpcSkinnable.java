package ua.homecraft.guild.entity;

public interface GuildNpcSkinnable {
    default String homecraft$npcSkinId() {
        return "default";
    }

    default boolean homecraft$npcSlimArms() {
        return false;
    }
}
