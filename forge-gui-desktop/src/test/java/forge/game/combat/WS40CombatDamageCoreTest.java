package forge.game.combat;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.AmountDistributionDecision;
import forge.game.player.AmountDistributionSelection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native WS40 tests for the Core-owned combat-damage decision/validation boundary. */
public class WS40CombatDamageCoreTest extends AITest {

    private static CombatDamageDecisionView.RecipientView recipient(
            final CombatDamageDecisionView view, final GameEntity entity) {
        for (CombatDamageDecisionView.SourceView source : view.getSources()) {
            for (CombatDamageDecisionView.RecipientView recipient : source.getRecipients()) {
                if (recipient.getRecipient() == entity) {
                    return recipient;
                }
            }
        }
        return null;
    }

    private static CombatDamageSelection selection(final Card source, final GameEntity target, final int amount) {
        return new CombatDamageSelection(source, target, amount);
    }

    private Combat createCombat(final Game game, final Player attacker, final Card source, final GameEntity defender) {
        final Combat combat = new Combat(attacker);
        combat.addAttacker(source, defender);
        game.getPhaseHandler().setCombat(combat);
        return combat;
    }

    private static void validate(final Combat combat, final CombatDamageDecision decision,
            final Map<Card, Map<GameEntity, Integer>> staged) {
        CombatDamageAssignmentValidator.validateAll(combat, List.of(decision), staged);
    }

    @Test
    public void unblockedAttackerHasForcedDefenderAssignmentAndValidatesHeadlessly() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card bear = addCard("Runeclaw Bear", attacker);
        final Combat combat = createCombat(game, attacker, bear, defender);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(bear, List.of(), defender, bear.getNetCombatDamage(), true,
                false, false, true, false, false);

