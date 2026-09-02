#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one occurrence, found {count}: {old[:100]!r}')
    write(path, text.replace(old, new, 1))


def replace_regex_once(path, pattern, replacement):
    text = read(path)
    text2, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{path}: regex expected exactly one occurrence, found {count}: {pattern[:120]!r}')
    write(path, text2)


GPL = '''/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
'''

write('forge-game/src/main/java/forge/game/combat/CombatDamageSelection.java', GPL + r'''package forge.game.combat;

import java.util.Objects;

/** A controller selection that can only name a choice authorized by the rules core. */
public final class CombatDamageSelection {
    private final String choiceId;

    public CombatDamageSelection(final String choiceId) {
        this.choiceId = Objects.requireNonNull(choiceId);
    }

    public String getChoiceId() {
        return choiceId;
    }
}
''')

write('forge-game/src/main/java/forge/game/combat/CombatDamageDecisionView.java', GPL + r'''package forge.game.combat;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable rules-core view of one incremental combat-damage decision.
 * Controllers receive opaque entity ids and already-authorized choices only.
 */
public final class CombatDamageDecisionView {
    public enum ChoiceKind { PROCEED, DEFER, SPILL, NO_SPILL, AMOUNT }

    public static final class Choice {
        private final String id;
        private final ChoiceKind kind;
        private final int recipientId;
        private final int amount;
        private final boolean defender;
        private final String label;

        public Choice(final String id, final ChoiceKind kind, final int recipientId,
                      final int amount, final boolean defender, final String label) {
            this.id = Objects.requireNonNull(id);
            this.kind = Objects.requireNonNull(kind);
            this.recipientId = recipientId;
            this.amount = amount;
            this.defender = defender;
            this.label = Objects.requireNonNull(label);
        }

        public String getId() { return id; }
        public ChoiceKind getKind() { return kind; }
        public int getRecipientId() { return recipientId; }
        public int getAmount() { return amount; }
        public boolean isDefender() { return defender; }
        public String getLabel() { return label; }
    }

    private final String decisionId;
    private final int sourceId;
    private final int remainingDamage;
    private final boolean spillMode;
    private final String prompt;
    private final List<Choice> choices;

    public CombatDamageDecisionView(final String decisionId, final int sourceId,
                                    final int remainingDamage, final boolean spillMode,
                                    final String prompt, final List<Choice> choices) {
        this.decisionId = Objects.requireNonNull(decisionId);
        this.sourceId = sourceId;
        this.remainingDamage = remainingDamage;
        this.spillMode = spillMode;
        this.prompt = Objects.requireNonNull(prompt);
        this.choices = List.copyOf(choices);
        if (this.choices.isEmpty()) {
            throw new IllegalArgumentException("combat-damage decision must contain at least one legal choice");
        }
    }

    public String getDecisionId() { return decisionId; }
    public int getSourceId() { return sourceId; }
    public int getRemainingDamage() { return remainingDamage; }
    public boolean isSpillMode() { return spillMode; }
    public String getPrompt() { return prompt; }
    public List<Choice> getChoices() { return Collections.unmodifiableList(choices); }

    public Choice requireAuthorized(final CombatDamageSelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("null combat-damage selection");
        }
        for (Choice choice : choices) {
            if (choice.id.equals(selection.getChoiceId())) {
                return choice;
            }
        }
        throw new IllegalArgumentException("selection was not authorized by decision " + decisionId);
    }
}
''')

write('forge-game/src/main/java/forge/game/combat/CombatDamageRuleMath.java', GPL + r'''package forge.game.combat;

import java.util.Collection;

/** Pure validation helpers used by the native combat-damage transaction. */
public final class CombatDamageRuleMath {
    private CombatDamageRuleMath() { }

    public static int requiredLethal(final int lethalValue, final int markedDamage,
                                     final int sameStepAssigned, final boolean sameStepDeathtouch) {
        if (sameStepDeathtouch) {
            return 0;
        }
        return Math.max(0, lethalValue - markedDamage - sameStepAssigned);
    }

    public static void requireExactTotal(final int expected, final Collection<Integer> amounts) {
        int total = 0;
        for (Integer amount : amounts) {
            if (amount == null || amount < 0) {
                throw new IllegalArgumentException("combat damage may not be null or negative");
            }
            total += amount;
        }
        if (total != expected) {
            throw new IllegalArgumentException("combat damage total " + total + " != " + expected);
        }
    }

    public static void requireSpillLegal(final int defenderDamage, final Collection<Integer> lethalRemainders) {
        if (defenderDamage <= 0) {
            return;
        }
        for (Integer remaining : lethalRemainders) {
            if (remaining == null || remaining > 0) {
                throw new IllegalArgumentException("trample spill before lethal assignment");
            }
        }
    }
}
''')

