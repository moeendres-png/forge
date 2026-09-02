#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"WS40_PATCH_EXPECTED_ONCE:{path}:{count}:{old[:80]!r}")
    p.write_text(text.replace(old, new, 1))

# PlayerController: supersede raw Map callback with the Core decision surface; keep old method only as deprecated ABI.
replace_once(
    "forge-game/src/main/java/forge/game/player/PlayerController.java",
    "import forge.game.combat.Combat;\n",
    "import forge.game.combat.Combat;\nimport forge.game.combat.CombatDamageDecisionView;\nimport forge.game.combat.CombatDamageSelection;\n",
)
replace_once(
    "forge-game/src/main/java/forge/game/player/PlayerController.java",
    "    public abstract Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder);\n",
    "    /** Core-owned incremental combat-damage choice. Unsupported controllers fail closed. */\n"
    "    public CombatDamageSelection chooseCombatDamage(final CombatDamageDecisionView decision) {\n"
    "        throw new IllegalStateException(\"FORGE_CONTROLLER_COMBAT_DAMAGE_DECISION_UNSUPPORTED\");\n"
    "    }\n\n"
    "    /** Core-owned noncombat amount distribution. Unsupported controllers fail closed. */\n"
    "    public AmountDistributionSelection chooseAmountDistribution(final AmountDistributionDecisionView decision) {\n"
    "        throw new IllegalStateException(\"FORGE_CONTROLLER_AMOUNT_DISTRIBUTION_UNSUPPORTED\");\n"
    "    }\n\n"
    "    /** @deprecated WS40: no production combat/noncombat caller may use this raw-map boundary. */\n"
    "    @Deprecated\n"
    "    public abstract Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder);\n",
)

# Human controller: presentation-only selection from Core-authorized recipients/ranges.
replace_once(
    "forge-gui/src/main/java/forge/player/PlayerControllerHuman.java",
    "import forge.game.combat.Combat;\nimport forge.game.combat.CombatUtil;\n",
    "import forge.game.combat.Combat;\nimport forge.game.combat.CombatUtil;\nimport forge.game.combat.CombatDamageDecisionView;\nimport forge.game.combat.CombatDamageSelection;\n",
)
human_marker = "    @Override\n    public Map<Card, Integer> assignCombatDamage(final Card attacker, final CardCollectionView blockers, final CardCollectionView remaining,\n"
human_method = r'''    @Override
    public CombatDamageSelection chooseCombatDamage(final CombatDamageDecisionView decision) {
        if (decision == null || decision.getSources().isEmpty()) {
            throw new IllegalStateException("FORGE_HUMAN_COMBAT_DAMAGE_EMPTY_CORE_VIEW");
        }
        CombatDamageDecisionView.SourceView sourceView = decision.getSources().get(0);
        if (decision.getSources().size() > 1) {
            final FCollection<Card> sources = new FCollection<>();
            for (CombatDamageDecisionView.SourceView source : decision.getSources()) {
                sources.add(source.getSource());
            }
            final Card chosenSource = chooseSingleEntityForEffect(sources, null,
                    Localizer.getInstance().getMessage("lblAssignCombatDamage"), null);
            sourceView = decision.getSources().stream()
                    .filter(source -> source.getSource() == chosenSource).findFirst()
                    .orElseThrow(() -> new IllegalStateException("FORGE_HUMAN_COMBAT_DAMAGE_STALE_SOURCE"));
        }

        CombatDamageDecisionView.RecipientView recipientView = sourceView.getRecipients().get(0);
        if (sourceView.getRecipients().size() > 1) {
            final FCollection<GameEntity> recipients = new FCollection<>();
            for (CombatDamageDecisionView.RecipientView recipient : sourceView.getRecipients()) {
                recipients.add(recipient.getRecipient());
            }
            final GameEntity chosenRecipient = chooseSingleEntityForEffect(recipients, null,
                    Localizer.getInstance().getMessage("lblAssignCombatDamage"), null);
            recipientView = sourceView.getRecipients().stream()
                    .filter(recipient -> recipient.getRecipient() == chosenRecipient).findFirst()
                    .orElseThrow(() -> new IllegalStateException("FORGE_HUMAN_COMBAT_DAMAGE_STALE_RECIPIENT"));
        }

        final int amount = recipientView.getMinDamage() == recipientView.getMaxDamage()
                ? recipientView.getMaxDamage()
                : chooseNumber(null, Localizer.getInstance().getMessage("lblAssignCombatDamage"),
                        recipientView.getMinDamage(), recipientView.getMaxDamage());
        return new CombatDamageSelection(sourceView.getSource(), recipientView.getRecipient(), amount);
    }

    @Override
    public AmountDistributionSelection chooseAmountDistribution(final AmountDistributionDecisionView decision) {
        if (decision == null || decision.getRecipients().isEmpty()) {
            throw new IllegalStateException("FORGE_HUMAN_AMOUNT_DISTRIBUTION_EMPTY_CORE_VIEW");
        }
        AmountDistributionDecisionView.RecipientView recipientView = decision.getRecipients().get(0);
        if (decision.getRecipients().size() > 1) {
            final FCollection<GameEntity> recipients = new FCollection<>();
            for (AmountDistributionDecisionView.RecipientView recipient : decision.getRecipients()) {
                recipients.add(recipient.getRecipient());
            }
            final GameEntity chosen = chooseSingleEntityForEffect(recipients, null,
                    Localizer.getInstance().getMessage("lblAssignDamage"), null);
            recipientView = decision.getRecipients().stream()
                    .filter(recipient -> recipient.getRecipient() == chosen).findFirst()
                    .orElseThrow(() -> new IllegalStateException("FORGE_HUMAN_AMOUNT_DISTRIBUTION_STALE_RECIPIENT"));
        }
        final int amount = recipientView.getMinAmount() == recipientView.getMaxAmount()
                ? recipientView.getMaxAmount()
                : chooseNumber(null, Localizer.getInstance().getMessage("lblAssignDamage"),
                        recipientView.getMinAmount(), recipientView.getMaxAmount());
        return new AmountDistributionSelection(recipientView.getRecipient(), amount);
    }

'''
replace_once(
    "forge-gui/src/main/java/forge/player/PlayerControllerHuman.java",
    human_marker,
    human_method + human_marker,
)

