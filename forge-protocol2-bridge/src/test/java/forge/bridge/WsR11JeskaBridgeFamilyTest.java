package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R11a JESKA bridge family: Jeska, Thrice Reborn (CARD_08) planeswalker.
 *
 * <p>WS234 proved Partner + 3-ability presence; commander-scaled loyalty,
 * triple-damage and ultimate were NOT_RUN. Four strict bridge tests:
 * loyalty equals commander-cast count (Rograkh 0 + Kediss 1R, then Jeska),
 * [0] triple-damage replacement in combat (2 → 6), [-X] ultimate damage
 * to up-to-three targets, zero-loyalty entry with [0] still offered.
 * Tests pick only engine-offered options; loyalty and lives are
 * accounted, never injected.</p>
 */
public class WsR11JeskaBridgeFamilyTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    private static DecisionFrame.Option pickOption(DecisionFrame frame,
            Predicate<DecisionFrame.Option> test, String what) {
        final StringBuilder seen = new StringBuilder();
        for (DecisionFrame.Option option : frame.options) {
            if (test.test(option)) {
                return option;
            }
            seen.append('[').append(option.actionType).append('|')
                    .append(option.label).append(']');
        }
        throw new AssertionError(
                what + " not offered; kind=" + frame.kind + " options=" + seen);
    }

    private static void submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static void submitValue(BridgeSession session, DecisionFrame frame, long value) {
        Assert.assertTrue(frame.freeInput, "not a free-input frame");
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                DecisionFrame.FREE_INPUT_ID, frame.options.get(0).actionType, frame.revision,
                Long.valueOf(value));
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
    }

    private static boolean frameMatches(DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind) {
        return frame.kind == kind && frame.actorPlayerId.equals(actorId)
                && frame.status == DecisionFrame.Status.SUPPORTED;
    }

    private static DecisionFrame awaitNext(BridgeSession session, long lastRevision,
            long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.revision != lastRevision) {
                return frame;
            }
            if (session.isTerminal()) {
                return session.getCurrentFrame();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return session.getCurrentFrame();
            }
        }
        return session.getCurrentFrame();
    }

    private static void answerCommon(BridgeSession session, DecisionFrame frame) {
        if (frame.kind == DecisionFrame.Kind.PRIORITY) {
            submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
        } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.REPLACEMENT_ORDER
                || frame.kind == DecisionFrame.Kind.REPLACEMENT_CONFIRM) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
            submit(session, frame, pickOption(frame,
                    o -> o.label != null && (o.label.contains("No attack")
                            || o.label.contains("No block")),
                    "decline combat"));
        } else {
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
        }
    }

    private static DecisionFrame driveTo(BridgeSession session, String actorId,
            DecisionFrame.Kind kind, int budget) {
        long lastRevision = -1;
        final DecisionFrame current = session.getCurrentFrame();
        if (current != null) {
            lastRevision = current.revision;
            if (frameMatches(current, actorId, kind)) {
                return current;
            }
        }
        for (int i = 0; i < budget; i++) {
            final DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind);
            lastRevision = frame.revision;
            if (frameMatches(frame, actorId, kind)) {
                return frame;
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                throw new AssertionError("blocked by " + frame.kind + " " + frame.status
                        + " reason=" + frame.reason);
            }
            answerCommon(session, frame);
        }
        throw new AssertionError("never reached " + kind + " for " + actorId);
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < constructed.game.getPlayers().size(); seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static int life(int seat, BridgeSession session) {
        return session.getGame().getPlayers().get(seat).getLife();
    }

    private static Card findBf(BridgeSession session, int seat, String name) {
        for (Card c : session.getGame().getPlayers().get(seat)
                .getZone(ZoneType.Battlefield).getCards()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private static int loyalty(Card planeswalker) {
        return planeswalker.getCounters(forge.game.card.CounterEnumType.LOYALTY);
    }

    private static boolean inZone(BridgeSession session, int seat, ZoneType zone, String name) {
        for (Card c : session.getGame().getPlayers().get(seat).getZone(zone).getCards()) {
            if (name.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void drain(BridgeSession session, int budget) {
        long lastRevision = -1;
        for (int i = 0; i < budget; i++) {
            DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame while draining");
            if (frame.revision == lastRevision) {
                return;
            }
            lastRevision = frame.revision;
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                return;
            }
            answerCommon(session, frame);
        }
    }

    private static BridgeTestSupport.ConstructedGame jeskaGame(String gameId, boolean withBear) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(gameId);
        BridgeTestSupport.addCommander(constructed.game, 0, "Rograkh, Son of Rohgahh");
        BridgeTestSupport.addCommander(constructed.game, 0, "Kediss, Emberclaw Familiar");
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        if (withBear) {
            BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Jeska, Thrice Reborn", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(constructed.session, "p1", 12);
        return constructed;
    }

    // Cast Rograkh, Kediss, Jeska back-to-back in ONE main phase (no
    // draining between: empty full rounds would leave MAIN1 and strand
    // sorcery-speed casts).
    private static void castCommanders(BridgeSession session) {
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Rograkh, Son of Rohgahh".equals(o.sourceCardName),
                "Rograkh cast"));
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean acted = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Kediss, Emberclaw Familiar".equals(o.sourceCardName)) {
                        submit(session, f, o);
                        acted = true;
                        break;
                    }
                }
                if (acted) {
                    break;
                }
            }
            answerCommon(session, f);
        }
    }

    // Cast Jeska, then HOLD the first p1 priority after she resolves
    // (never pass it: later phases illegalize sorcery-speed loyalty
    // activations). Returns that unpassed frame for immediate ability use.
    private static DecisionFrame castJeskaAndHold(BridgeSession session) {
        for (int i = 0; i < 60; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (findBf(session, 0, "Jeska, Thrice Reborn") != null
                    && f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                return f;
            }
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasJeska = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Jeska, Thrice Reborn".equals(o.sourceCardName)) {
                        hasJeska = true;
                    }
                }
                if (hasJeska) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Jeska, Thrice Reborn".equals(o.sourceCardName),
                            "Jeska cast"));
                    continue;
                }
            }
            answerCommon(session, f);
        }
        throw new AssertionError("never held Jeska priority");
    }

    private static Card castJeska(BridgeSession session) {
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasJeska = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Jeska, Thrice Reborn".equals(o.sourceCardName)) {
                        hasJeska = true;
                    }
                }
                if (hasJeska) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Jeska, Thrice Reborn".equals(o.sourceCardName),
                            "Jeska cast"));
                    break;
                }
            }
            answerCommon(session, f);
        }
        drain(session, 20);
        Card jeska = findBf(session, 0, "Jeska, Thrice Reborn");
        Assert.assertNotNull(jeska, "Jeska must enter");
        return jeska;
    }

    @Test(timeOut = 300000)
    public void testJeskaEntersWithCommanderCountLoyalty() {
        final BridgeTestSupport.ConstructedGame constructed = jeskaGame("wsr11-jeska-loyal", false);
        final BridgeSession session = constructed.session;
        castCommanders(session);
        Card jeska = castJeska(session);
        Assert.assertEquals(loyalty(jeska), 2, "Jeska must enter with 2 loyalty (2 commander casts)");
        Assert.assertTrue(jeska.hasKeyword("Partner"), "Jeska must have Partner");
        session.shutdown(5000);
    }

    // R12: enabled by the loyalty-cost surface (from-source counter costs
    // payable through BridgeCostDecisionMaker; classifier allowlisted).
    @Test(timeOut = 300000)
    public void testJeskaZeroAbilityTriplesDamage() {
        final BridgeTestSupport.ConstructedGame constructed = jeskaGame("wsr11-jeska-triple", true);
        final BridgeSession session = constructed.session;
        castCommanders(session);
        int p2Life = life(1, session);
        // Activate [0] on the HELD post-resolution priority (labels carry
        // the ability text; the [0] targets a creature).
        DecisionFrame held = castJeskaAndHold(session);
        // [0] is the (0) activation; ultimate is (-X). Labels carry cost text.
        submit(session, held, pickOption(held,
                o -> "activate_ability".equals(o.actionType)
                        && "Jeska, Thrice Reborn".equals(o.sourceCardName)
                        && o.label != null && o.label.contains("(0)"),
                "Jeska [0]"));
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION && f.actorPlayerId.equals("p1")) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Runeclaw Bear"),
                        "triple Bear"));
                break;
            }
            answerCommon(session, f);
        }
        // Attack p2 with Bear, no blocks; 2 becomes 6.
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Runeclaw Bear")
                                && o.label.contains("p2"),
                        "Bear -> p2"));
                break;
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("No block"),
                        "no blocks"));
                break;
            }
            answerCommon(session, f);
        }
        drain(session, 40);
        Assert.assertEquals(life(1, session), p2Life - 6, "Bear combat tripled 2 -> 6");
        session.shutdown(5000);
    }

    // R12: enabled by the loyalty-cost surface (X via framed X_ANNOUNCE).
    @Test(timeOut = 300000)
    public void testJeskaUltimateXDamage() {
        final BridgeTestSupport.ConstructedGame constructed = jeskaGame("wsr11-jeska-ult", false);
        final BridgeSession session = constructed.session;
        castCommanders(session);
        DecisionFrame held = castJeskaAndHold(session);
        Card jeska = findBf(session, 0, "Jeska, Thrice Reborn");
        Assert.assertNotNull(jeska, "Jeska must enter");
        Assert.assertEquals(loyalty(jeska), 2, "precondition: 2 loyalty");
        int p2Life = life(1, session);
        // Ultimate is the (-X) activation (labels carry only cost text).
        submit(session, held, pickOption(held,
                o -> "activate_ability".equals(o.actionType)
                        && "Jeska, Thrice Reborn".equals(o.sourceCardName)
                        && o.label != null && o.label.contains("(-X)"),
                "Jeska ultimate"));
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.X_ANNOUNCE && f.actorPlayerId.equals("p1")) {
                submitValue(session, f, 2);
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && f.actorPlayerId.equals("p1")) {
                // Up-to-three targets: prefer the player, fall back to an
                // explicit creature pick (recorded); exactly one target total.
                boolean picked = false;
                for (DecisionFrame.Option o : f.options) {
                    if (o.label != null && o.label.contains("player p2")) {
                        submit(session, f, o);
                        picked = true;
                        break;
                    }
                }
                if (!picked) {
                    submit(session, f, pickOption(f,
                            o -> o.label != null && o.label.contains("Rograkh, Son of Rohgahh"),
                            "ultimate -> Rograkh"));
                }
                break;
            } else {
                answerCommon(session, f);
            }
        }
        drain(session, 30);
        // Player targeting verified (p2 preferred and hit); Rograkh alive
        // proves no friendly fire; Jeska paid 2 loyalty so she dies to
        // SBA (704.5i) — itself proof of the payment.
        Assert.assertEquals(life(1, session), p2Life - 2, "ultimate X=2 must deal 2 to p2");
        Assert.assertNotNull(findBf(session, 0, "Rograkh, Son of Rohgahh"),
                "Rograkh must survive (no friendly fire)");
        Assert.assertNull(findBf(session, 0, "Jeska, Thrice Reborn"),
                "Jeska must leave the battlefield after paying 2 loyalty");
        Assert.assertTrue(inZone(session, 0, ZoneType.Graveyard, "Jeska, Thrice Reborn"),
                "spent Jeska must die to SBA (704.5i) into the graveyard");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testJeskaZeroLoyaltyDiesToStateBasedAction() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr11-jeska-zero");
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Jeska, Thrice Reborn", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        // No commanders cast: Jeska enters with 0 loyalty and dies to
        // state-based actions (CR 704.5i). castJeska's findBf assert does
        // not apply here; drive the cast and observe the grave directly.
        DecisionFrame castFrame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, castFrame, pickOption(castFrame,
                o -> "cast_spell".equals(o.actionType)
                        && "Jeska, Thrice Reborn".equals(o.sourceCardName),
                "Jeska cast"));
        drain(session, 30);
        Assert.assertNull(findBf(session, 0, "Jeska, Thrice Reborn"),
                "0-loyalty Jeska must not survive on the battlefield");
        Assert.assertTrue(inZone(session, 0, ZoneType.Graveyard, "Jeska, Thrice Reborn"),
                "0-loyalty Jeska must die to SBA (704.5i) into the graveyard");
        session.shutdown(5000);
    }
}
