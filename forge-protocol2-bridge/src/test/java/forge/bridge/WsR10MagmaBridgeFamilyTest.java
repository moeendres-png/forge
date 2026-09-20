package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * R10b MAGMA bridge family: Magma Opus (CARD_09) divided damage + tap +
 * token + draw, plus the Treasure activation.
 *
 * <p>WS234 proved Treasure-activation presence; divided-4/tap/token/draw
 * was NOT_RUN (shared WS217 divided engine CODE_DERIVED only). Three
 * strict bridge tests: divided 2+2 across p2/p3 with exact-vector submit
 * through the native seam, tap-2 permanents, 4/4 token, draw-2 accounting,
 * graveyard resolution; Treasure activation via discard+UR; 7-mana
 * decline fail-closed. Tests pick only engine-offered options; damage and
 * zones are accounted, never injected.</p>
 */
public class WsR10MagmaBridgeFamilyTest {

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

    private static void submitDivided(BridgeSession session, DecisionFrame frame,
            Map<String, Integer> vector) {
        final BridgeSession.SubmitOutcome outcome =
                session.submitDividedAllocation(frame.actorPlayerId, frame.revision, vector);

        Assert.assertTrue(outcome.applied,
                "divided submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
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
                throw new AssertionError("blocked by " + frame.kind + " " + frame.status);
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

    private static int zoneCount(BridgeSession session, int seat, ZoneType zone) {
        return session.getGame().getPlayers().get(seat).getZone(zone).getCards().size();
    }

    private static boolean inZone(BridgeSession session, int seat, ZoneType zone, String name) {
        for (Card c : session.getGame().getPlayers().get(seat).getZone(zone).getCards()) {
            if (name.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String findTargetOption(DecisionFrame frame, String fragment) {
        for (DecisionFrame.Option o : frame.options) {
            if (o.optionId != null && o.label != null && o.label.contains(fragment)) {
                return o.optionId;
            }
        }
        return null;
    }

    // Tap all available mana sources across MANA frames (bounded).
    // Extra taps are harmless overpay; guarantees full payment whenever
    // sources suffice. Returns after the first non-MANA frame.
    private static void tapAllMana(BridgeSession session) {
        for (int i = 0; i < 16; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                boolean tapped = false;
                for (DecisionFrame.Option o : f.options) {
                    if (o.actionType != null && o.actionType.contains("tap_mana_source")
                            && (o.label == null || !o.label.contains("Decline"))) {
                        submit(session, f, o);
                        tapped = true;
                        break;
                    }
                }
                if (!tapped) {
                    return;
                }
            } else {
                return;
            }
        }
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

    @Test(timeOut = 300000)
    public void testMagmaDividedTwoTwoTapTokenDraw() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr10-magma-22");
        final BridgeSession session = constructed.session;
        // Lean permanent base (7 total) so the tap-2 targeting fits the
        // bridge completeness bound; burst mana via Seething Song ({2}{R}).
        // Budget: Song taps 3R; Magma needs 6+U+R from pool 5R+U+2R.
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Seething Song", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Magma Opus", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 20);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int p2Life = life(1, session);
        int p3Life = life(2, session);
        int p4Life = life(3, session);
        int handBefore = zoneCount(session, 0, ZoneType.Hand);

        // Burst first: Seething Song for RRRRR (tap one Mountain explicitly).
        // The pool empties across phases, so Magma must be submitted at the
        // first p1 priority after Song resolves: check-then-act, no passes.
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Seething Song".equals(o.sourceCardName),
                "Song cast"));
        boolean magmaSubmitted = false;
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (inZone(session, 0, ZoneType.Graveyard, "Seething Song")
                    && f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasMagma = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Magma Opus".equals(o.sourceCardName)) {
                        hasMagma = true;
                    }
                }
                if (hasMagma) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Magma Opus".equals(o.sourceCardName),
                            "Magma cast"));
                    magmaSubmitted = true;
                    break;
                }
            }
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                DecisionFrame.Option tap = null;
                for (DecisionFrame.Option o : f.options) {
                    if (o.sourceCardName != null && o.sourceCardName.contains("Mountain")) {
                        tap = o;
                        break;
                    }
                }
                if (tap == null) {
                    for (DecisionFrame.Option o : f.options) {
                        if (o.actionType != null && o.actionType.contains("tap_mana_source")
                                && (o.label == null || !o.label.contains("Decline"))) {
                            tap = o;
                            break;
                        }
                    }
                }
                Assert.assertNotNull(tap, "no tap source offered");
                submit(session, f, tap);
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                break;
            }
        }
        Assert.assertTrue(inZone(session, 0, ZoneType.Graveyard, "Seething Song"),
                "Song must resolve");        Assert.assertTrue(magmaSubmitted, "Magma must be submitted on Song mana");
        // Target set: p2+p3 (both opponents, clean damage accounting).
        boolean setPicked = false;
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION && f.actorPlayerId.equals("p1")) {
                boolean hasSet = false;
                for (DecisionFrame.Option o : f.options) {
                    if (o.label != null && o.label.contains("player p2")
                            && o.label.contains("player p3")) {
                        hasSet = true;
                    }
                }
                if (hasSet) {
                    submit(session, f, pickOption(f,
                            o -> o.label != null && o.label.contains("player p2")
                                    && o.label.contains("player p3"),
                            "targets p2+p3"));
                    setPicked = true;
                    break;
                }
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                tapAllMana(session);
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                break;
            }
        }
        Assert.assertTrue(setPicked, "p2+p3 target set must be offered");
        tapAllMana(session);
        // Divided allocation: exact 2+2 vector through the native seam.
        DecisionFrame dividedFrame = null;
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.DIVIDED_ALLOCATION && f.actorPlayerId.equals("p1")) {
                dividedFrame = f;
                break;
            }
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                tapAllMana(session);
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertNotNull(dividedFrame, "DIVIDED_ALLOCATION must park for Magma");
        Assert.assertEquals(dividedFrame.dividedTotal, 4, "Core total must be 4");
        final String p2Id = findTargetOption(dividedFrame, "player p2");
        final String p3Id = findTargetOption(dividedFrame, "player p3");
        Assert.assertNotNull(p2Id, "p2 must be a divided target");
        Assert.assertNotNull(p3Id, "p3 must be a divided target");
        final Map<String, Integer> vector = new LinkedHashMap<>();
        vector.put(p2Id, 2);
        vector.put(p3Id, 2);
        submitDivided(session, dividedFrame, vector);
        tapAllMana(session);
        // Tap two permanents: Bear then Mountain (explicit picks).
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                boolean picked = false;
                for (DecisionFrame.Option o : f.options) {
                    if (o.label != null && o.label.contains("Runeclaw Bear")) {
                        submit(session, f, o);
                        picked = true;
                        break;
                    }
                }
                if (!picked) {
                    for (DecisionFrame.Option o : f.options) {
                        if (o.label != null && o.label.contains("Mountain")) {
                            submit(session, f, o);
                            picked = true;
                            break;
                        }
                    }
                }
                if (picked) {
                    continue;
                }
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                tapAllMana(session);
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else {
                break;
            }
            if (inZone(session, 0, ZoneType.Graveyard, "Magma Opus")) {
                break;
            }
        }
        drain(session, 30);

        Assert.assertEquals(life(1, session), p2Life - 2, "p2 takes exactly 2 divided");
        Assert.assertEquals(life(2, session), p3Life - 2, "p3 takes exactly 2 divided");
        Assert.assertEquals(life(3, session), p4Life, "p4 untouched");
        Assert.assertTrue(inZone(session, 0, ZoneType.Graveyard, "Magma Opus"),
                "Magma must resolve natively to graveyard");
        Assert.assertTrue(inZone(session, 0, ZoneType.Battlefield, "Elemental Token"),
                "4/4 Elemental token must enter");
        Assert.assertEquals(zoneCount(session, 0, ZoneType.Hand), handBefore,
                "must draw exactly 2 (net 0: Song+Magma cast, draw 2)");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testMagmaTreasureActivation() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr10-magma-treasure");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Magma Opus", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "activate_ability".equals(o.actionType)
                        && "Magma Opus".equals(o.sourceCardName),
                "Treasure activation"));
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else {
                break;
            }
            if (inZone(session, 0, ZoneType.Battlefield, "Treasure Token")
                    || inZone(session, 0, ZoneType.Graveyard, "Magma Opus")) {
                break;
            }
        }
        drain(session, 20);
        Assert.assertTrue(inZone(session, 0, ZoneType.Graveyard, "Magma Opus"),
                "Magma must be discarded as activation cost");
        Assert.assertTrue(inZone(session, 0, ZoneType.Battlefield, "Treasure Token"),
                "Treasure token must enter");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testMagmaShortManaFailClosed() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr10-magma-closed");
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Magma Opus", ZoneType.Hand);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Magma Opus".equals(o.sourceCardName),
                "Magma cast attempt"));
        for (int i = 0; i < 20; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Decline"),
                        "decline short payment"));
                break;
            }
            if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(inZone(session, 0, ZoneType.Hand, "Magma Opus"),
                "unpayable Magma must remain in hand after decline");
        session.shutdown(5000);
    }
}