# AI controller: tactical preference over Core choices only; no legality construction.
replace_once(
    "forge-ai/src/main/java/forge/ai/PlayerControllerAi.java",
    "import forge.game.combat.Combat;\n",
    "import forge.game.combat.Combat;\nimport forge.game.combat.CombatDamageDecisionView;\nimport forge.game.combat.CombatDamageSelection;\n",
)
ai_marker = "    @Override\n    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {\n"
ai_method = r'''    @Override
    public CombatDamageSelection chooseCombatDamage(final CombatDamageDecisionView decision) {
        if (decision == null || decision.getSources().isEmpty()) {
            throw new IllegalStateException("FORGE_AI_COMBAT_DAMAGE_EMPTY_CORE_VIEW");
        }
        CombatDamageDecisionView.SourceView bestSource = null;
        int bestSourceScore = Integer.MIN_VALUE;
        for (CombatDamageDecisionView.SourceView source : decision.getSources()) {
            int score = source.getRemainingDamage();
            if (source.getRecipients().stream().anyMatch(CombatDamageDecisionView.RecipientView::isDefender)) {
                score += 100000;
            }
            if (score > bestSourceScore) {
                bestSource = source;
                bestSourceScore = score;
            }
        }
        if (bestSource == null) {
            throw new IllegalStateException("FORGE_AI_COMBAT_DAMAGE_NO_TACTICAL_SOURCE");
        }

        CombatDamageDecisionView.RecipientView bestRecipient = null;
        int bestRecipientScore = Integer.MIN_VALUE;
        for (CombatDamageDecisionView.RecipientView recipient : bestSource.getRecipients()) {
            int score;
            if (recipient.isDefender()) {
                score = 1000000;
            } else if (recipient.getRecipient() instanceof Card card) {
                score = ComputerUtilCard.evaluateCreature(card);
                final int lethal = recipient.getLethalDamageRemaining();
                if (lethal > 0 && lethal <= bestSource.getRemainingDamage()) {
                    score += 100000;
                }
            } else {
                score = 0;
            }
            if (score > bestRecipientScore) {
                bestRecipient = recipient;
                bestRecipientScore = score;
            }
        }
        if (bestRecipient == null) {
            throw new IllegalStateException("FORGE_AI_COMBAT_DAMAGE_NO_TACTICAL_RECIPIENT");
        }

        int amount;
        if (bestRecipient.isDefender()) {
            amount = bestRecipient.getMaxDamage();
        } else if (bestRecipient.getLethalDamageRemaining() > 0) {
            amount = Math.max(bestRecipient.getMinDamage(),
                    Math.min(bestRecipient.getMaxDamage(), bestRecipient.getLethalDamageRemaining()));
        } else {
            amount = bestRecipient.getMinDamage();
        }
        return new CombatDamageSelection(bestSource.getSource(), bestRecipient.getRecipient(), amount);
    }

    @Override
    public AmountDistributionSelection chooseAmountDistribution(final AmountDistributionDecisionView decision) {
        if (decision == null || decision.getRecipients().isEmpty()) {
            throw new IllegalStateException("FORGE_AI_AMOUNT_DISTRIBUTION_EMPTY_CORE_VIEW");
        }
        AmountDistributionDecisionView.RecipientView best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AmountDistributionDecisionView.RecipientView recipient : decision.getRecipients()) {
            final int score = recipient.getRecipient() instanceof Card card
                    ? ComputerUtilCard.evaluateCreature(card) : 0;
            if (score > bestScore) {
                best = recipient;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalStateException("FORGE_AI_AMOUNT_DISTRIBUTION_NO_TACTICAL_RECIPIENT");
        }
        return new AmountDistributionSelection(best.getRecipient(), best.getMaxAmount());
    }

'''
replace_once("forge-ai/src/main/java/forge/ai/PlayerControllerAi.java", ai_marker, ai_method + ai_marker)