        final CombatDamageDecisionView view = decision.buildView();
        final CombatDamageDecisionView.RecipientView defenderChoice = recipient(view, defender);
        Assert.assertNotNull(defenderChoice);
        Assert.assertEquals(defenderChoice.getMinDamage(), bear.getNetCombatDamage());
        Assert.assertEquals(defenderChoice.getMaxDamage(), bear.getNetCombatDamage());
        decision.apply(selection(bear, defender, bear.getNetCombatDamage()));
        validate(combat, decision, staged);
    }

    @Test
    public void oneBlockerForcesAllNontrampleDamageToBlocker() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card source = addCard("Runeclaw Bear", attacker);
        final Card blocker = addCard("Hill Giant", defender);
        final Combat combat = createCombat(game, attacker, source, defender);
        combat.addBlocker(source, blocker);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(blocker), defender, source.getNetCombatDamage(), true,
                true, false, false, false, false);

        final CombatDamageDecisionView.RecipientView only = recipient(decision.buildView(), blocker);
        Assert.assertNotNull(only);
        Assert.assertEquals(only.getMinDamage(), source.getNetCombatDamage());
        decision.apply(selection(source, blocker, source.getNetCombatDamage()));
        validate(combat, decision, staged);
    }

    @Test
    public void currentRulesSeveralBlockersPermitFreeDivision() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card source = addCard("Hill Giant", attacker);
        final Card first = addCard("Runeclaw Bear", defender);
        final Card second = addCard("Runeclaw Bear", defender);
        final Combat combat = createCombat(game, attacker, source, defender);
        combat.addBlocker(source, first);
        combat.addBlocker(source, second);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(first, second), defender, source.getNetCombatDamage(), true,
                true, false, false, false, false);

        final CombatDamageDecisionView initial = decision.buildView();
        Assert.assertNotNull(recipient(initial, first));
        Assert.assertNotNull(recipient(initial, second));
        decision.apply(selection(source, first, 1));
        decision.apply(selection(source, second, source.getNetCombatDamage() - 1));
        validate(combat, decision, staged);
    }

    @Test
    public void trampleRequiresLethalBeforeDefenderAndAllowsExactOrOverLethal() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card source = addCard("Colossal Dreadmaw", attacker);
        final Card blocker = addCard("Runeclaw Bear", defender);
        final Combat combat = createCombat(game, attacker, source, defender);
        combat.addBlocker(source, blocker);

        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(blocker), defender, source.getNetCombatDamage(), true,
                true, true, true, false, false);
        Assert.assertNull(recipient(decision.buildView(), defender), "defender must not be legal before lethal");
        decision.apply(selection(source, blocker, 2));
        Assert.assertNotNull(recipient(decision.buildView(), defender), "defender becomes legal after lethal");
        decision.apply(selection(source, defender, source.getNetCombatDamage() - 2));
        validate(combat, decision, staged);

        final Map<Card, Map<GameEntity, Integer>> stagedOver = new LinkedHashMap<>();
        final CombatDamageDecision over = new CombatDamageDecision(attacker, false, stagedOver);
        over.addSource(source, List.of(blocker), defender, source.getNetCombatDamage(), true,
                true, true, true, false, false);
        over.apply(selection(source, blocker, 3));
        over.apply(selection(source, defender, source.getNetCombatDamage() - 3));
        validate(combat, over, stagedOver);
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "FORGE_COMBAT_DAMAGE_ILLEGAL_RECIPIENT")
    public void prematureTrampleSpillFailsClosedAtDecisionApi() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card source = addCard("Colossal Dreadmaw", attacker);
        final Card blocker = addCard("Hill Giant", defender);
        final Combat combat = createCombat(game, attacker, source, defender);
        combat.addBlocker(source, blocker);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(blocker), defender, source.getNetCombatDamage(), true,
                true, true, true, false, false);
        decision.apply(selection(source, defender, 1));
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "FORGE_COMBAT_DAMAGE_ILLEGAL_AMOUNT")
    public void overAssignmentSelectionFailsClosed() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card source = addCard("Runeclaw Bear", attacker);
        final Combat combat = createCombat(game, attacker, source, defender);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(), defender, source.getNetCombatDamage(), true,
                false, false, true, false, false);
        decision.apply(selection(source, defender, source.getNetCombatDamage() + 1));
    }

    @Test(expectedExceptions = IllegalStateException.class,
            expectedExceptionsMessageRegExp = "FORGE_COMBAT_DAMAGE_UNDERASSIGNED_TRANSACTION")
    public void finalValidatorRejectsUnderAssignment() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card source = addCard("Hill Giant", attacker);
        final Card blocker = addCard("Runeclaw Bear", defender);
        final Combat combat = createCombat(game, attacker, source, defender);
        combat.addBlocker(source, blocker);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(blocker), defender, source.getNetCombatDamage(), true,
                true, false, false, false, false);
        decision.apply(selection(source, blocker, 1));
        validate(combat, decision, staged);
    }

    @Test(expectedExceptions = IllegalStateException.class,
            expectedExceptionsMessageRegExp = "FORGE_COMBAT_DAMAGE_RECIPIENT_NOT_AUTHORIZED")
    public void finalValidatorRejectsMalformedRecipientEvenWhenBypassingDecisionApi() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card source = addCard("Runeclaw Bear", attacker);
        final Card blocker = addCard("Hill Giant", defender);
        final Card foreign = addCard("Grizzly Bears", defender);
        final Combat combat = createCombat(game, attacker, source, defender);
        combat.addBlocker(source, blocker);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(blocker), defender, source.getNetCombatDamage(), true,
                true, false, false, false, false);
        staged.get(source).put(foreign, source.getNetCombatDamage());
        validate(combat, decision, staged);
    }

    @Test(expectedExceptions = IllegalStateException.class,
            expectedExceptionsMessageRegExp = "FORGE_COMBAT_DAMAGE_STALE_SOURCE")
    public void finalValidatorRejectsStaleSource() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card source = addCard("Runeclaw Bear", attacker);
        final Combat combat = createCombat(game, attacker, source, defender);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(), defender, source.getNetCombatDamage(), true,
                false, false, true, false, false);
        decision.apply(selection(source, defender, source.getNetCombatDamage()));
        source.getZone().remove(source);
        attacker.getZone(ZoneType.Graveyard).add(source);
        validate(combat, decision, staged);
    }

    @Test
    public void sameStepDeathtouchAssignmentSatisfiesSharedBlockerForTrample() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card deathtouchSource = addCard("Typhoid Rats", attacker);
        final Card trampler = addCard("Colossal Dreadmaw", attacker);
        final Card sharedBlocker = addCard("Palace Guard", defender);
        final Combat combat = new Combat(attacker);
        combat.addAttacker(deathtouchSource, defender);
        combat.addAttacker(trampler, defender);
        combat.addBlocker(deathtouchSource, sharedBlocker);
        combat.addBlocker(trampler, sharedBlocker);
        game.getPhaseHandler().setCombat(combat);

        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(deathtouchSource, List.of(sharedBlocker), defender,
                deathtouchSource.getNetCombatDamage(), true, true, false, false, false, false);
        decision.addSource(trampler, List.of(sharedBlocker), defender,
                trampler.getNetCombatDamage(), true, true, true, true, false, false);
        decision.apply(selection(deathtouchSource, sharedBlocker, deathtouchSource.getNetCombatDamage()));
        Assert.assertNotNull(recipient(decision.buildView(), defender),
                "same-step deathtouch assignment must satisfy lethal-before-spill");
        decision.apply(selection(trampler, defender, trampler.getNetCombatDamage()));
        validate(combat, decision, staged);
    }

    @Test
    public void firstStrikeAndNormalDamageUseDistinctDecisionTransactions() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Map<Card, Map<GameEntity, Integer>> firstStage = new LinkedHashMap<>();
        final Map<Card, Map<GameEntity, Integer>> normalStage = new LinkedHashMap<>();
        final CombatDamageDecision first = new CombatDamageDecision(attacker, true, firstStage);
        final CombatDamageDecision normal = new CombatDamageDecision(attacker, false, normalStage);
        Assert.assertTrue(first.isFirstStrikeDamage());
        Assert.assertFalse(normal.isFirstStrikeDamage());
        Assert.assertNotSame(firstStage, normalStage);
    }

    @Test
    public void playerPlaneswalkerAndBattleAreNativeDefenderRecipients() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card playerSource = addCard("Runeclaw Bear", attacker);
        final Card walkerSource = addCard("Runeclaw Bear", attacker);
        final Card battleSource = addCard("Runeclaw Bear", attacker);
        final Card planeswalker = addCard("Jace, the Mind Sculptor", defender);
        final Card battle = addCard("Invasion of Zendikar", defender);

        assertDefenderValid(game, attacker, playerSource, defender);
        assertDefenderValid(game, attacker, walkerSource, planeswalker);
        assertDefenderValid(game, attacker, battleSource, battle);
    }

    private void assertDefenderValid(final Game game, final Player attacker, final Card source,
            final GameEntity defender) {
        final Combat combat = createCombat(game, attacker, source, defender);
        final Map<Card, Map<GameEntity, Integer>> staged = new LinkedHashMap<>();
        final CombatDamageDecision decision = new CombatDamageDecision(attacker, false, staged);
        decision.addSource(source, List.of(), defender, source.getNetCombatDamage(), true,
                false, false, true, false, false);
        decision.apply(selection(source, defender, source.getNetCombatDamage()));
        validate(combat, decision, staged);
    }

    @Test
    public void legacyOrderIsIsolatedFromCurrentRulesMode() {
        final Game game = initAndCreateGame();
        final Player attacker = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Card currentSource = addCard("Hill Giant", attacker);
        final Card legacySource = addCard("Hill Giant", attacker);
        final Card first = addCard("Runeclaw Bear", defender);
        final Card second = addCard("Runeclaw Bear", defender);

        final Map<Card, Map<GameEntity, Integer>> currentStaged = new LinkedHashMap<>();
        final CombatDamageDecision current = new CombatDamageDecision(attacker, false, currentStaged);
        current.addSource(currentSource, List.of(first, second), defender, currentSource.getNetCombatDamage(),
                true, true, false, false, false, false);
        Assert.assertNotNull(recipient(current.buildView(), first));
        Assert.assertNotNull(recipient(current.buildView(), second));

        final Map<Card, Map<GameEntity, Integer>> legacyStaged = new LinkedHashMap<>();
        final CombatDamageDecision legacy = new CombatDamageDecision(attacker, false, legacyStaged);
        legacy.addSource(legacySource, List.of(first, second), defender, legacySource.getNetCombatDamage(),
                true, true, false, false, false, true);
        Assert.assertNotNull(recipient(legacy.buildView(), first));
        Assert.assertNull(recipient(legacy.buildView(), second));
        legacy.apply(selection(legacySource, first, 2));
        Assert.assertNotNull(recipient(legacy.buildView(), second));
    }

    @Test
    public void noncombatDistributionHasSeparateExactTotalFailClosedBoundary() {
        final Game game = initAndCreateGame();
        final Player player = game.getPlayers().get(1);
        final Card first = addCard("Runeclaw Bear", game.getPlayers().get(0));
        final Card second = addCard("Hill Giant", game.getPlayers().get(0));
        final AmountDistributionDecision distribution = new AmountDistributionDecision(3, List.of(first, second));
        distribution.apply(new AmountDistributionSelection(first, 1));
        distribution.apply(new AmountDistributionSelection(second, 2));
        Assert.assertEquals(distribution.validatedResult().get(first).intValue(), 1);
        Assert.assertEquals(distribution.validatedResult().get(second).intValue(), 2);
        Assert.assertEquals(player.getGame(), game);
    }

    @Test(expectedExceptions = IllegalArgumentException.class,
            expectedExceptionsMessageRegExp = "FORGE_AMOUNT_DISTRIBUTION_ILLEGAL_AMOUNT")
    public void noncombatDistributionRejectsOverAssignment() {
        final Game game = initAndCreateGame();
        final Card target = addCard("Runeclaw Bear", game.getPlayers().get(0));
        final AmountDistributionDecision distribution = new AmountDistributionDecision(3, List.of(target));
        distribution.apply(new AmountDistributionSelection(target, 4));
    }
}
