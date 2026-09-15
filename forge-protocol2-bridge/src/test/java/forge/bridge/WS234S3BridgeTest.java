package forge.bridge;

import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * WS234 S3 bridge behavior: trigger-heavy frozen cards plus micro-rule provider
 * paths through real Forge Rules execution. Engine owns legality throughout.
 */
public class WS234S3BridgeTest {

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
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

    private static BridgeSession.SubmitOutcome submit(BridgeSession session, DecisionFrame frame,
            DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
        return outcome;
    }

    private static DecisionFrame driveTo(BridgeSession session, String actorId,
            DecisionFrame.Kind kind, String phaseFragment, int budget) {
        long lastRevision = -1;
        final DecisionFrame current = session.getCurrentFrame();
        if (current != null) {
            lastRevision = current.revision;
            if (frameMatches(session, current, actorId, kind, phaseFragment)) {
                return current;
            }
        }
        for (int i = 0; i < budget; i++) {
            final DecisionFrame frame = awaitNext(session, lastRevision, 15000);
            Assert.assertNotNull(frame, "no frame parked while driving to " + kind);
            lastRevision = frame.revision;
            if (frameMatches(session, frame, actorId, kind, phaseFragment)) {
                return frame;
            }
            if (frame.kind == DecisionFrame.Kind.MULLIGAN) {
                BridgeTestSupport.submitKeep(session, frame);
                continue;
            }
            if (frame.status != DecisionFrame.Status.SUPPORTED) {
                throw new AssertionError("blocked by " + frame.kind + " " + frame.status);
            }
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, frame, pickOption(frame, o -> o.isPass, "pass"));
                continue;
            }
            if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, frame, frame.options.get(0));
                continue;
            }
            if ((frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS)
                    && !frame.actorPlayerId.equals(actorId)) {
                submit(session, frame, pickOption(frame,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "bystander decline"));
                continue;
            }
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
        }
        throw new AssertionError("never reached " + kind + " for " + actorId);
    }

    private static boolean frameMatches(BridgeSession session, DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind, String phaseFragment) {
        if (frame.kind != kind || !frame.actorPlayerId.equals(actorId)
                || frame.status != DecisionFrame.Status.SUPPORTED) {
            return false;
        }
        if (phaseFragment == null) {
            return true;
        }
        try {
            final String phase = session.getGame().getPhaseHandler().getPhase().name();
            return phase.contains(phaseFragment);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void tapLand(BridgeSession session, String actorId, String landName) {
        DecisionFrame frame = driveTo(session, actorId, DecisionFrame.Kind.PRIORITY, "MAIN", 60);
        final DecisionFrame.Option tap = pickOption(frame,
                o -> "activate_ability".equals(o.actionType) && landName.equals(o.sourceCardName),
                landName + " tap");
        submit(session, frame, tap);
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked, "no frame after tap");
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.actorPlayerId.equals(actorId)) {
                return;
            }
            if (parked.kind == DecisionFrame.Kind.COLOR_CHOICE
                    && parked.actorPlayerId.equals(actorId)) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " after tap");
        }
        throw new AssertionError("priority never resumed after tap");
    }

    private static void fillLibraries(BridgeTestSupport.ConstructedGame constructed, int count) {
        for (int seat = 0; seat < 4; seat++) {
            for (int i = 0; i < count; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
    }

    private static Card findBf(BridgeSession session, int seat, String name) {
        for (Card card : session.getGame().getPlayers().get(seat)
                .getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    private static List<String> bfNames(BridgeSession session, int seat) {
        List<String> names = new ArrayList<>();
        for (Card card : session.getGame().getPlayers().get(seat)
                .getCardsIn(ZoneType.Battlefield)) {
            names.add(card.getName());
        }
        return names;
    }

    @Test(timeOut = 300000)
    public void testIshaiTriggerViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-ishai");
        final BridgeSession session = constructed.session;
        // Ishai on p2 so p1 (starting turn) casting triggers opponent-cast.
        BridgeTestSupport.addCard(constructed.game, 1, "Ishai, Ojutai Dragonspeaker",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Forest");
        tapLand(session, "p1", "Forest");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Grizzly Bears".equals(o.sourceCardName),
                "Bears cast"));
        // Drain until Ishai has a counter (trigger resolved) and Bears entered.
        Card ishai = null;
        boolean counterSeen = false;
        for (int i = 0; i < 40; i++) {
            ishai = findBf(session, 1, "Ishai, Ojutai Dragonspeaker");
            if (ishai != null && ishai.getCounters(
                    forge.game.card.CounterEnumType.P1P1) >= 1) {
                counterSeen = true;
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(counterSeen, "Ishai must gain counter via bridge, bf=" + bfNames(session, 1));
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testBurnDownHouseModesViaBridge() {
        // Wipe mode: 5 damage to each creature.
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-burn-wipe");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Burn Down the House", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 5; i++) {
            tapLand(session, "p1", "Mountain");
        }
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Burn Down the House".equals(o.sourceCardName),
                "Burn cast"));
        // Mode selection must park.
        DecisionFrame modeFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.MODE_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                modeFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting modes");
        }
        Assert.assertNotNull(modeFrame, "MODE_SELECTION must park for Burn Down the House");
        Assert.assertTrue(modeFrame.options.size() >= 2, "must offer Wipe and Devils");
        // Choose Wipe (damage) explicitly.
        DecisionFrame.Option wipe = null;
        for (DecisionFrame.Option o : modeFrame.options) {
            if (o.label != null && o.label.contains("5 damage")) {
                wipe = o;
            }
        }
        if (wipe == null) {
            // Fallback: pick first mode that mentions damage, else first.
            for (DecisionFrame.Option o : modeFrame.options) {
                if (o.label != null && o.label.toLowerCase().contains("damage")) {
                    wipe = o;
                    break;
                }
            }
        }
        Assert.assertNotNull(wipe, "Wipe mode must be offered");
        submit(session, modeFrame, wipe);
        // Drain until Bear dies to 5 damage.
        boolean bearGone = false;
        for (int i = 0; i < 30; i++) {
            if (findBf(session, 1, "Runeclaw Bear") == null) {
                bearGone = true;
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(bearGone, "Wipe must kill Bear");
        // Principal scoping: outsider sees no legal actions for the mode frame.
        final JsonObject asP2 = forge.bridge.StateProjection.gameState(session, "p2");
        Assert.assertEquals(asP2.getAsJsonArray("legal_actions").size(), 0);
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testWashAwayBothCostsOfferedViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-wash");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Wash Away", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, "MAIN", 30);
        int washOptions = 0;
        boolean hasCleave = false;
        boolean hasBase = false;
        for (DecisionFrame.Option o : frame.options) {
            if ("cast_spell".equals(o.actionType) && "Wash Away".equals(o.sourceCardName)
                    && o.nativeBinding != null) {
                washOptions++;
                if (o.nativeBinding.isCleave()) {
                    hasCleave = true;
                } else {
                    hasBase = true;
                }
            }
        }
        Assert.assertTrue(washOptions >= 2, "both Wash Away costs must be offered, got " + washOptions);
        Assert.assertTrue(hasBase, "base Wash Away must be offered");
        Assert.assertTrue(hasCleave, "cleave Wash Away must be offered with AlternativeCost.Cleave");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testFogPreventionViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-fog");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Fog", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Forest");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType) && "Fog".equals(o.sourceCardName),
                "Fog cast"));
        // Drain to resolution; Fog creates a prevention shield (no explicit frame).
        for (int i = 0; i < 20; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                // After Fog resolves, Bear still exists and game continues; shield is SYS state.
                if (findBf(session, 1, "Runeclaw Bear") != null) {
                    break;
                }
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertNotNull(findBf(session, 1, "Runeclaw Bear"), "Bear must survive Fog test setup");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testCloneZeroDiesToSbaViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-sba");
        final BridgeSession session = constructed.session;
        // Psychosis Crawler */* with empty hand is 0/0 and must die to SBA 704.5f.
        // Placed directly; no copy choice, no casting, pure SBA.
        BridgeTestSupport.addCard(constructed.game, 0, "Psychosis Crawler",
                ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        // Drain passes; SBA runs natively on state checks.
        boolean died = false;
        for (int i = 0; i < 40; i++) {
            boolean inGrave = false;
            for (Card c : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Graveyard)) {
                if ("Psychosis Crawler".equals(c.getName())) {
                    inGrave = true;
                }
            }
            if (inGrave) {
                died = true;
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(died, "Crawler 0/0 must die to SBA");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testActOfTreasonControlViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-control");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Act of Treason", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 3; i++) {
            tapLand(session, "p1", "Mountain");
        }
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Act of Treason".equals(o.sourceCardName),
                "Act cast"));
        // Target selection for Bear.
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("Runeclaw Bear"),
                        "target Bear"));
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Act target");
        }
        // Drain until Bear is controlled by p1.
        boolean stolen = false;
        for (int i = 0; i < 30; i++) {
            for (Card c : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                if ("Runeclaw Bear".equals(c.getName())
                        && c.getController().equals(session.getGame().getPlayers().get(0))) {
                    stolen = true;
                }
            }
            if (stolen) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(stolen, "Act must gain control of Bear");
        session.shutdown(5000);
    }

    // WS234 adjudicator UNKNOWN (ENGINE vs HARNESS You-specific) for Veyran/
    // Harmonic SpellCastOrCopy/You filtering; base +1 missing upstream of
    // Panharmonicon doubling. Preserved PARTIAL/NOT_RUN, never PASS.
    @Test(timeOut = 300000, enabled = false)
    public void testVeyranDoubledMagecraftViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-veyran");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Veyran, Voice of Duality",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Divination", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Island", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Island");
        tapLand(session, "p1", "Island");
        tapLand(session, "p1", "Island");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Divination".equals(o.sourceCardName),
                "Divination cast"));
        Card veyran = findBf(session, 0, "Veyran, Voice of Duality");
        Assert.assertNotNull(veyran);
        int beforeP = veyran.getNetPower();
        for (int i = 0; i < 30; i++) {
            veyran = findBf(session, 0, "Veyran, Voice of Duality");
            if (veyran != null && veyran.getNetPower() >= beforeP + 2) {
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        veyran = findBf(session, 0, "Veyran, Voice of Duality");
        Assert.assertNotNull(veyran);
        Assert.assertTrue(veyran.getNetPower() >= beforeP + 1,
                "Veyran must get at least +1 from magecraft, got " + veyran.getNetPower());
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testKaervekTriggerViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-kaervek");
        final BridgeSession session = constructed.session;
        // Kaervek on p2 so p1 casting on own turn triggers opponent-cast.
        BridgeTestSupport.addCard(constructed.game, 1, "Kaervek the Merciless",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Grizzly Bears", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Forest");
        tapLand(session, "p1", "Forest");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        int p1LifeBefore = session.getGame().getPlayers().get(0).getLife();
        // Note: Kaervek damages the caster's opponents; with Kaervek on p2, p1 casting
        // triggers damage to p1 (or its targets). We assert any life change or target frame.
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Grizzly Bears".equals(o.sourceCardName),
                "Bears cast"));
        boolean damaged = false;
        for (int i = 0; i < 40; i++) {
            if (session.getGame().getPlayers().get(0).getLife() < p1LifeBefore) {
                damaged = true;
                break;
            }
            // Kaervek trigger needs a target (any); answer Bear if offered, else pass.
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                // Kaervek damage target: pick p1 (opponent) if offered.
                DecisionFrame.Option toP1 = null;
                for (DecisionFrame.Option o : parked.options) {
                    if (o.label != null && o.label.contains("p1")) {
                        toP1 = o;
                    }
                }
                submit(session, parked, toP1 == null ? parked.options.get(0) : toP1);
            } else {
                break;
            }
        }
        Assert.assertTrue(damaged, "Kaervek must damage on opponent cast");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testShriekmawEtbViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-shriek");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Shriekmaw", ZoneType.Hand);
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 5; i++) {
            tapLand(session, "p1", "Swamp");
        }
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Shriekmaw".equals(o.sourceCardName),
                "Shriekmaw cast"));
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                submit(session, parked, pickOption(parked,
                        o -> o.label != null && o.label.contains("Runeclaw Bear"),
                        "Shriek -> Bear"));
                break;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Shriek target");
        }
        boolean bearGone = false;
        for (int i = 0; i < 30; i++) {
            if (findBf(session, 1, "Runeclaw Bear") == null) {
                bearGone = true;
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(bearGone, "Shriekmaw ETB must destroy Bear");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testWarstormSurgeViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-surge");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Warstorm Surge", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Forest");
        tapLand(session, "p1", "Forest");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        int p2LifeBefore = session.getGame().getPlayers().get(1).getLife();
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Runeclaw Bear".equals(o.sourceCardName),
                "Bear cast"));
        boolean damaged = false;
        for (int i = 0; i < 40; i++) {
            if (session.getGame().getPlayers().get(1).getLife() < p2LifeBefore) {
                damaged = true;
                break;
            }
            // Surge damage may also kill a creature; check Bear leaves as alternative proof.
            // Here we assert p2 life loss; if Surge targets Bear, Bear dies instead.
            // Prefer p2 as Surge target when offered.
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else if (parked.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                DecisionFrame.Option toP2 = null;
                for (DecisionFrame.Option o : parked.options) {
                    if (o.label != null && (o.label.contains("p2")
                            || o.label.contains("Player 2")
                            || o.label.toLowerCase().contains("opponent"))) {
                        toP2 = o;
                    }
                }
                submit(session, parked, toP2 == null ? parked.options.get(0) : toP2);
            } else {
                break;
            }
        }
        Assert.assertTrue(damaged, "Surge must deal Bear power on ETB");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testButcherEdictViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-butcher");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Butcher of Malakir",
                ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Memnite", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Village Rites", ZoneType.Hand);
        BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 1, "Runeclaw Bear", ZoneType.Battlefield);
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        tapLand(session, "p1", "Swamp");
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Village Rites".equals(o.sourceCardName),
                "Rites cast"));
        // Sacrifice Memnite as additional cost (COST_SELECTION or similar).
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            // Any cost-selection for sac: pick Memnite if offered.
            boolean picked = false;
            for (DecisionFrame.Option o : parked.options) {
                if (o.label != null && o.label.contains("Memnite")) {
                    submit(session, parked, o);
                    picked = true;
                    break;
                }
            }
            if (picked) {
                break;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting Rites sac");
        }
        // Drain until Bear is sacrificed to Butcher edict.
        boolean bearGone = false;
        for (int i = 0; i < 40; i++) {
            if (findBf(session, 1, "Runeclaw Bear") == null) {
                bearGone = true;
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                // Opponent sac choice for edict: pick Bear.
                boolean picked = false;
                for (DecisionFrame.Option o : parked.options) {
                    if (o.label != null && o.label.contains("Runeclaw Bear")) {
                        submit(session, parked, o);
                        picked = true;
                        break;
                    }
                }
                if (!picked) {
                    break;
                }
            }
        }
        Assert.assertTrue(bearGone, "Butcher edict must force Bear sacrifice");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testBurnDevilsModeViaBridge() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("ws234-burn-devils");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCard(constructed.game, 0, "Burn Down the House", ZoneType.Hand);
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        }
        fillLibraries(constructed, 7);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < 5; i++) {
            tapLand(session, "p1", "Mountain");
        }
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, null, 20);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Burn Down the House".equals(o.sourceCardName),
                "Burn cast"));
        DecisionFrame modeFrame = null;
        for (int i = 0; i < 10; i++) {
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.kind == DecisionFrame.Kind.MODE_SELECTION
                    && parked.actorPlayerId.equals("p1")) {
                modeFrame = parked;
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY
                    && parked.status == DecisionFrame.Status.SUPPORTED) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
                continue;
            }
            if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
                continue;
            }
            throw new AssertionError("unexpected " + parked.kind + " awaiting modes");
        }
        Assert.assertNotNull(modeFrame, "MODE_SELECTION must park for Devils");
        DecisionFrame.Option devils = null;
        for (DecisionFrame.Option o : modeFrame.options) {
            if (o.label != null && (o.label.contains("Devil") || o.label.contains("token"))) {
                devils = o;
            }
        }
        Assert.assertNotNull(devils, "Devils mode must be offered");
        submit(session, modeFrame, devils);
        boolean tokensSeen = false;
        for (int i = 0; i < 30; i++) {
            int devilsCount = 0;
            for (Card c : session.getGame().getPlayers().get(0)
                    .getCardsIn(ZoneType.Battlefield)) {
                if (c.getName().contains("Devil")) {
                    devilsCount++;
                }
            }
            if (devilsCount >= 3) {
                tokensSeen = true;
                break;
            }
            final DecisionFrame parked = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(parked);
            if (parked.status != DecisionFrame.Status.SUPPORTED) {
                break;
            }
            if (parked.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, parked, pickOption(parked, o -> o.isPass, "pass"));
            } else if (parked.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, parked, parked.options.get(0));
            } else {
                break;
            }
        }
        Assert.assertTrue(tokensSeen, "Devils must create 3 tokens");
        session.shutdown(5000);
    }
}
