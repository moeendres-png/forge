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
 * WSR20 FULL107 common-denominator: missing-execution closure for the Forge
 * candidate at the audit base.
 *
 * <p>Every test runs actual Forge Rules-engine Commander games through
 * engine-offered decision frames only. No fallback, no manual outcome
 * injection, no synthetic state mutation: damage, zones, counts, taxes and
 * turn order are all engine-produced and asserted. Targeted new coverage for
 * FULL107 fixtures left UNKNOWN by the wsc2 packet: Commander tax at 2P,
 * commander-damage lethal/split/control (21-life rule), zone-choice branches,
 * Partner independence, first-turn draw/skip, elimination stack/ring/turn
 * semantics, prevention, permanent control change, hand-size continuous
 * effects, Humility/Anthem layers, stack ordering, Warstorm Surge exactness,
 * hidden-knowledge channels (face-up exile, reveal audience, generic scry),
 * coin-flip extra turns, and 2/2 combat trades.</p>
 */
public class WsR20Full107DenominatorTest {

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
        Assert.assertTrue(outcome.executionOk,
                "submit applied but not executed: " + outcome.errorCode + " "
                        + outcome.errorMessage + " action=" + option.actionType
                        + " label=" + option.label);
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
            payManaFirstWorking(session);
        } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
            submit(session, frame, pickOption(frame,
                    o -> o.label != null && (o.label.contains("No attack")
                            || o.label.contains("No block")),
                    "decline combat"));
        } else if (frame.kind == DecisionFrame.Kind.TRIGGER_PLAY) {
            submit(session, frame, frame.options.get(0));
        } else {
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
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

    private static int lifeById(BridgeSession session, String playerId) {
        return session.playerById(playerId).getLife();
    }

    private static List<String> turnOrderIds(BridgeSession session) {
        final List<String> ids = new ArrayList<>();
        for (forge.game.player.Player p : session.getGame().getPlayersInTurnOrder()) {
            ids.add(session.playerIdOf(p));
        }
        return ids;
    }

    private static int handSize(int seat, BridgeSession session) {
        return session.getGame().getPlayers().get(seat)
                .getZone(ZoneType.Hand).getCards().size();
    }

    private static String turnPlayer(BridgeSession session) {
        return session.playerIdOf(session.getGame().getPhaseHandler().getPlayerTurn());
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

    private static Card findZone(BridgeSession session, int seat, ZoneType zone, String name) {
        for (Card c : session.getGame().getPlayers().get(seat)
                .getZone(zone).getCards()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    /** Try every offered mana payment until one applies (multi-color costs). */
    private static void payManaFirstWorking(BridgeSession session) {
        DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.MANA_PAYMENT,
                "expected mana payment, got " + frame.kind);
        for (DecisionFrame.Option option : frame.options) {
            final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                    option.optionId, option.actionType, frame.revision);
            if (outcome.applied) {
                return;
            }
        }
        throw new AssertionError("no offered mana payment applied; options="
                + frame.options.size());
    }

    private static void tapLand(BridgeSession session, String actorId, String landName) {
        for (int i = 0; i < 20; i++) {
            DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
                if (frameMatches(frame, actorId, DecisionFrame.Kind.PRIORITY)) {
                    DecisionFrame.Option tap = null;
                    for (DecisionFrame.Option o : frame.options) {
                        if (landName.equals(o.sourceCardName) && o.actionType != null
                                && (o.actionType.contains("tap")
                                        || "activate_ability".equals(o.actionType))) {
                            tap = o;
                            break;
                        }
                    }
                if (tap != null) {
                    submit(session, frame, tap);
                    return;
                }
            }
            answerCommon(session, frame);
        }
        throw new AssertionError("never reached tap of " + landName + " for " + actorId);
    }

    private static int poolOf(BridgeSession session, int seat, String color) {
        final com.google.gson.JsonObject state =
                StateProjection.gameState(session, "p" + (seat + 1));
        for (Object element : state.getAsJsonArray("players")) {
            final com.google.gson.JsonObject playerState = (com.google.gson.JsonObject) element;
            if (playerState.get("player_id").getAsString().equals("p" + (seat + 1))) {
                return playerState.getAsJsonObject("mana_pool").get(color).getAsInt();
            }
        }
        throw new AssertionError("no pool for seat " + seat);
    }

    /** Cast the named spell from hand through engine-offered frames. */
    private static void castFromHand(BridgeSession session, String actorId, String cardName) {
        boolean castDone = false;
        for (int i = 0; i < 40 && !castDone; i++) {
            DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, actorId, DecisionFrame.Kind.PRIORITY)) {
                DecisionFrame.Option cast = null;
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType) && cardName.equals(o.sourceCardName)) {
                        cast = o;
                        break;
                    }
                }
                if (cast != null) {
                    submit(session, frame, cast);
                    castDone = true;
                    settleMana(session);
                    return;
                }
            }
            answerCommon(session, frame);
        }
        if (!castDone) {
            throw new AssertionError("never reached cast of " + cardName + " for " + actorId);
        }
    }

    /** Answer every pending mana payment; never consumes other frames. */
    private static void settleMana(BridgeSession session) {
        final long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame frame = session.getCurrentFrame();
            if (frame != null && frame.kind == DecisionFrame.Kind.MANA_PAYMENT
                    && frame.status == DecisionFrame.Status.SUPPORTED) {

                payManaFirstWorking(session);
                continue;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Cast the named commander from the command zone, paying offered mana. */
    private static void castCommander(BridgeSession session, String actorId, String commanderName) {
        castFromHand(session, actorId, commanderName);
        for (int i = 0; i < 30; i++) {
            DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                payManaFirstWorking(session);
            } else {
                return;
            }
        }
    }

    private static void attackUnblocked(BridgeSession session, String attackerId,
            String attackerName, String defenderId) {
        boolean attacked = false;
        for (int i = 0; i < 200 && !attacked; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals(attackerId)
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains(attackerName)
                                && o.label.contains(defenderId),
                        "attack " + attackerName + " -> " + defenderId));
                attacked = true;
                break;
            }
            answerCommon(session, f);
        }
        if (!attacked) {
            throw new AssertionError(
                    "never reached attack " + attackerName + " -> " + defenderId);
        }
        for (int i = 0; i < 60; i++) {
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
    }

    private static void driveToMain(BridgeSession session, String actorId, int budget) {
        for (int i = 0; i < budget; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (frameMatches(f, actorId, DecisionFrame.Kind.PRIORITY)
                    && BridgeTestSupport.isMainPhase(session)
                    && turnPlayer(session).equals(actorId)) {
                return;
            }
            answerCommon(session, f);
        }
        throw new AssertionError("never reached " + actorId + " main phase");
    }

    /**
     * Answer a target selection for the actor, tolerating the bridge forcing a
     * lone legal target with no frame (correct behavior, cf. G02 Murder).
     *
     * <p>Pure poll, no passes: cast targets are chosen synchronously after
     * payment, so passing while waiting would resolve the spell before its
     * target frame parks (and, for elimination-on-stack shapes, before the
     * caller can act). Triggered-ability targets that need passes must use a
     * dedicated loop instead.</p>
     *
     * @return true if a target frame was answered, false if the spell went
     *         on without one (lone target forced).
     */
    private static boolean awaitTargetOrForced(BridgeSession session, String actorId,
            String targetLabel) {
        final long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            final DecisionFrame f = session.getCurrentFrame();
            if (f != null && frameMatches(f, actorId, DecisionFrame.Kind.TARGET_SELECTION)) {

                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains(targetLabel),
                        "target " + targetLabel));
                settleMana(session);
                return true;
            }
            if (f != null && f.kind == DecisionFrame.Kind.MANA_PAYMENT
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                payManaFirstWorking(session);
                continue;
            }
            if (f != null && (f.kind == DecisionFrame.Kind.COMMANDER_MOVE
                    || f.kind == DecisionFrame.Kind.REPLACEMENT_CONFIRM)) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void waitTurnPasses(BridgeSession session, String actorId, int budget) {
        for (int i = 0; i < budget; i++) {
            if (!turnPlayer(session).equals(actorId)) {
                return;
            }
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            answerCommon(session, f);
        }
        throw new AssertionError("turn never passed from " + actorId);
    }

    private static void answerCommanderMove(BridgeSession session, String actorId, boolean toCommand) {
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (frameMatches(f, actorId, DecisionFrame.Kind.COMMANDER_MOVE)
                    || frameMatches(f, actorId, DecisionFrame.Kind.REPLACEMENT_CONFIRM)) {
                final StringBuilder seen = new StringBuilder();
                for (DecisionFrame.Option o : f.options) {
                    seen.append('[').append(o.actionType).append('|').append(o.label)
                            .append('|').append(o.confirmValue).append(']');
                }

                submit(session, f, pickOption(f,
                        o -> o.confirmValue == toCommand,
                        "commander move toCommand=" + toCommand));
                return;
            }
            answerCommon(session, f);
        }
        throw new AssertionError("COMMANDER_MOVE never parked for " + actorId);
    }

    private static int commanderCastCount(BridgeSession session, String playerId, String name) {
        for (Card c : session.getGame().getPlayers()
                .get(Integer.parseInt(playerId.substring(1)) - 1)
                .getZone(ZoneType.Command).getCards()) {
            if (name.equals(c.getName())) {
                return session.playerById(playerId).getCommanderCast(c);
            }
        }
        for (Card c : session.getGame().getPlayers()
                .get(Integer.parseInt(playerId.substring(1)) - 1)
                .getZone(ZoneType.Battlefield).getCards()) {
            if (name.equals(c.getName())) {
                return session.playerById(playerId).getCommanderCast(c);
            }
        }
        throw new AssertionError(name + " not found for " + playerId);
    }

    private static void concedeAs(BridgeSession session, String actorId) {
        for (int i = 0; i < 60; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals(actorId) && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> "concede".equals(o.actionType),
                        actorId + " concedes"));
                return;
            }
            answerCommon(session, f);
        }
        throw new AssertionError("never reached concede priority for " + actorId);
    }

    // ------------------------------------------------------------------
    // WS05-CMD-TAX-2: {2} then {4} tax at exactly 2 players, count to 3.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CommanderTaxTwoPlayers() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-tax-2p", 2);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
        for (int i = 0; i < 7; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        for (int i = 0; i < 6; i++) {
            BridgeTestSupport.addCard(constructed.game, 1, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Murder", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Murder", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Isamaru, Hound of Konda");
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Isamaru, Hound of Konda"),
                "Isamaru must enter");

        for (int kill = 0; kill < 2; kill++) {
            driveToMain(session, "p2", 200);
            castFromHand(session, "p2", "Murder");
            awaitTargetOrForced(session, "p2", "Isamaru");
            answerCommanderMove(session, "p1", true);
            drain(session, 20);
            Assert.assertNotNull(
                    findZone(session, 0, ZoneType.Command, "Isamaru, Hound of Konda"),
                    "Isamaru must be back in the command zone");

            driveToMain(session, "p1", 200);
            final int tax = kill == 0 ? 2 : 4;
            for (int t = 0; t < tax; t++) {
                tapLand(session, "p1", "Plains");
            }
            castCommander(session, "p1", "Isamaru, Hound of Konda");
            drain(session, 20);
            Assert.assertNotNull(findBf(session, 0, "Isamaru, Hound of Konda"),
                    "taxed recast must resolve");
            Assert.assertEquals(poolOf(session, 0, "W"), 0,
                    "exactly {" + tax + "} must be consumed for the recast");
        }
        Assert.assertEquals(commanderCastCount(session, "p1", "Isamaru, Hound of Konda"), 3,
                "cast count must be 3 after two taxed recasts");
    }

    // ------------------------------------------------------------------
    // WS05-CMD-DMG-SAME-21 + WS05-CMD-ELIM-4: exactly 21 from one commander.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CommanderDamageLethal21() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-dmg21");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Gishath, Sun's Avatar");
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Fervor", ZoneType.Battlefield);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Gishath, Sun's Avatar");
        drain(session, 20);
        final Card gish = findBf(session, 0, "Gishath, Sun's Avatar");
        Assert.assertNotNull(gish, "Gishath must enter");


        for (int turn = 0; turn < 3; turn++) {
            attackUnblocked(session, "p1", "Gishath, Sun's Avatar", "p2");
            drain(session, 30);
            if (turn == 1) {
                int total = 0;
                int entries = 0;
                for (java.util.Map.Entry<Card, Integer> e : session.getGame().getPlayers()
                        .get(1).getCommanderDamage()) {
                    entries++;
                    total += e.getValue();
                    Assert.assertEquals(e.getKey().getName(), "Gishath, Sun's Avatar",
                            "damage identity must remain the same commander");
                }
                Assert.assertEquals(entries, 1, "exactly one commander damage source");
                Assert.assertEquals(total, 14, "14 commander damage after two hits");
            }
            waitTurnPasses(session, "p1", 120);
            if (turn < 2) {
                driveToMain(session, "p1", 120);
            }
        }
        drain(session, 30);
        Assert.assertEquals(session.getGame().getPlayers().size(), 3,
                "P2 must have left the game on 21 commander damage");
        Assert.assertEquals(turnOrderIds(session),
                java.util.Arrays.asList("p1", "p3", "p4"),
                "P2 must be absent from the turn ring after commander-damage loss");
        Assert.assertFalse(session.isTerminal(), "game must continue without P2");
        Assert.assertEquals(lifeById(session, "p1"), 40, "P1 untouched");
        Assert.assertEquals(lifeById(session, "p3"), 40, "P3 untouched");
        Assert.assertEquals(lifeById(session, "p4"), 40, "P4 untouched");
    }

    // ------------------------------------------------------------------
    // WS05-CMD-DMG-SPLIT: 14 + 7 from different commanders is no loss.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CommanderDamageSplitNoLoss() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-dmgsplit");
        final BridgeSession session = constructed.session;
        for (int seat : new int[]{0, 2}) {
            BridgeTestSupport.addCommander(constructed.game, seat, "Gishath, Sun's Avatar");
            for (int i = 0; i < 3; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Mountain", ZoneType.Battlefield);
                BridgeTestSupport.addCard(constructed.game, seat, "Forest", ZoneType.Battlefield);
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Battlefield);
            }
            BridgeTestSupport.addCard(constructed.game, seat, "Fervor", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Gishath, Sun's Avatar");
        drain(session, 20);
        attackUnblocked(session, "p1", "Gishath, Sun's Avatar", "p2");
        drain(session, 20);
        waitTurnPasses(session, "p1", 120);
        driveToMain(session, "p1", 120);
        attackUnblocked(session, "p1", "Gishath, Sun's Avatar", "p2");
        drain(session, 20);

        driveToMain(session, "p3", 160);
        castCommander(session, "p3", "Gishath, Sun's Avatar");
        drain(session, 20);
        attackUnblocked(session, "p3", "Gishath, Sun's Avatar", "p2");
        drain(session, 30);

        int total = 0;
        int entries = 0;
        int maxSingle = 0;
        for (java.util.Map.Entry<Card, Integer> e : session.getGame().getPlayers().get(1)
                .getCommanderDamage()) {
            entries++;
            total += e.getValue();
            maxSingle = Math.max(maxSingle, e.getValue());
        }
        Assert.assertEquals(entries, 2, "two distinct commander damage sources");
        Assert.assertEquals(total, 21, "21 aggregate commander damage");
        Assert.assertTrue(maxSingle < 21, "no single commander dealt 21");
        Assert.assertEquals(session.getGame().getPlayers().size(), 4,
                "P2 must survive split 21 (no commander-damage loss)");
        Assert.assertEquals(life(1, session), 19, "P2 at 19 life");
    }

    // ------------------------------------------------------------------
    // WS05-CMD-DMG-CONTROL: stolen commander still deals its own damage.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CommanderDamageControlIdentity() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-dmgcontrol");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Gishath, Sun's Avatar");
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 2, "Mountain", ZoneType.Battlefield);
        }
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 2, "Act of Treason", ZoneType.Hand);
        }
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Gishath, Sun's Avatar");
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Gishath, Sun's Avatar"),
                "Gishath must enter under P1");

        for (int turn = 0; turn < 3; turn++) {

            driveToMain(session, "p3", 200);
            castFromHand(session, "p3", "Act of Treason");
            awaitTargetOrForced(session, "p3", "Gishath");
            attackUnblocked(session, "p3", "Gishath, Sun's Avatar", "p2");
            drain(session, 30);
            if (turn == 1) {
                int total = 0;
                int entries = 0;
                for (java.util.Map.Entry<Card, Integer> e : session.getGame().getPlayers()
                        .get(1).getCommanderDamage()) {
                    entries++;
                    total += e.getValue();
                    Assert.assertEquals(e.getKey().getName(), "Gishath, Sun's Avatar",
                            "identity must remain P1's commander");
                    Assert.assertEquals(session.playerIdOf(e.getKey().getOwner()), "p1",
                            "commander ownership must remain P1 despite control changes");
                }
                Assert.assertEquals(entries, 1, "single pooled commander identity");
                Assert.assertEquals(total, 14, "14 commander damage while controlled by P3");
            }
            waitTurnPasses(session, "p3", 120);
        }
        drain(session, 30);
        Assert.assertEquals(session.getGame().getPlayers().size(), 3,
                "P2 must lose to 21 from the same commander");
        Assert.assertEquals(turnOrderIds(session),
                java.util.Arrays.asList("p1", "p3", "p4"),
                "P2 must be absent from the turn ring");
        Assert.assertFalse(session.isTerminal(), "game must continue without P2");
    }

    // ------------------------------------------------------------------
    // WS05-CMD-ZONE-GY-NO: declined move leaves the commander in the GY.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CommanderZoneChoiceNoToGraveyard() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-zonegy");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Swamp", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Swamp", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Swamp", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Murder", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Isamaru, Hound of Konda");
        drain(session, 20);
        driveToMain(session, "p2", 80);
        castFromHand(session, "p2", "Murder");
        awaitTargetOrForced(session, "p2", "Isamaru");
        answerCommanderMove(session, "p1", false);
        drain(session, 20);
        Assert.assertNotNull(
                findZone(session, 0, ZoneType.Graveyard, "Isamaru, Hound of Konda"),
                "declined commander must remain in the graveyard");
    }

    // ------------------------------------------------------------------
    // WS05-CMD-ZONE-EXILE-YES/NO: Swords to Plowshares branches.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CommanderZoneChoiceYesFromExile() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-zoneexileyes");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Swords to Plowshares", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Isamaru, Hound of Konda");
        drain(session, 20);
        driveToMain(session, "p2", 80);
        castFromHand(session, "p2", "Swords to Plowshares");
        awaitTargetOrForced(session, "p2", "Isamaru");
        answerCommanderMove(session, "p1", true);
        drain(session, 20);
        Assert.assertNotNull(
                findZone(session, 0, ZoneType.Command, "Isamaru, Hound of Konda"),
                "accepted commander must be in the command zone, not exile");
    }

    @Test(timeOut = 300000)
    public void testR20_CommanderZoneChoiceNoToExile() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-zoneexileno");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Swords to Plowshares", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Isamaru, Hound of Konda");
        drain(session, 20);
        driveToMain(session, "p2", 80);
        castFromHand(session, "p2", "Swords to Plowshares");
        awaitTargetOrForced(session, "p2", "Isamaru");
        answerCommanderMove(session, "p1", false);
        drain(session, 20);
        Assert.assertNotNull(
                findZone(session, 0, ZoneType.Exile, "Isamaru, Hound of Konda"),
                "declined commander must remain in exile");
    }

    // ------------------------------------------------------------------
    // WS05-CMD-ZONE-HAND-YES/NO: Unsummon branches.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CommanderZoneChoiceHandBranches() {
        for (boolean toCommand : new boolean[]{true, false}) {
            final BridgeTestSupport.ConstructedGame constructed =
                    BridgeTestSupport.buildConstructedGame(
                            "wsr20-zonehand-" + toCommand);
            final BridgeSession session = constructed.session;
            BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 1, "Island", ZoneType.Battlefield);
            BridgeTestSupport.addCard(constructed.game, 1, "Unsummon", ZoneType.Hand);
            fillLibraries(constructed, 30);
            BridgeTestSupport.launchConstructed(constructed);
            BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

            castCommander(session, "p1", "Isamaru, Hound of Konda");
            drain(session, 20);
            driveToMain(session, "p2", 80);
            castFromHand(session, "p2", "Unsummon");
            awaitTargetOrForced(session, "p2", "Isamaru");
            answerCommanderMove(session, "p1", toCommand);
            drain(session, 20);
            if (toCommand) {
                Assert.assertNotNull(
                        findZone(session, 0, ZoneType.Command, "Isamaru, Hound of Konda"),
                        "accepted commander must be in the command zone, not hand");
            } else {
                Assert.assertNotNull(
                        findZone(session, 0, ZoneType.Hand, "Isamaru, Hound of Konda"),
                        "declined commander must remain in hand");
            }
        }
    }

    // ------------------------------------------------------------------
    // WS05-CMD-ZONE-LIB-YES/NO: Tuck (bottom, no reveal) branches.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CommanderZoneChoiceLibraryBranches() {
        for (boolean toCommand : new boolean[]{true, false}) {
            final BridgeTestSupport.ConstructedGame constructed =
                    BridgeTestSupport.buildConstructedGame(
                            "wsr20-zonelib-" + toCommand);
            final BridgeSession session = constructed.session;
            BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
            for (int i = 0; i < 2; i++) {
                BridgeTestSupport.addCard(constructed.game, 1, "Plains", ZoneType.Battlefield);
            }
            BridgeTestSupport.addCard(constructed.game, 1, "Condemn", ZoneType.Hand);
            fillLibraries(constructed, 30);
            BridgeTestSupport.launchConstructed(constructed);
            BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

            castCommander(session, "p1", "Isamaru, Hound of Konda");
            drain(session, 20);
            Assert.assertNotNull(findBf(session, 0, "Isamaru, Hound of Konda"),
                    "Isamaru must enter");
            driveToMain(session, "p1", 200);
            boolean libAttacked = false;
            for (int i = 0; i < 200 && !libAttacked; i++) {
                DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(f);
                if (f.actorPlayerId.equals("p1")
                        && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                        && f.status == DecisionFrame.Status.SUPPORTED) {
                    submit(session, f, pickOption(f,
                            o -> o.label != null && o.label.contains("Isamaru")
                                    && o.label.contains("p2"),
                            "Isamaru attacks p2"));
                    libAttacked = true;
                    break;
                }
                answerCommon(session, f);
            }
            Assert.assertTrue(libAttacked, "Isamaru must attack for Condemn");
            boolean condemned = false;
            for (int i = 0; i < 60 && !condemned; i++) {
                DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(f);
                if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                    submit(session, f, pickOption(f,
                            o -> o.label != null && o.label.contains("No block"),
                            "no blocks"));
                    continue;
                }
                if (frameMatches(f, "p2", DecisionFrame.Kind.PRIORITY)) {
                    DecisionFrame.Option condemn = null;
                    for (DecisionFrame.Option o : f.options) {
                        if ("cast_spell".equals(o.actionType)
                                && "Condemn".equals(o.sourceCardName)) {
                            condemn = o;
                            break;
                        }
                    }
                    if (condemn != null) {
                        final String phaseName = session.getGame().getPhaseHandler()
                                .getPhase().name();

                        if (phaseName.contains("COMBAT")) {
                            submit(session, f, condemn);
                            condemned = true;
                            settleMana(session);
                            break;
                        }
                    }
                }
                answerCommon(session, f);
            }
            Assert.assertTrue(condemned, "Condemn must be castable on the attacker");
            awaitTargetOrForced(session, "p2", "Isamaru");
            for (int i = 0; i < 20; i++) {
                DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(f);
                if (frameMatches(f, "p1", DecisionFrame.Kind.COMMANDER_MOVE)
                        || frameMatches(f, "p1",
                                DecisionFrame.Kind.REPLACEMENT_CONFIRM)) {
                    break;
                }
                answerCommon(session, f);
            }
            answerCommanderMove(session, "p1", toCommand);
            drain(session, 20);
            if (toCommand) {
                Assert.assertNotNull(
                        findZone(session, 0, ZoneType.Command, "Isamaru, Hound of Konda"),
                        "accepted commander must be in the command zone, not library");
            } else {
                Assert.assertNotNull(
                        findZone(session, 0, ZoneType.Library, "Isamaru, Hound of Konda"),
                        "declined commander must remain in the library");
            }
        }
    }

    // ------------------------------------------------------------------
    // WS05-CMD-PARTNER-ZONE: two partner commanders, separate identities.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_PartnerZoneIdentities() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-partnerzone");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Rograkh, Son of Rohgahh");
        BridgeTestSupport.addCommander(constructed.game, 0, "Kediss, Emberclaw Familiar");
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        drain(session, 10);
        Assert.assertNotNull(
                findZone(session, 0, ZoneType.Command, "Rograkh, Son of Rohgahh"),
                "Rograkh must begin in the command zone");
        Assert.assertNotNull(
                findZone(session, 0, ZoneType.Command, "Kediss, Emberclaw Familiar"),
                "Kediss must begin in the command zone");
        Assert.assertEquals(session.playerById("p1").getCommanders().size(), 2,
                "both partner commanders must be registered as separate identities");
    }

    // ------------------------------------------------------------------
    // WS05-CMD-PARTNER-TAX: independent cast counts and tax.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_PartnerTaxIndependent() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-partnertax");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Rograkh, Son of Rohgahh");
        BridgeTestSupport.addCommander(constructed.game, 0, "Kediss, Emberclaw Familiar");
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 1, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Murder", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Rograkh, Son of Rohgahh");
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Rograkh, Son of Rohgahh"),
                "Rograkh must enter");

        driveToMain(session, "p2", 80);
        castFromHand(session, "p2", "Murder");
        awaitTargetOrForced(session, "p2", "Rograkh");
        answerCommanderMove(session, "p1", true);
        drain(session, 20);

        driveToMain(session, "p1", 120);
        castCommander(session, "p1", "Rograkh, Son of Rohgahh");
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Rograkh, Son of Rohgahh"),
                "taxed Rograkh recast must resolve");
        Assert.assertEquals(commanderCastCount(session, "p1", "Rograkh, Son of Rohgahh"), 2,
                "Rograkh count must be 2");

        castCommander(session, "p1", "Kediss, Emberclaw Familiar");
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Kediss, Emberclaw Familiar"),
                "Kediss must enter");
        Assert.assertEquals(commanderCastCount(session, "p1", "Kediss, Emberclaw Familiar"), 1,
                "Kediss count must stay 1: partner tax is independent");
    }

    // ------------------------------------------------------------------
    // CARD_02: Rograkh first cast, count 1, no tax.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_Card02RograkhFirstCast() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-card02");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Rograkh, Son of Rohgahh");
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Rograkh, Son of Rohgahh");
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Rograkh, Son of Rohgahh"),
                "Rograkh must be on the P1 battlefield");
        Assert.assertEquals(commanderCastCount(session, "p1", "Rograkh, Son of Rohgahh"), 1,
                "commander cast count cmd:P1-A must be 1");
    }

    // ------------------------------------------------------------------
    // WS05-CMD-START-2 / START-3: first-turn draw skip/grant.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_FirstTurnDrawSkippedTwoPlayers() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-start-2p", 2);
        final BridgeSession session = constructed.session;
        for (int seat = 0; seat < 2; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Memnite", ZoneType.Hand);
            }
        }
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.driveStartToPriority(session, "p1", 30000);
        Assert.assertEquals(turnPlayer(session), "p1", "P1 must be the starting player");
        DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        submit(session, frame, pickOption(frame, o -> o.isPass, "pass upkeep"));
        drain(session, 10);
        Assert.assertEquals(handSize(0, session), 7,
                "2P starting player must skip the first-turn draw (7 cards)");
    }

    @Test(timeOut = 300000)
    public void testR20_FirstTurnDrawGrantedThreePlayers() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-start-3p", 3);
        final BridgeSession session = constructed.session;
        for (int seat = 0; seat < 3; seat++) {
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Memnite", ZoneType.Hand);
            }
        }
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.driveStartToPriority(session, "p1", 30000);
        Assert.assertEquals(turnPlayer(session), "p1", "P1 must be the starting player");
        DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        submit(session, frame, pickOption(frame, o -> o.isPass, "pass upkeep"));
        drain(session, 10);
        Assert.assertEquals(handSize(0, session), 8,
                "3P starting player must draw on the first turn (8 cards)");
    }

    // ------------------------------------------------------------------
    // WS05-MP-ELIM-STACK-3: conceding with an owned spell on the stack.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_EliminationStackCleanup() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-elimstack", 3);
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Lightning Bolt", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Lightning Bolt");
        awaitTargetOrForced(session, "p1", "p2");
        Assert.assertEquals(session.getGame().getStack().size(), 1,
                "Bolt must be on the stack");
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> "concede".equals(o.actionType), "p1 concedes"));
                break;
            }
            answerCommon(session, f);
        }
        drain(session, 30);
        Assert.assertEquals(session.getGame().getStack().size(), 0,
                "conceder's spell must leave the stack and never resolve");
        Assert.assertEquals(life(1, session), 40,
                "P2 life must be unchanged: Bolt never resolved");
        Assert.assertFalse(session.isTerminal(), "game must continue without P1");
        Assert.assertEquals(session.getGame().getPlayers().size(), 2,
                "two players must remain");
    }

    // ------------------------------------------------------------------
    // WS05-MP-ELIM-PRIO-3: turn/priority ring recomputes without the departed.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_EliminationRingExcludesDeparted() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-elimring", 3);
        final BridgeSession session = constructed.session;
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        concedeAs(session, "p3");
        drain(session, 20);
        final List<String> ring = new ArrayList<>();
        for (forge.game.player.Player p
                : session.getGame().getPlayersInTurnOrder()) {
            ring.add(session.playerIdOf(p));
        }
        Assert.assertEquals(ring, java.util.Arrays.asList("p1", "p2"),
                "turn ring must recompute to exactly the live players");
        Assert.assertFalse(session.isTerminal(), "game must continue");
    }

    // ------------------------------------------------------------------
    // WS05-MP-ELIM-TURN-3: active player leaves mid-turn, turn continues.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_EliminationTurnContinues() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-elimturn", 3);
        final BridgeSession session = constructed.session;
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertEquals(turnPlayer(session), "p1", "P1 must hold the turn");

        concedeAs(session, "p1");
        boolean reachedP2 = false;
        for (int i = 0; i < 120; i++) {
            if (!session.isTerminal() && turnPlayer(session).equals("p2")) {
                reachedP2 = true;
                break;
            }
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (session.isTerminal()) {
                break;
            }
            answerCommon(session, f);
        }
        Assert.assertTrue(reachedP2, "turn must continue with P2 after P1 leaves");
        Assert.assertFalse(session.isTerminal(), "game must continue");
    }

    // ------------------------------------------------------------------
    // MICRO_PREVENTION: Fog prevents all combat damage (exact 0).
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_FogPreventionZeroDamage() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-fog");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Fog", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final int p1Life = life(0, session);

        boolean fogCast = false;
        for (int i = 0; i < 120; i++) {
            if (!turnPlayer(session).equals("p2")) {
                DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
                Assert.assertNotNull(f);
                answerCommon(session, f);
                continue;
            }
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (!fogCast && frameMatches(f, "p1", DecisionFrame.Kind.PRIORITY)
                    && BridgeTestSupport.isMainPhase(session)) {
                castFromHand(session, "p1", "Fog");
                fogCast = true;
                continue;
            }
            if (f.actorPlayerId.equals("p2")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Runeclaw Bear")
                                && o.label.contains("p1"),
                        "Bear attacks p1"));
                continue;
            }
            answerCommon(session, f);
            if (fogCast && findZone(session, 0, ZoneType.Graveyard, "Fog") != null
                    && session.getGame().getStack().isEmpty()) {
                break;
            }
        }
        Assert.assertTrue(fogCast, "Fog must have been cast");
        drain(session, 30);
        Assert.assertEquals(life(0, session), p1Life,
                "P1 must lose 0 life: Fog prevents all combat damage");
        Assert.assertNotNull(findBf(session, 1, "Runeclaw Bear"),
                "attacking Bear survives (damage prevented, not dealt)");
    }

    // ------------------------------------------------------------------
    // MICRO_CONTROL + WS05-MP-ELIM-CONTROL-3: steal, revert, leave-revert.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_ControlMagicStealAndRevert() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-control");
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Control Magic", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Disenchant", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Control Magic");
        awaitTargetOrForced(session, "p1", "Runeclaw Bear");
        drain(session, 20);
        final Card stolen = findBf(session, 0, "Runeclaw Bear");
        Assert.assertNotNull(stolen, "stolen Bear must be on the P1 side");
        Assert.assertEquals(session.playerIdOf(stolen.getController()), "p1",
                "controller must be P1 while Control Magic lasts");

        driveToMain(session, "p1", 200);
        castFromHand(session, "p1", "Disenchant");
        awaitTargetOrForced(session, "p1", "Control Magic");
        drain(session, 20);
        final Card reverted = findBf(session, 1, "Runeclaw Bear");
        Assert.assertNotNull(reverted, "Bear must remain on a battlefield");
        Assert.assertEquals(session.playerIdOf(reverted.getController()), "p2",
                "control must revert to P2 when Control Magic leaves");
    }

    @Test(timeOut = 300000)
    public void testR20_ElimControlReverts() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-elimcontrol", 3);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 1, "Island", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Control Magic", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        driveToMain(session, "p2", 80);
        castFromHand(session, "p2", "Control Magic");
        awaitTargetOrForced(session, "p2", "Runeclaw Bear");
        drain(session, 20);
        Assert.assertEquals(
                session.playerIdOf(findBf(session, 1, "Runeclaw Bear").getController()), "p2",
                "P2 must control the Bear while the aura lasts");

        concedeAs(session, "p2");
        drain(session, 30);
        final Card reverted = findBf(session, 0, "Runeclaw Bear");
        Assert.assertNotNull(reverted, "P1-owned Bear must remain in the game");
        Assert.assertEquals(session.playerIdOf(reverted.getController()), "p1",
                "control must revert to P1 when P2 (and the aura) leaves");
    }

    // ------------------------------------------------------------------
    // WS05-MP-ELIM-OWNED-3: objects owned by the leaver leave entirely,
    // even when controlled by another player (no revert).
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_ElimOwnedLeavesEntirely() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-elimowned", 3);
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Act of Treason", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        driveToMain(session, "p1", 200);
        castFromHand(session, "p1", "Act of Treason");
        awaitTargetOrForced(session, "p1", "Runeclaw Bear");
        drain(session, 20);
        final Card stolen = findBf(session, 0, "Runeclaw Bear");
        Assert.assertNotNull(stolen, "stolen Bear must be on the P1 side");
        Assert.assertEquals(session.playerIdOf(stolen.getController()), "p1",
                "P1 must control the Bear while Treason lasts");
        Assert.assertEquals(session.playerIdOf(stolen.getOwner()), "p2",
                "P2 must remain the owner");

        concedeAs(session, "p2");
        drain(session, 30);
        Assert.assertFalse(session.isTerminal(), "game must continue");
        Assert.assertEquals(session.getGame().getPlayers().size(), 2,
                "two players must remain");
        boolean bearAnywhere = false;
        for (int s = 0; s < session.getGame().getPlayers().size(); s++) {
            for (ZoneType z : new ZoneType[]{ZoneType.Battlefield, ZoneType.Graveyard,
                    ZoneType.Exile, ZoneType.Hand, ZoneType.Library, ZoneType.Command}) {
                if (findZone(session, s, z, "Runeclaw Bear") != null) {
                    bearAnywhere = true;
                }
            }
        }
        Assert.assertFalse(bearAnywhere,
                "P2-owned Bear must leave the game entirely (not revert to P1)");
    }

    // ------------------------------------------------------------------
    // MICRO_CONTINUOUS_EFFECTS: Psychosis Crawler tracks hand size.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CrawlerTracksHandSize() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-crawler");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Psychosis Crawler", ZoneType.Battlefield);
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Hand);
        }
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        drain(session, 10);
        final Card crawler = findBf(session, 0, "Psychosis Crawler");
        Assert.assertNotNull(crawler, "Crawler must be on the battlefield");
        final int hand = handSize(0, session);
        Assert.assertTrue(hand >= 5, "P1 must hold the seeded hand");
        Assert.assertEquals(crawler.getNetPower(), hand,
                "Crawler power must equal P1 hand size without any trigger");
        Assert.assertEquals(crawler.getNetToughness(), hand,
                "Crawler toughness must equal P1 hand size without any trigger");
    }

    // ------------------------------------------------------------------
    // MICRO_LAYERS: Humility + Glorious Anthem leave 2/2 ability-less Bears.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_HumilityAnthemLayers() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-layers");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Llanowar Elves", ZoneType.Battlefield);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Glorious Anthem", ZoneType.Hand);
        for (int i = 0; i < 4; i++) {
            BridgeTestSupport.addCard(constructed.game, 2, "Plains", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 2, "Humility", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        driveToMain(session, "p3", 120);
        castFromHand(session, "p3", "Humility");
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 2, "Humility"), "Humility must resolve");
        driveToMain(session, "p1", 160);
        castFromHand(session, "p1", "Glorious Anthem");
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Glorious Anthem"), "Anthem must resolve");
        final Card elves = findBf(session, 0, "Llanowar Elves");
        Assert.assertNotNull(elves, "Elves must survive");
        Assert.assertEquals(elves.getNetPower(), 2,
                "1/1 under Humility with Anthem must be 2 power (layers 7b/7c)");
        Assert.assertEquals(elves.getNetToughness(), 2,
                "1/1 under Humility with Anthem must be 2 toughness (layers 7b/7c)");
        driveToMain(session, "p1", 120);
        DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        for (DecisionFrame.Option o : frame.options) {
            Assert.assertFalse("Llanowar Elves".equals(o.sourceCardName),
                    "humbled Elves must offer no ability (layer 6 removes all abilities)");
        }
    }

    // ------------------------------------------------------------------
    // MICRO_PRIORITY + MICRO_STACK: Bolt answered by Growth, stack empties.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_BoltGrowthStackOrder() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-stack");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Lightning Bolt", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Giant Growth", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Lightning Bolt");
        awaitTargetOrForced(session, "p1", "Runeclaw Bear");
        boolean growthCast = false;
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (!growthCast && frameMatches(f, "p2", DecisionFrame.Kind.PRIORITY)) {
                DecisionFrame.Option growth = null;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Giant Growth".equals(o.sourceCardName)) {
                        growth = o;
                        break;
                    }
                }
                if (growth != null) {
                    submit(session, f, growth);
                    growthCast = true;
                    continue;
                }
            }
            if (growthCast && frameMatches(f, "p2", DecisionFrame.Kind.TARGET_SELECTION)) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Runeclaw Bear"),
                        "Growth the Bear"));
                growthCast = true;
                break;
            }
            answerCommon(session, f);
        }
        drain(session, 40);
        Assert.assertNotNull(findBf(session, 1, "Runeclaw Bear"),
                "Bear must survive Bolt because Growth resolves first (LIFO)");
        Assert.assertEquals(session.getGame().getStack().size(), 0,
                "stack must be empty after both spells resolve");
    }

    // ------------------------------------------------------------------
    // MICRO_TRIGGERS: Warstorm Surge deals exactly Bear power, once.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_WarstormSurgeExactlyOnce() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-surge");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Warstorm Surge", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        final int p2Life = life(1, session);

        castFromHand(session, "p1", "Runeclaw Bear");
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (frameMatches(f, "p1", DecisionFrame.Kind.TRIGGER_PLAY)) {
                DecisionFrame.Option yes = null;
                for (DecisionFrame.Option o : f.options) {
                    if (o.label != null && o.label.contains("Yes")) {
                        yes = o;
                        break;
                    }
                }
                submit(session, f, yes != null ? yes : f.options.get(0));
                continue;
            }
            if (frameMatches(f, "p1", DecisionFrame.Kind.TARGET_SELECTION)) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("p2"),
                        "Surge at p2"));
                break;
            }
            answerCommon(session, f);
        }
        drain(session, 30);
        Assert.assertNotNull(findBf(session, 0, "Runeclaw Bear"), "Bear must enter");
        Assert.assertEquals(life(1, session), p2Life - 2,
                "Surge must trigger exactly once for exactly Bear power (2)");
    }

    // ------------------------------------------------------------------
    // HIDDEN_03: face-up exile identity is public to the foe.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_FaceUpExileVisibleToFoe() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-exilevis");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Isamaru, Hound of Konda");
        BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Plains", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Swords to Plowshares", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castCommander(session, "p1", "Isamaru, Hound of Konda");
        drain(session, 20);
        driveToMain(session, "p2", 80);
        castFromHand(session, "p2", "Swords to Plowshares");
        awaitTargetOrForced(session, "p2", "Isamaru");
        answerCommanderMove(session, "p1", false);
        drain(session, 20);
        final String foeView =
                StateProjection.gameState(session, "p2").toString();
        Assert.assertTrue(foeView.contains("Isamaru"),
                "face-up exile identity must be public to the foe");
        final String outsiderView =
                StateProjection.gameState(session, "p3").toString();
        Assert.assertTrue(outsiderView.contains("Isamaru"),
                "face-up exile identity must be public to every player");
    }

    // ------------------------------------------------------------------
    // HIDDEN_07: Thoughtseize reveal reaches exactly the legal audience.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_ThoughtseizeRevealAudience() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-reveal");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Thoughtseize", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Memnite", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 1, "Memnite", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Thoughtseize");
        awaitTargetOrForced(session, "p1", "p2");
        String casterRevealView = null;
        String outsiderRevealView = null;
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (frameMatches(f, "p1", DecisionFrame.Kind.HIDDEN_ZONE_SELECTION)) {
                casterRevealView =
                        StateProjection.gameState(session, "p1").toString();
                outsiderRevealView =
                        StateProjection.gameState(session, "p3").toString();
                submit(session, f, f.options.get(0));
                continue;
            }
            if (frameMatches(f, "p1", DecisionFrame.Kind.GENERIC_SELECTION)
                    || frameMatches(f, "p1", DecisionFrame.Kind.COST_SELECTION)) {
                submit(session, f, f.options.get(0));
                continue;
            }
            if (session.getGame().getStack().isEmpty()
                    && findZone(session, 0, ZoneType.Graveyard, "Thoughtseize") != null) {
                break;
            }
            answerCommon(session, f);
        }
        drain(session, 20);
        Assert.assertNotNull(casterRevealView, "reveal choice must have been offered");
        Assert.assertTrue(casterRevealView.contains("Memnite"),
                "caster must observe the revealed hand while choosing");
        Assert.assertNotNull(outsiderRevealView, "outsider view must have been captured");
        Assert.assertFalse(outsiderRevealView.contains("Memnite"),
                "reveal must not reach players outside the legal audience");
    }

    // ------------------------------------------------------------------
    // HIDDEN_10: generic scry offers actor knowledge, hides it from foes.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_ScryActorSeesTop() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-scry");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Opt", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Opt");
        boolean scrySeen = false;
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (frameMatches(f, "p1", DecisionFrame.Kind.GENERIC_SELECTION)) {
                Assert.assertFalse(f.options.isEmpty(),
                        "scry must offer the actor a real choice");
                scrySeen = true;
                submit(session, f, f.options.get(0));
                continue;
            }
            if (session.getGame().getStack().isEmpty()
                    && findZone(session, 0, ZoneType.Graveyard, "Opt") != null) {
                break;
            }
            answerCommon(session, f);
        }
        Assert.assertTrue(scrySeen, "generic scry decision must be offered to the actor");
        Assert.assertEquals(
                StateProjection.gameState(session, "p2").getAsJsonArray("legal_actions").size(),
                0, "scry knowledge must stay hidden from the foe (no legal actions)");
    }

    // ------------------------------------------------------------------
    // Extra turns: Time Walk grants P1 an immediate extra turn, then order
    // resumes. Covers the extra-turn mechanism (WS05-MP-TURN shapes).
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_TimeWalkExtraTurn() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-timewalk");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Time Walk", ZoneType.Hand);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertEquals(turnPlayer(session), "p1", "P1 must hold the turn");

        castFromHand(session, "p1", "Time Walk");
        final boolean walkTargeted = awaitTargetOrForced(session, "p1", "p1");
        Assert.assertFalse(walkTargeted, "Time Walk targets nothing (controller takes the turn)");
        final List<String> turnTakers = new ArrayList<>();
        int lastTurn = session.getGame().getPhaseHandler().getTurn();
        turnTakers.add(turnPlayer(session));
        for (int i = 0; i < 600 && turnTakers.size() < 6; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            final int nowTurn = session.getGame().getPhaseHandler().getTurn();
            if (nowTurn != lastTurn) {
                lastTurn = nowTurn;
                turnTakers.add(turnPlayer(session));
            }
            if (!session.isTerminal()) {
                answerCommon(session, f);
            }
        }
        Assert.assertTrue(turnTakers.size() >= 6, "must observe six turn takings");
        boolean extraFound = false;
        for (int i = 0; i + 2 < turnTakers.size(); i++) {
            if (turnTakers.get(i).equals("p1") && turnTakers.get(i + 1).equals("p1")
                    && turnTakers.get(i + 2).equals("p2")) {
                extraFound = true;
                break;
            }
        }
        Assert.assertTrue(extraFound,
                "Time Walk must grant P1 an extra turn before P2 (saw " + turnTakers + ")");
    }

    // ------------------------------------------------------------------
    // Coin-flip call framing: Frenetic Efreet parks a pilot-called flip
    // (no random fallback, no silent skip); the Rules engine flips.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_CoinFlipCallOffered() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-flip");
        final BridgeSession session = constructed.session;
        for (int i = 0; i < 3; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Frenetic Efreet", ZoneType.Battlefield);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        Assert.assertNotNull(findBf(session, 0, "Frenetic Efreet"),
                "Efreet must be on the battlefield");

        boolean flipAnswered = false;
        boolean activatedOnce = false;
        for (int i = 0; i < 80; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (!flipAnswered && !activatedOnce
                    && frameMatches(f, "p1", DecisionFrame.Kind.PRIORITY)) {
                DecisionFrame.Option activate = null;
                for (DecisionFrame.Option o : f.options) {
                    if ("Frenetic Efreet".equals(o.sourceCardName)
                            && !"cast_spell".equals(o.actionType)) {
                        activate = o;
                        break;
                    }
                }
                if (activate != null) {
                    submit(session, f, activate);
                    activatedOnce = true;
                    continue;
                }
            }
            if (frameMatches(f, "p1", DecisionFrame.Kind.BINARY_CHOICE)) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.toLowerCase().contains("head"),
                        "call heads"));
                flipAnswered = true;
                continue;
            }
            if (flipAnswered && session.getGame().getStack().isEmpty()) {
                break;
            }
            answerCommon(session, f);
        }
        Assert.assertTrue(flipAnswered, "coin-flip call must be offered to the pilot");
        Assert.assertFalse(session.isTerminal(), "game must continue after the flip");
    }

    // ------------------------------------------------------------------
    // MICRO_COMBAT: two 2/2s trade via simultaneous damage + SBA.
    // ------------------------------------------------------------------
    @Test(timeOut = 300000)
    public void testR20_BearsTradeViaCombat() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr20-trade");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 30);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        for (int i = 0; i < 60; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1")
                    && f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Runeclaw Bear")
                                && o.label.contains("p2"),
                        "Bear attacks p2"));
                break;
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 30; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("Runeclaw Bear"),
                        "Bear blocks Bear"));
                break;
            }
            answerCommon(session, f);
        }
        drain(session, 30);
        Assert.assertNull(findBf(session, 0, "Runeclaw Bear"),
                "attacker must die to simultaneous combat damage");
        Assert.assertNull(findBf(session, 1, "Runeclaw Bear"),
                "blocker must die to simultaneous combat damage");
        Assert.assertNotNull(findZone(session, 0, ZoneType.Graveyard, "Runeclaw Bear"),
                "attacker must be in its owner's graveyard after SBA");
        Assert.assertNotNull(findZone(session, 1, ZoneType.Graveyard, "Runeclaw Bear"),
                "blocker must be in its owner's graveyard after SBA");
    }
}