write('forge-game/src/main/java/forge/game/combat/CombatDamageStepTransaction.java', GPL + r'''package forge.game.combat;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.keyword.Keyword;
import forge.game.player.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One combat-damage-step transaction. All source assignments are selected first,
 * then independently validated as complete player assignments, then committed.
 */
public final class CombatDamageStepTransaction {
    private final Combat combat;
    private final boolean firstStrikeDamage;
    private final List<SourceRecord> records = new ArrayList<>();
    private int serial = 0;

    public CombatDamageStepTransaction(final Combat combat, final boolean firstStrikeDamage) {
        this.combat = combat;
        this.firstStrikeDamage = firstStrikeDamage;
    }

    public boolean chooseAndRecord(final Player assigningPlayer, final Card source,
                                   final CardCollectionView opposed, final CardCollectionView remainingSources,
                                   final int damage, final GameEntity defender, final boolean overrideOrder) {
        if (damage < 0) {
            throw new IllegalArgumentException("negative combat damage");
        }
        final boolean sourceIsAttacker = combat.isAttacking(source);
        final boolean specialFreeDivision = source.hasKeyword("You may assign CARDNAME's combat damage divided as you choose among "
                + "defending player and/or any number of creatures they control.");
        final boolean trample = sourceIsAttacker && source.hasKeyword(Keyword.TRAMPLE) && defender != null;
        final boolean mayDefer = remainingSources != null && remainingSources.size() > 1 && sourceIsAttacker;

        if (mayDefer && askProceed(assigningPlayer, source, damage)) {
            return false;
        }

        final List<Card> recipients = new ArrayList<>();
        for (Card card : opposed) {
            recipients.add(card);
        }

        boolean spillMode = false;
        if (trample && canCurrentlySpill(source, recipients, damage)) {
            spillMode = askSpill(assigningPlayer, source, damage);
        }

        final LinkedHashMap<GameEntity, Integer> assignment = new LinkedHashMap<>();
        int remaining = damage;

        if (recipients.isEmpty()) {
            if (defender != null && (trample || specialFreeDivision || !sourceIsAttacker || !combat.isBlocked(source))) {
                assignment.put(defender, remaining);
                remaining = 0;
            }
        } else if (spillMode) {
            for (int i = 0; i < recipients.size(); i++) {
                final Card recipient = recipients.get(i);
                final int min = Math.min(remaining, requiredFor(source, recipient));
                int futureRequired = 0;
                for (int j = i + 1; j < recipients.size(); j++) {
                    futureRequired += requiredFor(source, recipients.get(j));
                }
                final int max = Math.max(min, remaining - Math.min(remaining, futureRequired));
                final int amount = chooseAmount(assigningPlayer, source, recipient, remaining, min, max, true);
                if (amount > 0) {
                    assignment.put(recipient, amount);
                    remaining -= amount;
                }
            }
            if (remaining > 0 && defender != null) {
                assignment.put(defender, remaining);
                remaining = 0;
            }
        } else {
            final boolean defenderAllowed = specialFreeDivision && defender != null;
            final int totalSlots = recipients.size() + (defenderAllowed ? 1 : 0);
            int slot = 0;
            for (Card recipient : recipients) {
                slot++;
                int min = 0;
                int max = remaining;
                if (!overrideOrder) {
                    min = Math.min(remaining, requiredFor(source, recipient));
                }
                if (slot == totalSlots) {
                    min = remaining;
                    max = remaining;
                }
                final int amount = chooseAmount(assigningPlayer, source, recipient, remaining, min, max, false);
                if (amount > 0) {
                    assignment.put(recipient, amount);
                    remaining -= amount;
                }
            }
            if (defenderAllowed) {
                if (remaining > 0) {
                    assignment.put(defender, remaining);
                    remaining = 0;
                }
            } else if (remaining > 0 && recipients.isEmpty() && defender != null && !combat.isBlocked(source)) {
                assignment.put(defender, remaining);
                remaining = 0;
            }
        }

        if (remaining != 0) {
            throw new IllegalStateException("rules-core transaction left unassigned combat damage: " + remaining);
        }
        addRecord(assigningPlayer, source, damage, recipients, defender, assignment,
                sourceIsAttacker, trample, specialFreeDivision, overrideOrder, false);
        return true;
    }

    public void recordForced(final Player assigningPlayer, final Card source,
                             final GameEntity target, final int damage) {
        if (damage <= 0) {
            return;
        }
        final LinkedHashMap<GameEntity, Integer> assignment = new LinkedHashMap<>();
        assignment.put(target, damage);
        addRecord(assigningPlayer, source, damage, List.of(), target, assignment,
                combat.isAttacking(source), false, false, true, true);
    }

    private void addRecord(final Player assigningPlayer, final Card source, final int damage,
                           final List<Card> recipients, final GameEntity defender,
                           final LinkedHashMap<GameEntity, Integer> assignment,
                           final boolean sourceIsAttacker, final boolean trample,
                           final boolean specialFreeDivision, final boolean overrideOrder,
                           final boolean forced) {
        records.add(new SourceRecord(assigningPlayer, source, damage, recipients, defender,
                assignment, sourceIsAttacker, trample, specialFreeDivision, overrideOrder, forced));
    }

    private boolean askProceed(final Player assigningPlayer, final Card source, final int damage) {
        final String id = nextId(assigningPlayer, source, "progress");
        final List<CombatDamageDecisionView.Choice> choices = List.of(
                new CombatDamageDecisionView.Choice(id + ":proceed", CombatDamageDecisionView.ChoiceKind.PROCEED,
                        -1, 0, false, "assign this source now"),
                new CombatDamageDecisionView.Choice(id + ":defer", CombatDamageDecisionView.ChoiceKind.DEFER,
                        -1, 0, false, "assign another source first"));
        final CombatDamageDecisionView view = new CombatDamageDecisionView(id, source.getId(), damage, false,
                "Choose combat-damage source progression", choices);
        final CombatDamageDecisionView.Choice chosen = view.requireAuthorized(assigningPlayer.getController().chooseCombatDamage(view));
        return chosen.getKind() == CombatDamageDecisionView.ChoiceKind.DEFER;
    }

    private boolean askSpill(final Player assigningPlayer, final Card source, final int damage) {
        final String id = nextId(assigningPlayer, source, "trample");
        final List<CombatDamageDecisionView.Choice> choices = List.of(
                new CombatDamageDecisionView.Choice(id + ":spill", CombatDamageDecisionView.ChoiceKind.SPILL,
                        -1, 0, true, "permit excess damage to defender after lethal assignment"),
                new CombatDamageDecisionView.Choice(id + ":nospill", CombatDamageDecisionView.ChoiceKind.NO_SPILL,
                        -1, 0, false, "assign all damage among blocking creatures"));
        final CombatDamageDecisionView view = new CombatDamageDecisionView(id, source.getId(), damage, false,
                "Choose whether to use trample spill", choices);
        final CombatDamageDecisionView.Choice chosen = view.requireAuthorized(assigningPlayer.getController().chooseCombatDamage(view));
        return chosen.getKind() == CombatDamageDecisionView.ChoiceKind.SPILL;
    }

    private int chooseAmount(final Player assigningPlayer, final Card source, final GameEntity recipient,
                             final int remaining, final int min, final int max, final boolean spillMode) {
        if (min < 0 || max < min || max > remaining) {
            throw new IllegalStateException("invalid core combat-damage range " + min + ".." + max);
        }
        if (min == max) {
            return min;
        }
        final String id = nextId(assigningPlayer, source, "amount-" + recipient.getId());
        final List<CombatDamageDecisionView.Choice> choices = new ArrayList<>();
        for (int amount = min; amount <= max; amount++) {
            choices.add(new CombatDamageDecisionView.Choice(id + ":" + amount,
                    CombatDamageDecisionView.ChoiceKind.AMOUNT, recipient.getId(), amount, false,
                    "assign " + amount + " damage to entity " + recipient.getId()));
        }
        final CombatDamageDecisionView view = new CombatDamageDecisionView(id, source.getId(), remaining,
                spillMode, "Assign combat damage to entity " + recipient.getId(), choices);
        final CombatDamageDecisionView.Choice chosen = view.requireAuthorized(assigningPlayer.getController().chooseCombatDamage(view));
        return chosen.getAmount();
    }

    private String nextId(final Player player, final Card source, final String suffix) {
        return "combat:" + (firstStrikeDamage ? "first" : "normal") + ":p" + player.getId()
                + ":s" + source.getId() + ":" + suffix + ":" + (++serial);
    }

    private boolean canCurrentlySpill(final Card source, final List<Card> recipients, final int damage) {
        int required = 0;
        for (Card recipient : recipients) {
            required += requiredFor(source, recipient);
        }
        return required <= damage;
    }

    private int requiredFor(final Card source, final Card recipient) {
        final Aggregate aggregate = aggregateFor(recipient, source);
        if (source.hasKeyword("Trample:Planeswalker") && combat.isAttacking(source, recipient) && recipient.isPlaneswalker()) {
            return Math.max(0, recipient.getCurrentLoyalty() - aggregate.damage);
        }
        return CombatDamageRuleMath.requiredLethal(recipient.getLethal(), recipient.getDamage(),
                aggregate.damage, aggregate.deathtouch);
    }

    private Aggregate aggregateFor(final GameEntity recipient, final Card exceptSource) {
        int damage = 0;
        boolean deathtouch = false;
        for (SourceRecord record : records) {
            if (record.source == exceptSource) {
                continue;
            }
            final Integer amount = record.assignment.get(recipient);
            if (amount != null && amount > 0) {
                damage += amount;
                if (recipient instanceof Card && record.source.hasKeyword(Keyword.DEATHTOUCH)) {
                    deathtouch = true;
                }
            }
        }
        return new Aggregate(damage, deathtouch);
    }

    public void validateAndCommit() {
        final Map<Player, List<SourceRecord>> byPlayer = new LinkedHashMap<>();
        for (SourceRecord record : records) {
            byPlayer.computeIfAbsent(record.assigningPlayer, k -> new ArrayList<>()).add(record);
        }
        for (Map.Entry<Player, List<SourceRecord>> entry : byPlayer.entrySet()) {
            validatePlayerAssignment(entry.getKey(), entry.getValue());
        }
        for (SourceRecord record : records) {
            for (Map.Entry<GameEntity, Integer> e : record.assignment.entrySet()) {
                if (e.getValue() > 0) {
                    combat.commitValidatedDamage(record.source, e.getKey(), e.getValue());
                }
            }
        }
    }

    private void validatePlayerAssignment(final Player player, final List<SourceRecord> playerRecords) {
        for (SourceRecord record : playerRecords) {
            CombatDamageRuleMath.requireExactTotal(record.damage, record.assignment.values());
            if (record.source.getGame() != player.getGame()) {
                throw new IllegalStateException("stale combat-damage source");
            }
            if (record.forced) {
                if (record.assignment.size() != 1 || !record.assignment.containsKey(record.defender)) {
                    throw new IllegalStateException("malformed forced combat-damage assignment");
                }
                continue;
            }

            final Set<GameEntity> allowed = new HashSet<>(record.recipients);
            if (record.specialFreeDivision || record.trample || (!record.sourceIsAttacker && record.defender != null)) {
                if (record.defender != null) {
                    allowed.add(record.defender);
                }
            }
            for (GameEntity recipient : record.assignment.keySet()) {
                if (!allowed.contains(recipient)) {
                    throw new IllegalStateException("combat damage assigned to non-authorized recipient");
                }
                if (recipient instanceof Card c && !c.isInPlay() && !c.isLKI()) {
                    throw new IllegalStateException("stale combat-damage recipient");
                }
            }

            if (record.sourceIsAttacker && combat.isBlocked(record.source) && !record.trample
                    && !record.specialFreeDivision && record.defender != null
                    && record.assignment.getOrDefault(record.defender, 0) > 0) {
                throw new IllegalStateException("blocked nontrampler assigned damage to defender");
            }

            if (record.trample && record.defender != null
                    && record.assignment.getOrDefault(record.defender, 0) > 0) {
                final List<Integer> remainders = new ArrayList<>();
                for (Card recipient : record.recipients) {
                    final Aggregate full = aggregateAll(recipient);
                    if (record.source.hasKeyword("Trample:Planeswalker") && combat.isAttacking(record.source, recipient)
                            && recipient.isPlaneswalker()) {
                        remainders.add(Math.max(0, recipient.getCurrentLoyalty() - full.damage));
                    } else {
                        remainders.add(CombatDamageRuleMath.requiredLethal(recipient.getLethal(), recipient.getDamage(),
                                full.damage, full.deathtouch));
                    }
                }
                CombatDamageRuleMath.requireSpillLegal(record.assignment.get(record.defender), remainders);
            }

            if (!record.overrideOrder && record.recipients.size() > 1) {
                boolean earlierNotLethal = false;
                for (Card recipient : record.recipients) {
                    final int amount = record.assignment.getOrDefault(recipient, 0);
                    if (earlierNotLethal && amount > 0) {
                        throw new IllegalStateException("legacy damage-assignment order violation");
                    }
                    final Aggregate full = aggregateForRecord(recipient, record);
                    final boolean lethal = full.deathtouch || recipient.getDamage() + full.damage >= recipient.getLethal();
                    if (!lethal) {
                        earlierNotLethal = true;
                    }
                }
            }
        }
    }

    private Aggregate aggregateAll(final GameEntity recipient) {
        int damage = 0;
        boolean deathtouch = false;
        for (SourceRecord record : records) {
            final int amount = record.assignment.getOrDefault(recipient, 0);
            if (amount > 0) {
                damage += amount;
                if (recipient instanceof Card && record.source.hasKeyword(Keyword.DEATHTOUCH)) {
                    deathtouch = true;
                }
            }
        }
        return new Aggregate(damage, deathtouch);
    }

    private Aggregate aggregateForRecord(final GameEntity recipient, final SourceRecord record) {
        int damage = 0;
        boolean deathtouch = false;
        final int amount = record.assignment.getOrDefault(recipient, 0);
        if (amount > 0) {
            damage = amount;
            deathtouch = recipient instanceof Card && record.source.hasKeyword(Keyword.DEATHTOUCH);
        }
        return new Aggregate(damage, deathtouch);
    }

    private static final class Aggregate {
        final int damage;
        final boolean deathtouch;
        Aggregate(final int damage, final boolean deathtouch) {
            this.damage = damage;
            this.deathtouch = deathtouch;
        }
    }

    private static final class SourceRecord {
        final Player assigningPlayer;
        final Card source;
        final int damage;
        final List<Card> recipients;
        final GameEntity defender;
        final LinkedHashMap<GameEntity, Integer> assignment;
        final boolean sourceIsAttacker;
        final boolean trample;
        final boolean specialFreeDivision;
        final boolean overrideOrder;
        final boolean forced;

        SourceRecord(final Player assigningPlayer, final Card source, final int damage,
                     final List<Card> recipients, final GameEntity defender,
                     final LinkedHashMap<GameEntity, Integer> assignment,
                     final boolean sourceIsAttacker, final boolean trample,
                     final boolean specialFreeDivision, final boolean overrideOrder,
                     final boolean forced) {
            this.assigningPlayer = assigningPlayer;
            this.source = source;
            this.damage = damage;
            this.recipients = List.copyOf(recipients);
            this.defender = defender;
            this.assignment = new LinkedHashMap<>(assignment);
            this.sourceIsAttacker = sourceIsAttacker;
            this.trample = trample;
            this.specialFreeDivision = specialFreeDivision;
            this.overrideOrder = overrideOrder;
            this.forced = forced;
        }
    }
}
''')

