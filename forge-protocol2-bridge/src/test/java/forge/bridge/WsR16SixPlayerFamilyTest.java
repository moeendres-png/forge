package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * R16 six-player family: lifecycle, actual-card combat/trigger,
 * concession, hidden-info and determinism at 6P (2–5P retained).
 *
 * <p>Engine proven 6-capable (R16 probe); provider gate widened 2–5 to
 * 2–6 with 7+ fail-closed. Per test: six distinct seats, exact life and
 * zone accounting, clean shutdown. Tests pick only engine-offered
 * options; nothing is injected. 7P rejection lives in
 * WS233CardinalityTest (R16-updated).</p>
 */
public class WsR16SixPlayerFamilyTest {

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

    private static final String[] CANARIES = {
            "Memnite", "Serra Angel", "Runeclaw Bear", "Grizzly Bears", "Ornithopter",
            "Storm Crow"
    };

    @Test(timeOut = 300000)
    public void testSixPlayerLifecycle() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr16-life-6p", 6);
        final BridgeSession session = constructed.session;
        Assert.assertEquals(session.getGame().getPlayers().size(), 6, "six seats");
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int s = 0; s < 6; s++) {
            Assert.assertEquals(life(s, session), 40, "seat " + s + " starts at 40");
            ids.add("p" + (s + 1));
        }
        Assert.assertEquals(ids.size(), 6, "six distinct principals");
        drain(session, 10);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testSixPlayerCombatSplit() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr16-combat-6p", 6);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Runeclaw Bear -> p2")
                                && o.label.contains("Grizzly Bears -> p3"),
                        "split p2+p3"));
                break;
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("No block"),
                        "no blocks"));
            } else {
                answerCommon(session, f);
            }
            if (life(1, session) != 40) {
                break;
            }
        }
        drain(session, 30);
        Assert.assertEquals(life(0, session), 40, "p1 untouched");
        Assert.assertEquals(life(1, session), 38, "p2 takes Bear 2");
        Assert.assertEquals(life(2, session), 38, "p3 takes Grizzly 2");
        Assert.assertEquals(life(3, session), 40, "p4 untouched");
        Assert.assertEquals(life(4, session), 40, "p5 untouched");
        Assert.assertEquals(life(5, session), 40, "p6 untouched");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testSixPlayerKedissFanout() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr16-kediss-6p", 6);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Kediss, Emberclaw Familiar");
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int[] before = new int[6];
        for (int s = 0; s < 6; s++) {
            before[s] = life(s, session);
        }
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasKediss = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Kediss, Emberclaw Familiar".equals(o.sourceCardName)) {
                        hasKediss = true;
                    }
                }
                if (hasKediss) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Kediss, Emberclaw Familiar".equals(o.sourceCardName),
                            "Kediss cast"));
                    break;
                }
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Kediss, Emberclaw Familiar")
                                && o.label.contains("p2"),
                        "Kediss -> p2"));
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
        Assert.assertEquals(life(0, session), before[0], "p1 untouched");
        Assert.assertEquals(life(1, session), before[1] - 1, "p2 takes commander combat");
        for (int s = 2; s < 6; s++) {
            Assert.assertEquals(life(s, session), before[s] - 1, "p" + (s + 1) + " takes trigger");
        }
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testSixPlayerConcedeContinues() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr16-concede-6p", 6);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 5, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p6") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> "concede".equals(o.actionType),
                        "p6 concedes"));
                break;
            }
            answerCommon(session, f);
        }
        drain(session, 20);
        Assert.assertFalse(session.isTerminal(), "6P concede must not end the session");
        Assert.assertEquals(session.getGame().getPlayers().size(), 5, "five remain");
        final int remaining = session.getGame().getPlayers().size();
        for (int s = 0; s < remaining; s++) {
            for (Card c : session.getGame().getPlayers().get(s)
                    .getZone(ZoneType.Battlefield).getCards()) {
                Assert.assertFalse("Runeclaw Bear".equals(c.getName()),
                        "conceder's Bear must leave the game (800.4)");
            }
        }
        for (int s = 0; s < 5; s++) {
            Assert.assertEquals(life(s, session), 40, "seat " + s + " untouched");
        }
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testSixPlayerHiddenInfo() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr16-hidden-6p", 6);
        final BridgeSession session = constructed.session;
        for (int seat = 0; seat < 6; seat++) {
            BridgeTestSupport.addCard(constructed.game, seat, CANARIES[seat], ZoneType.Hand);
            BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Battlefield);
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int observer = 1; observer <= 6; observer++) {
            final String observerId = "p" + observer;
            final com.google.gson.JsonObject state =
                    StateProjection.gameState(session, observerId);
            final String flat = state.toString();
            Assert.assertTrue(flat.contains(CANARIES[observer - 1]),
                    observerId + " must see own canary");
            for (int other = 1; other <= 6; other++) {
                if (other == observer) {
                    continue;
                }
                Assert.assertFalse(flat.contains(CANARIES[other - 1]),
                        observerId + " leaked " + CANARIES[other - 1]);
            }
        }
        final com.google.gson.JsonObject asPublic = StateProjection.gameState(session, null);
        Assert.assertEquals(asPublic.getAsJsonArray("legal_actions").size(), 0,
                "public observer must see no legal actions");
        session.shutdown(5000);
    }

    private static final class TapedStep {
        DecisionFrame.Kind kind;
        String fingerprint;
    }

    private static void twinSixPlayers(long seed, boolean sameSeed) {
        final String tagA = "wsr16-twin6-a";
        final List<TapedStep> tape = new ArrayList<>();
        final BridgeEngine engineA = new BridgeEngine();
        BridgeTestSupport.startEngine(engineA);
        final List<String> handlesA = BridgeTestSupport.importPod(engineA, 6);
        createSeeded(engineA, tagA, handlesA, seed);
        BridgeTestSupport.startGame(engineA, tagA);
        final BridgeSession sessionA = engineA.sessionsForTests().get(tagA);
        long seen = -1;
        int passes = 0;
        for (int i = 0; i < 80 && passes < 4; i++) {
            final DecisionFrame parked = awaitNext(sessionA, seen, 15000);
            Assert.assertNotNull(parked, "no frame while recording at 6P");
            seen = parked.revision;
            if (parked.kind == DecisionFrame.Kind.STARTING_PLAYER) {
                final DecisionFrame.Option opt = findStartingOption(parked, "p6");
                tape.add(taped(parked, opt, sessionA));
                Assert.assertTrue(sessionA.submit(parked.actorPlayerId, opt.optionId,
                        opt.actionType, parked.revision).applied);
            } else if (parked.kind == DecisionFrame.Kind.MULLIGAN) {
                final DecisionFrame.Option keep = keepOption(parked);
                tape.add(taped(parked, keep, sessionA));
                Assert.assertTrue(sessionA.submit(parked.actorPlayerId, keep.optionId,
                        keep.actionType, parked.revision).applied);
            } else if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                final DecisionFrame.Option pass = BridgeTestSupport.findOption(parked,
                        "pass_priority");
                Assert.assertNotNull(pass);
                tape.add(taped(parked, pass, sessionA));
                Assert.assertTrue(BridgeTestSupport.submitPass(sessionA, parked).applied);
                passes++;
            } else {
                Assert.fail("unexpected frame while recording: " + parked.kind);
            }
        }
        Assert.assertEquals(passes, 4, "must record four passes");
        final String digestA = SemanticReplay.publicStateDigest(sessionA);
        sessionA.shutdown(5000);
        final long seedB = sameSeed ? seed : seed + 1;
        final BridgeEngine engineB = new BridgeEngine();
        BridgeTestSupport.startEngine(engineB);
        final List<String> handlesB = BridgeTestSupport.importPod(engineB, 6);
        createSeeded(engineB, "wsr16-twin6-b", handlesB, seedB);
        BridgeTestSupport.startGame(engineB, "wsr16-twin6-b");
        final BridgeSession sessionB = engineB.sessionsForTests().get("wsr16-twin6-b");
        long seenB = -1;
        final String digestB;
        if (sameSeed) {
            for (TapedStep step : tape) {
                DecisionFrame parked = null;
                for (int i = 0; i < 60; i++) {
                    final DecisionFrame next = awaitNext(sessionB, seenB, 15000);
                    Assert.assertNotNull(next, "no frame while replaying at 6P");
                    seenB = next.revision;
                    if (next.kind == step.kind) {
                        parked = next;
                        break;
                    }
                    Assert.fail("frame kind drift while replaying: expected " + step.kind
                            + " got " + next.kind);
                }
                Assert.assertNotNull(parked);
                final DecisionFrame.Option resolved =
                        SemanticReplay.resolveExactlyOnce(parked, sessionB, step.fingerprint);
                final BridgeSession.SubmitOutcome outcome = sessionB.submit(parked.actorPlayerId,
                        resolved.optionId, resolved.actionType, parked.revision);
                Assert.assertTrue(outcome.applied, "replay submit failed: " + outcome.errorCode);
            }
            digestB = SemanticReplay.publicStateDigest(sessionB);
            sessionB.shutdown(5000);
            Assert.assertTrue(sessionB.isTerminal());
            Assert.assertEquals(digestB, digestA, "6P same-seed replay must match");
        } else {
            // Divergence control: different seed AND different starting
            // seat (public state), driven independently — never a replay.
            int passesC = 0;
            for (int i = 0; i < 80 && passesC < 4; i++) {
                final DecisionFrame parked = awaitNext(sessionB, seenB, 15000);
                Assert.assertNotNull(parked, "no frame while diverging at 6P");
                seenB = parked.revision;
                if (parked.kind == DecisionFrame.Kind.STARTING_PLAYER) {
                    final DecisionFrame.Option opt = findStartingOption(parked, "p1");
                    Assert.assertTrue(sessionB.submit(parked.actorPlayerId, opt.optionId,
                            opt.actionType, parked.revision).applied);
                } else if (parked.kind == DecisionFrame.Kind.MULLIGAN) {
                    final DecisionFrame.Option keep = keepOption(parked);
                    Assert.assertTrue(sessionB.submit(parked.actorPlayerId, keep.optionId,
                            keep.actionType, parked.revision).applied);
                } else if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                    final DecisionFrame.Option pass = BridgeTestSupport.findOption(parked,
                            "pass_priority");
                    Assert.assertNotNull(pass);
                    Assert.assertTrue(BridgeTestSupport.submitPass(sessionB, parked).applied);
                    passesC++;
                } else {
                    Assert.fail("unexpected frame while diverging: " + parked.kind);
                }
            }
            Assert.assertEquals(passesC, 4, "must drive four passes");
            digestB = SemanticReplay.publicStateDigest(sessionB);
            sessionB.shutdown(5000);
            Assert.assertNotEquals(digestB, digestA, "6P distinct seed+starter must diverge");
        }
    }

    private static void createSeeded(BridgeEngine engine, String gameId, List<String> handles,
            long seed) {
        final com.google.gson.JsonObject created = BridgeTestSupport.rpc(engine,
                "{\"protocol_version\":\"2.0.0\",\"request_id\":\"seed-" + gameId + "\","
                        + "\"message_type\":\"create_commander_game\",\"payload\":{\"request\":{"
                        + "\"game_id\":\"" + gameId + "\",\"format\":\"commander\",\"seed\":" + seed
                        + ",\"deck_handles\":[\"" + String.join("\",\"", handles) + "\"]}}}");
        BridgeTestSupport.assertOk(created);
    }

    private static TapedStep taped(DecisionFrame frame, DecisionFrame.Option option,
            BridgeSession session) {
        final TapedStep step = new TapedStep();
        step.kind = frame.kind;
        step.fingerprint = SemanticReplay.optionFingerprint(frame, option, session);
        return step;
    }

    private static DecisionFrame.Option keepOption(DecisionFrame frame) {
        for (DecisionFrame.Option option : frame.options) {
            if (option.isKeep) {
                return option;
            }
        }
        throw new AssertionError("no keep option parked");
    }

    private static DecisionFrame.Option findStartingOption(DecisionFrame frame,
            String playerId) {
        for (DecisionFrame.Option option : frame.options) {
            if (playerId.equals(option.sourceCardName)) {
                return option;
            }
        }
        throw new AssertionError("no starting-player option for " + playerId);
    }

    @Test(timeOut = 300000)
    public void testSixPlayerTwinMatch() {
        twinSixPlayers(7771L, true);
    }

    @Test(timeOut = 300000)
    public void testSixPlayerTwinDiverge() {
        twinSixPlayers(7771L, false);
    }
}
