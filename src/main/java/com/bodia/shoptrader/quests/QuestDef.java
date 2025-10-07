package com.bodia.shoptrader.quests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

public class QuestDef {
    public enum Kind { FETCH, KILL, MINE, FISH }

    private final String id;
    private final String name;
    private final Kind kind;
    private final Material targetMaterial; // for FETCH/MINE
    private final EntityType targetEntity; // for KILL
    private final int required;
    private final double rewardMoney;

    public QuestDef(String id, String name, Kind kind, Material targetMaterial, EntityType targetEntity, int required, double rewardMoney) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.targetMaterial = targetMaterial;
        this.targetEntity = targetEntity;
        this.required = required;
        this.rewardMoney = rewardMoney;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Kind getKind() { return kind; }
    public Material getTargetMaterial() { return targetMaterial; }
    public EntityType getTargetEntity() { return targetEntity; }
    public int getRequired() { return required; }
    public double getRewardMoney() { return rewardMoney; }
}