write('forge-game/src/main/java/forge/game/player/AmountDistributionSelection.java', GPL + r'''package forge.game.player;

import java.util.Objects;

public final class AmountDistributionSelection {
    private final String choiceId;
    public AmountDistributionSelection(final String choiceId) { this.choiceId = Objects.requireNonNull(choiceId); }
    public String getChoiceId() { return choiceId; }
}
''')

write('forge-game/src/main/java/forge/game/player/AmountDistributionDecisionView.java', GPL + r'''package forge.game.player;

import java.util.List;
import java.util.Objects;

/** Core-constrained noncombat amount distribution decision. */
public final class AmountDistributionDecisionView {
    public static final class Choice {
        private final String id;
        private final int recipientId;
        private final int amount;
        private final String label;
        public Choice(final String id, final int recipientId, final int amount, final String label) {
            this.id = Objects.requireNonNull(id);
            this.recipientId = recipientId;
            this.amount = amount;
            this.label = Objects.requireNonNull(label);
        }
        public String getId() { return id; }
        public int getRecipientId() { return recipientId; }
        public int getAmount() { return amount; }
        public String getLabel() { return label; }
    }

    private final String decisionId;
    private final int sourceId;
    private final int remainingAmount;
    private final String prompt;
    private final List<Choice> choices;

    public AmountDistributionDecisionView(final String decisionId, final int sourceId,
                                          final int remainingAmount, final String prompt,
                                          final List<Choice> choices) {
        this.decisionId = Objects.requireNonNull(decisionId);
        this.sourceId = sourceId;
        this.remainingAmount = remainingAmount;
        this.prompt = Objects.requireNonNull(prompt);
        this.choices = List.copyOf(choices);
        if (this.choices.isEmpty()) throw new IllegalArgumentException("empty amount-distribution choice set");
    }
    public String getDecisionId() { return decisionId; }
    public int getSourceId() { return sourceId; }
    public int getRemainingAmount() { return remainingAmount; }
    public String getPrompt() { return prompt; }
    public List<Choice> getChoices() { return choices; }
    public Choice requireAuthorized(final AmountDistributionSelection selection) {
        if (selection == null) throw new IllegalArgumentException("null amount-distribution selection");
        for (Choice c : choices) if (c.id.equals(selection.getChoiceId())) return c;
        throw new IllegalArgumentException("unauthorized amount-distribution selection");
    }
}
''')

