package forge.bridge;

import com.google.gson.JsonObject;
import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WSR24 PB-07 Forge-side actual-card preparation (PARTIAL by design).
 *
 * <p>Denominator: Lab {@code ACTUAL_CARD_DOMAIN_v1.json#regression_corpus_29}
 * (read-only input, never invented here). This class proves import +
 * construction for all 29 and adds one shared-mechanics runtime execution
 * (multiplayer opponent-choice discard via Syphon Mind, same decision family
 * as the HIDDEN_08 Duress channel). Per-card runtime behavior beyond the
 * pre-existing families stays explicitly open; see
 * wsr24-evidence-closure/PB07_EVIDENCE.json.
 */
public class WsR24Pb07ActualCardPreparationTest {

    /** Exact Lab regression_corpus_29, in Lab order. */
    static final String[] CORPUS_29 = {
            "Ishai, Ojutai Dragonspeaker",
            "Rograkh, Son of Rohgahh",
            "Esior, Wardwing Familiar",
            "Kediss, Emberclaw Familiar",
            "Veyran, Voice of Duality",
            "Harmonic Prodigy",
            "Narset, Parter of Veils",
            "Jeska, Thrice Reborn",
            "Magma Opus",
            "Wash Away",
            "Wear // Tear",
            "Dig Through Time",
            "Flare of Duplication",
            "Vandalblast",
            "Finale of Revelation",
            "Psychosis Crawler",
            "Kaervek the Merciless",
            "Shriekmaw",
            "Butcher of Malakir",
            "Syphon Mind",
            "Gratuitous Violence",
            "Bolt Bend",
            "Makeshift Mannequin",
            "Warstorm Surge",
            "Basilisk Collar",
            "Burn Down the House",
            "Path of Ancestry",
            "Find // Finality",
            "Boseiju Reaches Skyward // Branch of Boseiju"
    };

    @BeforeClass
    public void initEngine() {
        BridgeTestSupport.ensureEngine();
    }

    @Test(timeOut = 300000)
    public void testAll29ResolveAndConstruct() {
        // Import + construction level only (explicitly NOT runtime behavior):
        // every denominator identity must resolve in the Forge card database
        // and construct a real engine Card object in a real game. Modal DFCs
        // are keyed by front face in Forge; the exact denominator string is
        // tried first and the resolution path is recorded per card.
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-construct", 2);
        final List<String> placed = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        final List<String> resolvedByFace = new ArrayList<>();
        for (String name : CORPUS_29) {
            if (tryPlace(constructed, name)) {
                placed.add(name);
                continue;
            }
            final int split = name.indexOf(" // ");
            if (split > 0 && tryPlace(constructed, name.substring(0, split))) {
                placed.add(name);
                resolvedByFace.add(name);
                continue;
            }
            missing.add(name);
        }
        Assert.assertTrue(missing.isEmpty(),
                "denominator identities missing from Forge DB: " + missing);
        Assert.assertEquals(placed.size(), 29);
        System.err.println("[wsr24-pb07] resolved-by-front-face: " + resolvedByFace);
        final int engineCount = constructed.game.getPlayers().get(0)
                .getZone(ZoneType.Library).getCards().size();
        Assert.assertEquals(engineCount, 29, "all 29 must construct into the engine zone");
        constructed.session.shutdown(1000);
    }

    private static boolean tryPlace(BridgeTestSupport.ConstructedGame constructed,
            String name) {
        try {
            final Card card = BridgeTestSupport.addCard(constructed.game, 0, name,
                    ZoneType.Library);
            return card != null;
        } catch (AssertionError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Shared-mechanics runtime: multiplayer opponent-choice discard.
    // ------------------------------------------------------------------

    private static BridgeSession.SubmitOutcome submit(BridgeSession session,
            DecisionFrame frame, DecisionFrame.Option option) {
        final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                option.optionId, option.actionType, frame.revision);
        Assert.assertTrue(outcome.applied,
                "submit failed: " + outcome.errorCode + " " + outcome.errorMessage);
        Assert.assertTrue(outcome.executionOk,
                "submit applied but not executed: " + outcome.errorCode);
        return outcome;
    }

    private static boolean frameMatches(DecisionFrame frame, String actorId,
            DecisionFrame.Kind kind) {
        return frame.kind == kind && frame.actorPlayerId.equals(actorId)
                && frame.status == DecisionFrame.Status.SUPPORTED;
    }

    private static void payManaFirstWorking(BridgeSession session) {
        final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
        Assert.assertNotNull(frame);
        Assert.assertEquals(frame.kind, DecisionFrame.Kind.MANA_PAYMENT);
        for (DecisionFrame.Option option : frame.options) {
            final BridgeSession.SubmitOutcome outcome = session.submit(frame.actorPlayerId,
                    option.optionId, option.actionType, frame.revision);
            if (outcome.applied) {
                return;
            }
        }
        throw new AssertionError("no offered mana payment applied");
    }

    private static void settleMana(BridgeSession session) {
        final long deadline = System.currentTimeMillis() + 5000;
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

    private static void answerCommon(BridgeSession session, DecisionFrame frame) {
        if (frame.kind == DecisionFrame.Kind.PRIORITY) {
            DecisionFrame.Option pass = null;
            for (DecisionFrame.Option o : frame.options) {
                if (o.isPass) {
                    pass = o;
                    break;
                }
            }
            Assert.assertNotNull(pass, "pass must be offered");
            submit(session, frame, pass);
        } else if (frame.kind == DecisionFrame.Kind.MANA_PAYMENT) {
            payManaFirstWorking(session);
        } else if (frame.kind == DecisionFrame.Kind.TARGET_SELECTION) {
            submit(session, frame, frame.options.get(0));
        } else if (frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                || frame.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
            for (DecisionFrame.Option o : frame.options) {
                if (o.label != null && (o.label.contains("No attack")
                        || o.label.contains("No block"))) {
                    submit(session, frame, o);
                    return;
                }
            }
            throw new AssertionError("no combat decline offered");
        } else {
            throw new AssertionError("unexpected " + frame.kind + " for " + frame.actorPlayerId);
        }
    }

    private static void castFromHand(BridgeSession session, String actorId, String cardName) {
        for (int i = 0; i < 40; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frameMatches(frame, actorId, DecisionFrame.Kind.PRIORITY)) {
                for (DecisionFrame.Option o : frame.options) {
                    if ("cast_spell".equals(o.actionType) && cardName.equals(o.sourceCardName)) {
                        submit(session, frame, o);
                        settleMana(session);
                        return;
                    }
                }
            }
            answerCommon(session, frame);
        }
        throw new AssertionError("never reached cast of " + cardName + " for " + actorId);
    }

    private static Set<String> zoneNames(JsonObject state, String playerId, String zone) {
        final Set<String> names = new HashSet<>();
        for (Object element : state.getAsJsonArray("players")) {
            final JsonObject playerState = (JsonObject) element;
            if (playerState.get("player_id").getAsString().equals(playerId)) {
                for (Object entry : playerState.getAsJsonObject("zones")
                        .getAsJsonArray(zone)) {
                    names.add(entry.toString().replace("\"", ""));
                }
                return names;
            }
        }
        throw new AssertionError("no such player " + playerId);
    }

    @Test(timeOut = 300000)
    public void testSyphonMindMultiplayerDiscardRuntime() {
        // Real Syphon Mind through the real pipeline: each of 3 opponents
        // chooses its discard from a 2-card hand via an actor-scoped
        // HIDDEN_ZONE_SELECTION frame; the caster learns nothing early;
        // discards go public afterwards. Distinct canaries per seat keep the
        // audience assertions exact.
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr24-pb07-syphon", 4);
        final BridgeSession session = constructed.session;
        // Syphon Mind costs {3}{B}: five Swamps float enough black for the
        // full payment through framed mid-payment taps.
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Swamp", ZoneType.Battlefield);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Syphon Mind", ZoneType.Hand);
        final String[] canaries = {"Memnite", "Ornithopter", "Phyrexian Walker"};
        for (int seat = 1; seat <= 3; seat++) {
            BridgeTestSupport.addCard(constructed.game, seat, canaries[seat - 1],
                    ZoneType.Hand);
            BridgeTestSupport.addCard(constructed.game, seat, canaries[seat - 1],
                    ZoneType.Hand);
            for (int i = 0; i < 5; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        for (int i = 0; i < 5; i++) {
            BridgeTestSupport.addCard(constructed.game, 0, "Plains", ZoneType.Library);
        }
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        castFromHand(session, "p1", "Syphon Mind");

        // Three opponent discard frames, one per seat, in engine order.
        final Set<String> discardingActors = new HashSet<>();
        for (int i = 0; i < 40 && discardingActors.size() < 3; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame, "discard frame never parked");
            if (frame.kind == DecisionFrame.Kind.HIDDEN_ZONE_SELECTION
                    && frame.status == DecisionFrame.Status.SUPPORTED
                    && !discardingActors.contains(frame.actorPlayerId)
                    && !frame.actorPlayerId.equals("p1")) {
                Assert.assertEquals(frame.options.size(), 2,
                        "two-card hand must offer a real discard choice");
                // Audience: every non-actor principal must observe no identity
                // of the discarder's choice (caster included: Syphon Mind
                // grants the caster no look).
                for (String observer : new String[]{"p1", "p2", "p3", "p4"}) {
                    if (observer.equals(frame.actorPlayerId)) {
                        continue;
                    }
                    final String flat = StateProjection.gameState(session, observer)
                            .toString();
                    final String victimCanary = canaries[
                            Integer.parseInt(frame.actorPlayerId.substring(1)) - 2];
                    Assert.assertFalse(flat.contains(victimCanary),
                            observer + " learned " + frame.actorPlayerId + "'s choice");
                    for (DecisionFrame.Option option : frame.options) {
                        Assert.assertFalse(flat.contains(option.optionId),
                                observer + " learned option id");
                    }
                }
                Assert.assertEquals(StateProjection.gameState(session,
                        frame.actorPlayerId.equals("p2") ? "p3" : "p2")
                        .getAsJsonArray("legal_actions").size(), 0);
                discardingActors.add(frame.actorPlayerId);
                submit(session, frame, frame.options.get(0));
                continue;
            }
            answerCommon(session, frame);
        }
        Assert.assertEquals(discardingActors.size(), 3,
                "all three opponents must discard, got " + discardingActors);

        // Resolution publicity: each graveyard shows its discarded canary.
        for (int i = 0; i < 40; i++) {
            final DecisionFrame frame = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(frame);
            if (frame.kind == DecisionFrame.Kind.PRIORITY) {
                break;
            }
            answerCommon(session, frame);
        }
        final JsonObject after = StateProjection.gameState(session, "p1");
        for (int seat = 2; seat <= 4; seat++) {
            final Set<String> grave = zoneNames(after, "p" + seat, "graveyard");
            Assert.assertTrue(grave.contains(canaries[seat - 2]),
                    "p" + seat + " graveyard must show the discard publicly: " + grave);
        }
        session.shutdown(5000);
    }
}