# Noncombat DividerOnResolution: separate Core-owned amount transaction + final validator.
replace_once(
    "forge-game/src/main/java/forge/game/ability/effects/DamageDealEffect.java",
    "import forge.game.player.Player;\n",
    "import forge.game.player.Player;\nimport forge.game.player.AmountDistributionDecision;\n",
)
replace_once(
    "forge-game/src/main/java/forge/game/ability/effects/DamageDealEffect.java",
    "                Player assigningPlayer = players.get(0);\n"
    "                Map<Card, Integer> map = assigningPlayer.getController().assignCombatDamage(sourceLKI, assigneeCards, null, dmg, null, true);\n"
    "                for (Entry<Card, Integer> dt : map.entrySet()) {\n"
    "                    damageMap.put(sourceLKI, dt.getKey(), dt.getValue());\n"
    "                }\n",
    "                Player assigningPlayer = players.get(0);\n"
    "                AmountDistributionDecision distribution = new AmountDistributionDecision(dmg, assigneeCards);\n"
    "                distribution.resolve(assigningPlayer.getController());\n"
    "                for (Entry<GameEntity, Integer> dt : distribution.validatedResult().entrySet()) {\n"
    "                    damageMap.put(sourceLKI, dt.getKey(), dt.getValue());\n"
    "                }\n",
)