write('forge-game/src/main/java/forge/game/player/AmountDistributionTransaction.java', GPL + r'''package forge.game.player;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate noncombat distribution boundary; deliberately does not reuse combat rules. */
public final class AmountDistributionTransaction {
    private AmountDistributionTransaction() { }

    public static Map<Card, Integer> distribute(final Player assigningPlayer, final Card source,
                                                final CardCollectionView recipients, final int amount) {
        if (amount < 0) throw new IllegalArgumentException("negative distribution amount");
        if (recipients.isEmpty()) {
            if (amount == 0) return Map.of();
            throw new IllegalArgumentException("cannot distribute positive amount to no recipients");
        }
        final List<Card> list = new ArrayList<>();
        for (Card c : recipients) list.add(c);
        final LinkedHashMap<Card, Integer> result = new LinkedHashMap<>();
        int remaining = amount;
        int serial = 0;
        for (int i = 0; i < list.size(); i++) {
            final Card recipient = list.get(i);
            final int chosen;
            if (i == list.size() - 1) {
                chosen = remaining;
            } else {
                final String id = "distribution:p" + assigningPlayer.getId() + ":s" + source.getId()
                        + ":r" + recipient.getId() + ":" + (++serial);
                final List<AmountDistributionDecisionView.Choice> choices = new ArrayList<>();
                for (int n = 0; n <= remaining; n++) {
                    choices.add(new AmountDistributionDecisionView.Choice(id + ":" + n,
                            recipient.getId(), n, "assign " + n + " to entity " + recipient.getId()));
                }
                final AmountDistributionDecisionView view = new AmountDistributionDecisionView(id, source.getId(),
                        remaining, "Distribute noncombat amount to entity " + recipient.getId(), choices);
                chosen = view.requireAuthorized(assigningPlayer.getController().chooseAmountDistribution(view)).getAmount();
            }
            if (chosen > 0) result.put(recipient, chosen);
            remaining -= chosen;
        }
        int total = 0;
        for (Map.Entry<Card, Integer> e : result.entrySet()) {
            if (!list.contains(e.getKey()) || e.getValue() < 0 || !e.getKey().isInPlay()) {
                throw new IllegalStateException("invalid or stale noncombat distribution");
            }
            total += e.getValue();
        }
        if (total != amount || remaining != 0) {
            throw new IllegalStateException("noncombat distribution total mismatch");
        }
        return result;
    }
}
''')

