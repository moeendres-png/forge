package forge.bridge;

import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * Energy, return-to-hand (ninjutsu), reveal and exert costs are the payer's
 * choice, framed as COST_SELECTION; so is the emerge sacrifice.
 *
 * <p>Each of these cost parts used to decline in BridgeCostDecisionMaker and
 * was classified COMPLEX_COST, so any priority frame with such an ability in
 * reach (a Longtusk Cub on the battlefield, a ninja in hand during combat) was
 * UNSUPPORTED and the pilot was halted. The legal lists mirror
 * HumanCostDecision.</p>
 */
public class NonManaCostPartsTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static DecisionFrame.Option pick(DecisionFrame frame,
            Predicate<DecisionFrame.Option> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
            seen.append('[').append(option.actionType).append('|').append(option.label)
                    .append('|').append(option.sourceCardName).append(']');
        }
        throw new AssertionError(what + " not offered; kind=" + frame.kind + " status="
                + frame.status + " reason=" + frame.reason + " options=" + seen);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static boolean has(DecisionFrame frame, String actionType) {
        return frame.options.stream().anyMatch(o -> actionType.equals(o.actionType));
    }

    private static Card find(BridgeSession session, int seat, String name, ZoneType zone) {
        for (Card card : session.getGame().getPlayers().get(seat).getCardsIn(zone)) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    private static BridgeTestSupport.ConstructedGame game(String id) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(id, 2);
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        return constructed;
    }

    /** Floats mana from each named land in turn, returning the next p1 priority frame. */
    private static DecisionFrame floatMana(BridgeSession session, DecisionFrame frame,
            String land, int count) {
        for (int i = 0; i < count; i++) {
            Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED,
                    "priority frame blocked: " + frame.reason);
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && land.equals(o.sourceCardName), land + " tap"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame, "no frame");
        }
        return frame;
    }

    /** Passes until the stack is empty and p1 has priority again. */
    private static void resolveStack(BridgeSession session) {
        for (int i = 0; i < 20 && !session.getGame().getStack().isEmpty(); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.kind, DecisionFrame.Kind.PRIORITY,
                    "unexpected " + f.kind + " " + f.reason);
            submit(session, f, pick(f, o -> o.isPass, "pass"));
        }
        Assert.assertTrue(session.getGame().getStack().isEmpty(), "stack resolved");
    }

    // ---- Energy: Longtusk Cub, "Pay {E}{E}: Put a +1/+1 counter on it." ----

    private void longtuskCub(boolean pay) {
        final BridgeTestSupport.ConstructedGame constructed = game("energy-" + pay);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Longtusk Cub", ZoneType.Battlefield);
        constructed.game.getPlayers().get(0).setCounters(CounterEnumType.ENERGY, 2, null, false);
        BridgeTestSupport.launchConstructed(constructed);
        final DecisionFrame main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertEquals(main.status, DecisionFrame.Status.SUPPORTED,
                "priority frame blocked: " + main.reason);
        submit(session, main, pick(main, o -> "activate_ability".equals(o.actionType)
                && "Longtusk Cub".equals(o.sourceCardName), "Cub ability"));
        final DecisionFrame cost = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(cost, "no frame");
        Assert.assertTrue(has(cost, "cost_pay_energy"), "energy payment framed: " + cost.kind);
        submit(session, cost, pick(cost, o -> Boolean.valueOf(pay).equals(o.confirmValue),
                pay ? "pay" : "decline"));
        if (pay) {
            resolveStack(session);
        }
        final Card cub = find(session, 0, "Longtusk Cub", ZoneType.Battlefield);
        Assert.assertEquals(cub.getCounters(CounterEnumType.P1P1), pay ? 1 : 0);
        Assert.assertEquals(session.getGame().getPlayers().get(0)
                .getCounters(CounterEnumType.ENERGY), pay ? 0 : 2);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void payingEnergyGrowsLongtuskCub() {
        longtuskCub(true);
    }

    @Test(timeOut = 300000)
    public void decliningEnergyRollsBackTheActivation() {
        longtuskCub(false);
    }

    // ---- Reveal: Induce Despair, "reveal a creature card from your hand" ----

    @Test(timeOut = 300000)
    public void pilotChoosesWhichCreatureInduceDespairReveals() {
        final BridgeTestSupport.ConstructedGame constructed = game("reveal-despair");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Induce Despair", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Craw Wurm", ZoneType.Hand);
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Craw Wurm", ZoneType.Battlefield);
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        frame = floatMana(session, frame, "Swamp", 3);
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Induce Despair".equals(o.sourceCardName), "cast Induce Despair"));
        boolean revealFramed = false;
        for (int i = 0; i < 20 && session.getGame().getStack().isEmpty(); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (has(f, "cost_reveal")) {
                revealFramed = true;
                Assert.assertTrue(f.options.stream().anyMatch(o -> o.label.contains("Runeclaw Bear")),
                        "the smaller creature is a legal reveal too");
                submit(session, f, pick(f, o -> o.label.contains("Craw Wurm")
                        && !o.label.contains("Runeclaw Bear"), "reveal Craw Wurm"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, pick(f, o -> o.label != null && o.label.contains("Craw Wurm"),
                        "target the opposing Craw Wurm"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, BridgeTestSupport.equivalentPayment(f));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.status + " " + f.reason);
            }
        }
        Assert.assertTrue(revealFramed, "the reveal was the pilot's choice");
        resolveStack(session);
        Assert.assertNull(find(session, 1, "Craw Wurm", ZoneType.Battlefield),
                "revealing Craw Wurm (mana value 6) gives -6/-6 and kills the 6/4");
        session.shutdown(5000);
    }

    // ---- Exert: Fervent Paincaster, "{T}, Exert: 1 damage to target creature" ----

    @Test(timeOut = 300000)
    public void exertCostIsConfirmedAndApplied() {
        final BridgeTestSupport.ConstructedGame constructed = game("exert-paincaster");
        final BridgeSession session = constructed.session;
        final Card paincaster = BridgeTestSupport.addCard(constructed.game, 0,
                "Fervent Paincaster", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Raging Goblin", ZoneType.Battlefield);
        BridgeTestSupport.launchConstructed(constructed);
        paincaster.setSickness(false);
        final DecisionFrame main = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertEquals(main.status, DecisionFrame.Status.SUPPORTED,
                "priority frame blocked: " + main.reason);
        submit(session, main, pick(main, o -> "activate_ability".equals(o.actionType)
                && "Fervent Paincaster".equals(o.sourceCardName)
                && o.label != null && o.label.contains("Exert"), "exert ability"));
        boolean costOrderFramed = false;
        boolean exertFramed = false;
        for (int i = 0; i < 20 && session.getGame().getStack().isEmpty(); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.kind == DecisionFrame.Kind.ORDER_CHOICE && has(f, "cost_order")) {
                costOrderFramed = true;
                Assert.assertTrue(f.options.size() >= 2,
                        "tap+exert must expose more than one payment order");
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.startsWith("Pay order: #1 "),
                        "reverse the scripted cost order"));
            } else if (has(f, "cost_exert")) {
                exertFramed = true;
                submit(session, f, pick(f, o -> Boolean.TRUE.equals(o.confirmValue), "exert"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.contains("Raging Goblin"), "target the goblin"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.status + " " + f.reason);
            }
        }
        Assert.assertTrue(costOrderFramed, "multi-part payment order was the pilot's choice");
        Assert.assertTrue(exertFramed, "exerting was the pilot's choice");
        resolveStack(session);
        Assert.assertTrue(paincaster.isTapped(), "tapped");
        Assert.assertTrue(paincaster.isExertedBy(session.getGame().getPlayers().get(0)),
                "exerted");
        Assert.assertNull(find(session, 1, "Raging Goblin", ZoneType.Battlefield),
                "1 damage kills the 1/1");
        session.shutdown(5000);
    }

    // ---- Return: ninjutsu, "Return an unblocked attacker you control to hand" ----

    @Test(timeOut = 300000)
    public void ninjutsuReturnsTheChosenUnblockedAttacker() {
        final BridgeTestSupport.ConstructedGame constructed = game("ninjutsu");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Raging Goblin", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Ninja of the Deep Hours", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        boolean attacked = false;
        boolean ninjutsu = false;
        boolean returnFramed = false;
        int floated = 0;
        for (int i = 0; i < 60; i++) {
            final PhaseType phase = session.getGame().getPhaseHandler().getPhase();
            if (ninjutsu && session.getGame().getStack().isEmpty()
                    && find(session, 0, "Ninja of the Deep Hours", ZoneType.Battlefield) != null) {
                break;
            }
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS) {
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.contains("Raging Goblin"), "attack with the goblin"));
                attacked = true;
            } else if (has(f, "cost_return")) {
                returnFramed = true;
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.contains("Raging Goblin"), "return the goblin"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, BridgeTestSupport.equivalentPayment(f));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY && f.actorPlayerId.equals("p1")
                    && attacked && !ninjutsu && phase == PhaseType.COMBAT_DECLARE_BLOCKERS) {
                Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                        "a ninja in hand must not block the attacker's priority: " + f.reason);
                if (floated < 2) {
                    submit(session, f, pick(f, o -> "activate_ability".equals(o.actionType)
                            && "Island".equals(o.sourceCardName), "Island tap"));
                    floated++;
                } else {
                    submit(session, f, pick(f, o -> "Ninja of the Deep Hours"
                            .equals(o.sourceCardName), "ninjutsu"));
                    ninjutsu = true;
                }
            } else if (f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.status + " " + f.reason
                        + " in " + phase);
            }
        }
        Assert.assertTrue(returnFramed, "the returned attacker was the pilot's choice");
        final Card ninja = find(session, 0, "Ninja of the Deep Hours", ZoneType.Battlefield);
        Assert.assertNotNull(ninja, "the ninja entered");
        Assert.assertTrue(ninja.isTapped(), "tapped");
        Assert.assertTrue(session.getGame().getCombat() != null
                && session.getGame().getCombat().isAttacking(ninja), "and attacking");
        Assert.assertNotNull(find(session, 0, "Raging Goblin", ZoneType.Hand),
                "the goblin went back to hand");
        session.shutdown(5000);
    }

    // ---- Emerge: Wretched Gryff, "Emerge {5}{U}" (CR 702.119) ----

    private void emergeGryff(String sacrificed, boolean resolves) {
        final BridgeTestSupport.ConstructedGame constructed = game("emerge-" + sacrificed);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Wretched Gryff", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Craw Wurm", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        frame = floatMana(session, frame, "Island", 1);
        Assert.assertEquals(frame.status, DecisionFrame.Status.SUPPORTED,
                "priority frame blocked: " + frame.reason);
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Wretched Gryff".equals(o.sourceCardName)
                && o.label != null && o.label.contains("{5}{U}"), "cast with emerge"));
        boolean sacrificeFramed = false;
        for (int i = 0; i < 20; i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            if (f.kind == DecisionFrame.Kind.PRIORITY && f.actorPlayerId.equals("p1")
                    && !sacrificeFramed) {
                throw new AssertionError("emerge cast returned to priority without a choice");
            }
            if (has(f, "sacrifice")) {
                sacrificeFramed = true;
                submit(session, f, pick(f, o -> o.label != null && o.label.contains(sacrificed)
                        && !o.label.contains(";" + (sacrificed.equals("Craw Wurm")
                                ? "Grizzly Bears" : "Craw Wurm")), "sacrifice " + sacrificed));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, BridgeTestSupport.equivalentPayment(f));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                break;
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.status + " " + f.reason);
            }
        }
        Assert.assertTrue(sacrificeFramed, "the emerge sacrifice was the pilot's choice");
        if (resolves) {
            resolveStack(session);
            Assert.assertNotNull(find(session, 0, "Wretched Gryff", ZoneType.Battlefield),
                    "Gryff cast for {U} after sacrificing the mana value 6 Wurm");
            Assert.assertNotNull(find(session, 0, "Craw Wurm", ZoneType.Graveyard), "Wurm sacrificed");
            Assert.assertNotNull(find(session, 0, "Grizzly Bears", ZoneType.Battlefield), "Bears kept");
        } else {
            Assert.assertNotNull(find(session, 0, "Wretched Gryff", ZoneType.Hand),
                    "{3}{U} unaffordable with one Island: the cast rolls back");
            Assert.assertNotNull(find(session, 0, "Grizzly Bears", ZoneType.Battlefield),
                    "and the Bears are not sacrificed");
            Assert.assertNotNull(find(session, 0, "Craw Wurm", ZoneType.Battlefield), "Wurm kept");
        }
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void emergeSacrificesTheChosenCreatureAndReducesTheCost() {
        emergeGryff("Craw Wurm", true);
    }

    @Test(timeOut = 300000)
    public void unaffordableEmergeRollsBackWithoutSacrificing() {
        emergeGryff("Grizzly Bears", false);
    }

    // ---- "unless" damage: Blazing Salvo, 3 to the creature unless its
    // controller takes 5. Choosing to pay used to be silently "not paid". ----

    private void blazingSalvo(boolean pay) {
        final BridgeTestSupport.ConstructedGame constructed = game("salvo-" + pay);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Blazing Salvo", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        final Card wurm = BridgeTestSupport.addCard(constructed.game, 1, "Craw Wurm",
                ZoneType.Battlefield);
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        frame = floatMana(session, frame, "Mountain", 1);
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Blazing Salvo".equals(o.sourceCardName), "cast Blazing Salvo"));
        boolean asked = false;
        for (int i = 0; i < 30 && (session.getGame().getStack().size() > 0 || !asked); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (has(f, "pay_to_prevent")) {
                Assert.assertEquals(f.actorPlayerId, "p2", "the creature's controller decides");
                asked = true;
                submit(session, f, pick(f, o -> Boolean.valueOf(pay).equals(o.confirmValue),
                        pay ? "take 5" : "decline"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, pick(f, o -> o.label != null && o.label.contains("Craw Wurm"),
                        "target the Wurm"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, BridgeTestSupport.equivalentPayment(f));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        Assert.assertTrue(asked, "the payer was asked");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), pay ? 35 : 40);
        Assert.assertEquals(wurm.getDamage(), pay ? 0 : 3, pay ? "damage prevented" : "3 dealt");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void takingSalvoDamageSparesTheCreature() {
        blazingSalvo(true);
    }

    @Test(timeOut = 300000)
    public void decliningSalvoDamagesTheCreature() {
        blazingSalvo(false);
    }

    // ---- Draw cost: Windrider Wizard, "you may draw a card. If you do, discard" ----

    private void windriderLoot(boolean loot) {
        final BridgeTestSupport.ConstructedGame constructed = game("loot-" + loot);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Windrider Wizard", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Shock", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Craw Wurm", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.launchConstructed(constructed);
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final int library = session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Library).size();
        frame = floatMana(session, frame, "Mountain", 1);
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Shock".equals(o.sourceCardName), "cast Shock"));
        boolean asked = false;
        for (int i = 0; i < 30 && (session.getGame().getStack().size() > 0 || !asked); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (has(f, "cost_draw")) {
                asked = true;
                submit(session, f, pick(f, o -> Boolean.valueOf(loot).equals(o.confirmValue),
                        loot ? "draw" : "do not draw"));
            } else if (has(f, "trigger_play")) {
                // "you may": the engine first asks whether to use the trigger.
                submit(session, f, pick(f, o -> o.label != null && o.label.endsWith("[Yes]"),
                        "use the trigger"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, pick(f, o -> o.label != null && o.label.contains("p2"), "p2"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, BridgeTestSupport.equivalentPayment(f));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else if (f.options.stream().anyMatch(o -> o.label != null
                    && o.label.contains("Craw Wurm"))) {
                submit(session, f, pick(f, o -> o.label.contains("Craw Wurm"), "discard the Wurm"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason + " "
                        + f.options.stream().map(o -> o.actionType + "|" + o.label).toList());
            }
        }
        Assert.assertTrue(asked, "drawing was the controller's choice");
        Assert.assertEquals(session.getGame().getPlayers().get(0).getCardsIn(ZoneType.Library).size(),
                loot ? library - 1 : library);
        Assert.assertEquals(find(session, 0, "Craw Wurm", ZoneType.Graveyard) != null, loot,
                loot ? "looted away the Wurm" : "no discard without the draw");
        Assert.assertEquals(session.getGame().getPlayers().get(1).getLife(), 38, "Shock resolved");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void windriderWizardLootsWhenChosen() {
        windriderLoot(true);
    }

    @Test(timeOut = 300000)
    public void windriderWizardMayDeclineTheLoot() {
        windriderLoot(false);
    }
}