# Combat: collect all sources into shared Core transactions, resolve, validate entire step, then atomically commit.
replace_once(
    "forge-game/src/main/java/forge/game/combat/Combat.java",
    "    private boolean assignBlockersDamage(boolean firstStrikeDamage) {\n",
    "    private boolean assignBlockersDamage(boolean firstStrikeDamage, Map<Player, CombatDamageDecision> decisions,\n"
    "            Map<Card, Map<GameEntity, Integer>> staged) {\n",
)
replace_once(
    "forge-game/src/main/java/forge/game/combat/Combat.java",
    "                assignedDamage = true;\n"
    "                Map<Card, Integer> map = assigningPlayer.getController().assignCombatDamage(blocker, attackers, null, damage, defender, divideCombatDamageAsChoose || assigningPlayer != blocker.getController() || !this.legacyOrderCombatants);\n"
    "                for (Entry<Card, Integer> dt : map.entrySet()) {\n"
    "                    // Butcher Orgg\n"
    "                    if (dt.getKey() == null && dt.getValue() > 0) {\n"
    "                        damageMap.get().put(blocker, defender, dt.getValue());\n"
    "                    } else {\n"
    "                        dt.getKey().addAssignedDamage(dt.getValue(), blocker);\n"
    "                        damageMap.get().put(blocker, dt.getKey(), dt.getValue());\n"
    "                    }\n"
    "                }\n",
    "                assignedDamage = true;\n"
    "                final CombatDamageDecision decision = decisions.computeIfAbsent(assigningPlayer,\n"
    "                        p -> new CombatDamageDecision(p, firstStrikeDamage, staged));\n"
    "                decision.addSource(blocker, attackers, defender, damage, false, true, false,\n"
    "                        divideCombatDamageAsChoose && defender != null, divideCombatDamageAsChoose,\n"
    "                        this.legacyOrderCombatants && !divideCombatDamageAsChoose\n"
    "                                && assigningPlayer == blocker.getController());\n",
)
replace_once(
    "forge-game/src/main/java/forge/game/combat/Combat.java",
    "    private boolean assignAttackersDamage(boolean firstStrikeDamage) {\n",
    "    private boolean assignAttackersDamage(boolean firstStrikeDamage, Map<Player, CombatDamageDecision> decisions,\n"
    "            Map<Card, Map<GameEntity, Integer>> staged) {\n",
)
old_attacker = r'''            assignedDamage = true;
            // If the Attacker is unblocked, or it's a trampler and has 0 blockers, deal damage to defender
            if (defender instanceof Card && !((Card) defender).isBattle() && attacker.hasKeyword("Trample:Planeswalker")) {
                if (orderedBlockers == null || orderedBlockers.isEmpty()) {
                    orderedBlockers = new CardCollection((Card) defender);
                } else {
                    orderedBlockers.add((Card) defender);
                }
                defender = getDefenderPlayerByAttacker(attacker);
            }
            if (assignToPlayer) {
                attackers.remove(attacker);
                damageMap.get().put(attacker, defender, damageDealt);
            }
            else if (orderedBlockers == null || orderedBlockers.isEmpty()) {
                attackers.remove(attacker);
                if (assignCombatDamageToCreature) {
                    final SpellAbility emptySA = new SpellAbility.EmptySa(ApiType.Cleanup, attacker);
                    Card chosen = attacker.getController().getController().chooseCardsForEffect(getDefendersCreatures(),
                            emptySA, Localizer.getInstance().getMessage("lblChooseCreature"), 1, 1, false, null).get(0);
                    damageMap.get().put(attacker, chosen, damageDealt);
                } else if (trampler || !band.isBlocked()) { // this is called after declare blockers, no worries 'bout nulls in isBlocked
                    if (defender == null) {
                        defender = getDefenderPlayerByAttacker(attacker);
                        System.err.println("[COMBAT] defender is null, getDefenderPlayerByAttacker(attacker) result: " + defender);
                    }
                    // this will fail if defender is null, and it doesn't allow null values..
                    damageMap.get().put(attacker, defender, damageDealt);
                } // No damage happens if blocked but no blockers left
            } else {
                Map<Card, Integer> map = assigningPlayer.getController().assignCombatDamage(attacker, orderedBlockers, attackers,
                        damageDealt, defender, divideCombatDamageAsChoose || getAttackingPlayer() != assigningPlayer || !this.legacyOrderCombatants);

                attackers.remove(attacker);
                // player wants to assign another first
                if (map == null) {
                    // add to end
                    attackers.add(attacker);
                    continue;
                }

                for (Entry<Card, Integer> dt : map.entrySet()) {
                    if (dt.getKey() == null) {
                        if (dt.getValue() > 0) {
                            if (defender instanceof Card) {
                                ((Card) defender).addAssignedDamage(dt.getValue(), attacker);
                            }
                            damageMap.get().put(attacker, defender, dt.getValue());
                        }
                    } else {
                        dt.getKey().addAssignedDamage(dt.getValue(), attacker);
                        damageMap.get().put(attacker, dt.getKey(), dt.getValue());
                    }
                }
            } // if !hasFirstStrike ...
'''
new_attacker = r'''            assignedDamage = true;
            boolean tramplePlaneswalker = false;
            // Special trample-to-planeswalker mode: the planeswalker becomes the lethal-before-spill recipient.
            if (defender instanceof Card && !((Card) defender).isBattle() && attacker.hasKeyword("Trample:Planeswalker")) {
                tramplePlaneswalker = true;
                if (orderedBlockers == null || orderedBlockers.isEmpty()) {
                    orderedBlockers = new CardCollection((Card) defender);
                } else {
                    orderedBlockers.add((Card) defender);
                }
                defender = getDefenderPlayerByAttacker(attacker);
            }

            final CombatDamageDecision decision = decisions.computeIfAbsent(assigningPlayer,
                    p -> new CombatDamageDecision(p, firstStrikeDamage, staged));
            if (assignToPlayer) {
                attackers.remove(attacker);
                decision.addSource(attacker, Collections.emptyList(), defender, damageDealt, true, false,
                        false, true, true, false);
            }
            else if (orderedBlockers == null || orderedBlockers.isEmpty()) {
                attackers.remove(attacker);
                if (assignCombatDamageToCreature) {
                    final SpellAbility emptySA = new SpellAbility.EmptySa(ApiType.Cleanup, attacker);
                    Card chosen = attacker.getController().getController().chooseCardsForEffect(getDefendersCreatures(),
                            emptySA, Localizer.getInstance().getMessage("lblChooseCreature"), 1, 1, false, null).get(0);
                    decision.addSource(attacker, Collections.singletonList(chosen), null, damageDealt, true,
                            false, false, false, true, false);
                } else if (trampler || !band.isBlocked()) {
                    if (defender == null) {
                        defender = getDefenderPlayerByAttacker(attacker);
                        System.err.println("[COMBAT] defender is null, getDefenderPlayerByAttacker(attacker) result: " + defender);
                    }
                    decision.addSource(attacker, Collections.emptyList(), defender, damageDealt, true,
                            band.isBlocked(), trampler, true, false, false);
                } // A blocked nontrampler with no blockers assigns no combat damage.
            } else {
                attackers.remove(attacker);
                final boolean blockedForAssignment = !divideCombatDamageAsChoose
                        && (band.isBlocked() || tramplePlaneswalker);
                final boolean maySpill = divideCombatDamageAsChoose || trampler || tramplePlaneswalker;
                final boolean legacyOrder = this.legacyOrderCombatants && !divideCombatDamageAsChoose
                        && getAttackingPlayer() == assigningPlayer;
                decision.addSource(attacker, orderedBlockers, defender, damageDealt, true,
                        blockedForAssignment, trampler || tramplePlaneswalker, maySpill,
                        divideCombatDamageAsChoose, legacyOrder);
            } // if !hasFirstStrike ...
'''
replace_once("forge-game/src/main/java/forge/game/combat/Combat.java", old_attacker, new_attacker)
replace_once(
    "forge-game/src/main/java/forge/game/combat/Combat.java",
    "    public final boolean assignCombatDamage(boolean firstStrikeDamage) {\n"
    "        boolean assignedDamage = assignAttackersDamage(firstStrikeDamage);\n"
    "        assignedDamage |= assignBlockersDamage(firstStrikeDamage);\n"
    "        if (!firstStrikeDamage) {\n"
    "            // Clear first strike damage list since it doesn't matter anymore\n"
    "            combatantsThatDealtFirstStrikeDamage.get().clear();\n"
    "        }\n"
    "        return assignedDamage;\n"
    "    }\n",
    "    public final boolean assignCombatDamage(boolean firstStrikeDamage) {\n"
    "        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();\n"
    "        final Map<Player, CombatDamageDecision> decisions = new LinkedHashMap<>();\n"
    "        boolean assignedDamage = assignAttackersDamage(firstStrikeDamage, decisions, staged);\n"
    "        assignedDamage |= assignBlockersDamage(firstStrikeDamage, decisions, staged);\n\n"
    "        for (CombatDamageDecision decision : decisions.values()) {\n"
    "            if (decision.hasSources()) {\n"
    "                decision.resolve(decision.getAssigningPlayer().getController());\n"
    "            }\n"
    "        }\n"
    "        CombatDamageAssignmentValidator.validateAll(this, decisions.values(), staged);\n\n"
    "        // Canonical state-mutation boundary: nothing above this line writes damageMap or assignedDamage.\n"
    "        for (Map.Entry<Card, Map<GameEntity, Integer>> sourceEntry : staged.entrySet()) {\n"
    "            final Card source = sourceEntry.getKey();\n"
    "            for (Map.Entry<GameEntity, Integer> assignment : sourceEntry.getValue().entrySet()) {\n"
    "                if (assignment.getKey() instanceof Card card) {\n"
    "                    card.addAssignedDamage(assignment.getValue(), source);\n"
    "                }\n"
    "                damageMap.get().put(source, assignment.getKey(), assignment.getValue());\n"
    "            }\n"
    "        }\n\n"
    "        if (!firstStrikeDamage) {\n"
    "            combatantsThatDealtFirstStrikeDamage.get().clear();\n"
    "        }\n"
    "        return assignedDamage;\n"
    "    }\n",
)

print("WS40 core patch applied")