# PlayerController: remove raw combat-map boundary and add fail-closed core selections.
p = 'forge-game/src/main/java/forge/game/player/PlayerController.java'
replace_once(p, 'import forge.game.combat.Combat;\n', 'import forge.game.combat.Combat;\nimport forge.game.combat.CombatDamageDecisionView;\nimport forge.game.combat.CombatDamageSelection;\n')
replace_once(p,
'''    public abstract Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder);\n''',
'''    public CombatDamageSelection chooseCombatDamage(final CombatDamageDecisionView decision) {\n        throw new UnsupportedOperationException("controller does not implement core combat-damage decisions");\n    }\n    public AmountDistributionSelection chooseAmountDistribution(final AmountDistributionDecisionView decision) {\n        throw new UnsupportedOperationException("controller does not implement core amount-distribution decisions");\n    }\n''')

# Human controller consumes only already-authorized core choices through generic presentation.
p = 'forge-gui/src/main/java/forge/player/PlayerControllerHuman.java'
replace_once(p, 'import forge.game.combat.Combat;\nimport forge.game.combat.CombatUtil;\n', 'import forge.game.combat.*;\n')
replace_regex_once(p,
r'''    @Override\n    public Map<Card, Integer> assignCombatDamage\(final Card attacker, final CardCollectionView blockers, final CardCollectionView remaining,\n.*?\n    }\n\n    @Override\n    public Map<GameEntity, Integer> divideShield''',
r'''    @Override
    public CombatDamageSelection chooseCombatDamage(final CombatDamageDecisionView decision) {
        final List<CombatDamageDecisionView.Choice> choices = decision.getChoices();
        if (choices.size() == 1) {
            return new CombatDamageSelection(choices.get(0).getId());
        }
        final StringBuilder prompt = new StringBuilder(decision.getPrompt());
        for (int i = 0; i < choices.size(); i++) {
            prompt.append("\n").append(i).append(": ").append(choices.get(i).getLabel());
        }
        final int index = getGui().getInteger(prompt.toString(), 0, choices.size() - 1, 0);
        return new CombatDamageSelection(choices.get(index).getId());
    }

    @Override
    public AmountDistributionSelection chooseAmountDistribution(final AmountDistributionDecisionView decision) {
        final List<AmountDistributionDecisionView.Choice> choices = decision.getChoices();
        if (choices.size() == 1) {
            return new AmountDistributionSelection(choices.get(0).getId());
        }
        final StringBuilder prompt = new StringBuilder(decision.getPrompt());
        for (int i = 0; i < choices.size(); i++) {
            prompt.append("\n").append(i).append(": ").append(choices.get(i).getLabel());
        }
        final int index = getGui().getInteger(prompt.toString(), 0, choices.size() - 1, 0);
        return new AmountDistributionSelection(choices.get(index).getId());
    }

    @Override
    public Map<GameEntity, Integer> divideShield''')

# AI ranks authorized options only; no legal allocation construction remains.
p = 'forge-ai/src/main/java/forge/ai/PlayerControllerAi.java'
replace_once(p, 'import forge.game.combat.Combat;\n', 'import forge.game.combat.*;\n')
replace_regex_once(p,
r'''    @Override\n    public Map<Card, Integer> assignCombatDamage\(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder\) \{\n        return ComputerUtilCombat\.distributeAIDamage\(player, attacker, blockers, remaining, damageDealt, defender, overrideOrder\);\n    }''',
r'''    @Override
    public CombatDamageSelection chooseCombatDamage(final CombatDamageDecisionView decision) {
        CombatDamageDecisionView.Choice best = decision.getChoices().get(0);
        for (CombatDamageDecisionView.Choice choice : decision.getChoices()) {
            if (choice.getKind() == CombatDamageDecisionView.ChoiceKind.PROCEED) {
                return new CombatDamageSelection(choice.getId());
            }
            if (choice.getKind() == CombatDamageDecisionView.ChoiceKind.SPILL) {
                best = choice;
            } else if (choice.getKind() == CombatDamageDecisionView.ChoiceKind.AMOUNT) {
                if (decision.isSpillMode()) {
                    if (choice.getAmount() < best.getAmount()) best = choice;
                } else if (choice.getAmount() > best.getAmount()) {
                    best = choice;
                }
            }
        }
        return new CombatDamageSelection(best.getId());
    }

    @Override
    public AmountDistributionSelection chooseAmountDistribution(final AmountDistributionDecisionView decision) {
        AmountDistributionDecisionView.Choice best = decision.getChoices().get(0);
        for (AmountDistributionDecisionView.Choice choice : decision.getChoices()) {
            if (choice.getAmount() > best.getAmount()) best = choice;
        }
        return new AmountDistributionSelection(best.getId());
    }''')

# Noncombat divide-on-resolution gets a separate core-constrained transaction.
p = 'forge-game/src/main/java/forge/game/ability/effects/DamageDealEffect.java'
text = read(p)
if 'import forge.game.player.AmountDistributionTransaction;' not in text:
    text = text.replace('import forge.game.player.Player;\n', 'import forge.game.player.Player;\nimport forge.game.player.AmountDistributionTransaction;\n', 1)
write(p, text)
replace_once(p,
'''                Map<Card, Integer> map = assigningPlayer.getController().assignCombatDamage(sourceLKI, assigneeCards, null, dmg, null, true);\n''',
'''                Map<Card, Integer> map = AmountDistributionTransaction.distribute(assigningPlayer, sourceLKI, assigneeCards, dmg);\n''')

# Combat: defer all mutations until a complete step transaction validates.
p = 'forge-game/src/main/java/forge/game/combat/Combat.java'
replace_once(p, '    private boolean assignBlockersDamage(boolean firstStrikeDamage) {\n',
             '    private boolean assignBlockersDamage(boolean firstStrikeDamage, CombatDamageStepTransaction tx) {\n')
replace_once(p, '    private boolean assignAttackersDamage(boolean firstStrikeDamage) {\n',
             '    private boolean assignAttackersDamage(boolean firstStrikeDamage, CombatDamageStepTransaction tx) {\n')
replace_regex_once(p,
r'''                assignedDamage = true;\n                Map<Card, Integer> map = assigningPlayer\.getController\(\)\.assignCombatDamage\(blocker, attackers, null, damage, defender, divideCombatDamageAsChoose \|\| assigningPlayer != blocker\.getController\(\) \|\| !this\.legacyOrderCombatants\);\n                for \(Entry<Card, Integer> dt : map\.entrySet\(\)\) \{\n                    // Butcher Orgg\n                    if \(dt\.getKey\(\) == null && dt\.getValue\(\) > 0\) \{\n                        damageMap\.get\(\)\.put\(blocker, defender, dt\.getValue\(\)\);\n                    } else \{\n                        dt\.getKey\(\)\.addAssignedDamage\(dt\.getValue\(\), blocker\);\n                        damageMap\.get\(\)\.put\(blocker, dt\.getKey\(\), dt\.getValue\(\)\);\n                    }\n                }''',
r'''                assignedDamage = true;
                if (!tx.chooseAndRecord(assigningPlayer, blocker, attackers, null, damage, defender,
                        divideCombatDamageAsChoose || assigningPlayer != blocker.getController() || !this.legacyOrderCombatants)) {
                    throw new IllegalStateException("blocking combat-damage source cannot be deferred");
                }''')

# Forced attacker assignments are recorded, not committed.
text = read(p)
text = text.replace('damageMap.get().put(attacker, defender, damageDealt);',
                    'tx.recordForced(assigningPlayer, attacker, defender, damageDealt);')
text = text.replace('damageMap.get().put(attacker, chosen, damageDealt);',
                    'tx.recordForced(assigningPlayer, attacker, chosen, damageDealt);')
write(p, text)

replace_regex_once(p,
r'''                Map<Card, Integer> map = assigningPlayer\.getController\(\)\.assignCombatDamage\(attacker, orderedBlockers, attackers,\n                        damageDealt, defender, divideCombatDamageAsChoose \|\| getAttackingPlayer\(\) != assigningPlayer \|\| !this\.legacyOrderCombatants\);\n\n                attackers\.remove\(attacker\);\n                // player wants to assign another first\n                if \(map == null\) \{\n                    // add to end\n                    attackers\.add\(attacker\);\n                    continue;\n                }\n\n                for \(Entry<Card, Integer> dt : map\.entrySet\(\)\) \{\n                    if \(dt\.getKey\(\) == null\) \{\n                        if \(dt\.getValue\(\) > 0\) \{\n                            if \(defender instanceof Card\) \{\n                                \(\(Card\) defender\)\.addAssignedDamage\(dt\.getValue\(\), attacker\);\n                            }\n                            damageMap\.get\(\)\.put\(attacker, defender, dt\.getValue\(\)\);\n                        }\n                    } else \{\n                        dt\.getKey\(\)\.addAssignedDamage\(dt\.getValue\(\), attacker\);\n                        damageMap\.get\(\)\.put\(attacker, dt\.getKey\(\), dt\.getValue\(\)\);\n                    }\n                }''',
r'''                final boolean recorded = tx.chooseAndRecord(assigningPlayer, attacker, orderedBlockers, attackers,
                        damageDealt, defender, divideCombatDamageAsChoose || getAttackingPlayer() != assigningPlayer || !this.legacyOrderCombatants);
                attackers.remove(attacker);
                if (!recorded) {
                    attackers.add(attacker);
                    continue;
                }''')

replace_regex_once(p,
r'''    public final boolean assignCombatDamage\(boolean firstStrikeDamage\) \{\n        boolean assignedDamage = assignAttackersDamage\(firstStrikeDamage\);\n        assignedDamage \|= assignBlockersDamage\(firstStrikeDamage\);\n        if \(!firstStrikeDamage\) \{\n            // Clear first strike damage list since it doesn't matter anymore\n            combatantsThatDealtFirstStrikeDamage\.get\(\)\.clear\(\);\n        }\n        return assignedDamage;\n    }''',
r'''    public final boolean assignCombatDamage(boolean firstStrikeDamage) {
        final CombatDamageStepTransaction tx = new CombatDamageStepTransaction(this, firstStrikeDamage);
        boolean assignedDamage = assignAttackersDamage(firstStrikeDamage, tx);
        assignedDamage |= assignBlockersDamage(firstStrikeDamage, tx);
        tx.validateAndCommit();
        if (!firstStrikeDamage) {
            combatantsThatDealtFirstStrikeDamage.get().clear();
        }
        return assignedDamage;
    }

    void commitValidatedDamage(final Card source, final GameEntity target, final int amount) {
        if (amount <= 0 || target == null) {
            throw new IllegalArgumentException("validated combat damage requires positive amount and target");
        }
        if (target instanceof Card card) {
            card.addAssignedDamage(amount, source);
        }
        damageMap.get().put(source, target, amount);
    }''')

# Tests: pure negative/positive core validation and authorization.
write('forge-game/src/test/java/forge/game/combat/CombatDamageRuleMathTest.java', r'''package forge.game.combat;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class CombatDamageRuleMathTest {
    @Test
    public void exactTotalAcceptsCompleteAssignment() {
        CombatDamageRuleMath.requireExactTotal(5, List.of(2, 3));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void underAssignmentFailsClosed() {
        CombatDamageRuleMath.requireExactTotal(5, List.of(2, 2));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void overAssignmentFailsClosed() {
        CombatDamageRuleMath.requireExactTotal(5, List.of(2, 4));
    }

    @Test
    public void sameStepDamageReducesTrampleRequirement() {
        Assert.assertEquals(CombatDamageRuleMath.requiredLethal(4, 0, 2, false), 2);
    }

    @Test
    public void markedDamageReducesTrampleRequirement() {
        Assert.assertEquals(CombatDamageRuleMath.requiredLethal(4, 1, 0, false), 3);
    }

    @Test
    public void deathtouchSameStepAssignmentIsLethal() {
        Assert.assertEquals(CombatDamageRuleMath.requiredLethal(99, 0, 1, true), 0);
    }

    @Test
    public void legalTrampleSpillPasses() {
        CombatDamageRuleMath.requireSpillLegal(3, List.of(0, 0));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void prematureTrampleSpillFailsClosed() {
        CombatDamageRuleMath.requireSpillLegal(1, List.of(0, 1));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void unauthorizedSelectionFailsClosed() {
        CombatDamageDecisionView view = new CombatDamageDecisionView("d", 1, 1, false, "p",
                List.of(new CombatDamageDecisionView.Choice("legal", CombatDamageDecisionView.ChoiceKind.AMOUNT,
                        2, 1, false, "one")));
        view.requireAuthorized(new CombatDamageSelection("forged"));
    }
}
''')

# Fail closed if any production core caller still invokes the raw combat-map callback.
for path in [
    'forge-game/src/main/java/forge/game/combat/Combat.java',
    'forge-game/src/main/java/forge/game/ability/effects/DamageDealEffect.java',
]:
    if '.assignCombatDamage(' in read(path) and 'public final boolean assignCombatDamage(' not in read(path):
        raise SystemExit(f'raw combat allocation callback remains in {path}')

print('WS40 source remediation applied')
