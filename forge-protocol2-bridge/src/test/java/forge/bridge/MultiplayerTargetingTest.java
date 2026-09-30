package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * Targeting on a 4-player board (CR 115, 601.2c): four players and eight
 * creatures give "any target" twelve legal choices. Target selection failed
 * closed above nine candidates, so any burn spell or removal in a real
 * multiplayer midgame halted the pilot. Larger sets are now chosen one
 * target at a time from the engine's own candidates.
 */
public class MultiplayerTargetingTest {

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
            seen.append('[').append(option.actionType).append('|').append(option.label).append(']');
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

    /** 4P board: every player controls two creatures; p3 has the Craw Wurm. */
    private static BridgeTestSupport.ConstructedGame board(String id, String spell,
            String... extraHandThenIslands) {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame(id, 4);
        BridgeTestSupport.addCard(constructed.game, 0, spell, ZoneType.Hand);
        for (String extra : extraHandThenIslands) {
            BridgeTestSupport.addCard(constructed.game, 0, extra,
                    "Island".equals(extra) ? ZoneType.Battlefield : ZoneType.Hand);
        }
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        final String[][] creatures = {
            {"Llanowar Elves", "Elvish Mystic"},
            {"Grizzly Bears", "Runeclaw Bear"},
            {"Craw Wurm", "Raging Goblin"},
            {"Palace Guard", "Longtusk Cub"},
        };
        for (int seat = 0; seat < 4; seat++) {
            for (String name : creatures[seat]) {
                BridgeTestSupport.addCard(constructed.game, seat, name, ZoneType.Battlefield);
            }
            for (int i = 0; i < 7; i++) {
                BridgeTestSupport.addCard(constructed.game, seat, "Plains", ZoneType.Library);
            }
        }
        BridgeTestSupport.launchConstructed(constructed);
        return constructed;
    }

    private static Card find(BridgeSession session, int seat, String name, ZoneType zone) {
        for (Card card : session.getGame().getPlayers().get(seat).getCardsIn(zone)) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    /** Casts {@code spell}, answering target frames with {@code targets} in order. */
    private static int cast(BridgeSession session, String spell, int mana, String... targets) {
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (int i = 0; i < mana; i++) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && "Mountain".equals(o.sourceCardName), "Mountain"));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && spell.equals(o.sourceCardName), "cast " + spell));
        int targetFrames = 0;
        boolean cast = false;
        for (int i = 0; i < 40 && (!cast || !session.getGame().getStack().isEmpty()); i++) {
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                final String want = targets[targetFrames];
                if (targetFrames == 0) {
                    Assert.assertEquals(f.options.size(), 12,
                            "four players and eight creatures are legal");
                    // Every option carries an identity reference, so equally
                    // named objects of different players stay distinguishable.
                    final java.util.Set<String> refs = new java.util.HashSet<>();
                    for (com.google.gson.JsonElement e : StateProjection.legalActions(session)) {
                        final com.google.gson.JsonArray r = e.getAsJsonObject()
                                .getAsJsonObject("metadata").getAsJsonArray("object_refs");
                        Assert.assertNotNull(r, "object_refs on every target option");
                        Assert.assertEquals(r.size(), 1);
                        final com.google.gson.JsonObject ref = r.get(0).getAsJsonObject();
                        if ("card".equals(ref.get("kind").getAsString())) {
                            Assert.assertTrue(ref.has("controller"), "creature controller named");
                            refs.add("card:" + ref.get("card_id").getAsInt());
                        } else {
                            refs.add("player:" + ref.get("player_id").getAsString());
                        }
                    }
                    Assert.assertEquals(refs.size(), 12, "12 distinct identities");
                } else {
                    Assert.assertEquals(f.options.size(), 11,
                            "the other target excludes the first (TargetUnique)");
                    Assert.assertTrue(f.options.stream().noneMatch(o -> o.label != null
                            && o.label.endsWith("[" + targets[0] + "]")), "first target excluded");
                }
                targetFrames++;
                submit(session, f, pick(f, o -> o.label != null && o.label.endsWith("[" + want + "]"),
                        want));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, BridgeTestSupport.equivalentPayment(f));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                cast = true;
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        return targetFrames;
    }

    @Test(timeOut = 300000)
    public void shockPicksOneOfTwelveTargetsOnAFourPlayerBoard() {
        final BridgeTestSupport.ConstructedGame constructed = board("mp-target-shock", "Shock");
        final BridgeSession session = constructed.session;
        Assert.assertEquals(cast(session, "Shock", 1, "Runeclaw Bear"), 1);
        Assert.assertNull(find(session, 1, "Runeclaw Bear", ZoneType.Battlefield),
                "p2's Runeclaw Bear died");
        Assert.assertNotNull(find(session, 1, "Grizzly Bears", ZoneType.Battlefield),
                "and nothing else was hit");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void arcTrailTargetsTwoDifferentObjectsAcrossPlayers() {
        final BridgeTestSupport.ConstructedGame constructed = board("mp-target-arc", "Arc Trail");
        final BridgeSession session = constructed.session;
        Assert.assertEquals(cast(session, "Arc Trail", 2, "Grizzly Bears", "Raging Goblin"), 2);
        Assert.assertNull(find(session, 1, "Grizzly Bears", ZoneType.Battlefield),
                "2 damage killed p2's Grizzly Bears");
        Assert.assertNull(find(session, 2, "Raging Goblin", ZoneType.Battlefield),
                "1 damage killed p3's Raging Goblin");
        Assert.assertNotNull(find(session, 2, "Craw Wurm", ZoneType.Battlefield), "Wurm untouched");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void redirectMovesShockToAnotherPlayersCreature() {
        final BridgeTestSupport.ConstructedGame constructed = board("mp-target-redirect", "Shock",
                "Redirect", "Island", "Island");
        final BridgeSession session = constructed.session;
        DecisionFrame frame = BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        for (String land : new String[] {"Mountain", "Island", "Island"}) {
            submit(session, frame, pick(frame, o -> "activate_ability".equals(o.actionType)
                    && land.equals(o.sourceCardName), land));
            frame = BridgeTestSupport.awaitFrame(session, 15000);
        }
        submit(session, frame, pick(frame, o -> "cast_spell".equals(o.actionType)
                && "Shock".equals(o.sourceCardName), "cast Shock"));
        boolean shockCast = false;
        boolean redirectCast = false;
        boolean retargeted = false;
        for (int i = 0; i < 60; i++) {
            if (retargeted && session.getGame().getStack().isEmpty()) {
                break;
            }
            final DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f, "no frame");
            Assert.assertEquals(f.status, DecisionFrame.Status.SUPPORTED,
                    "blocked: " + f.kind + " " + f.reason);
            if (f.kind == DecisionFrame.Kind.TARGET_SELECTION && !shockCast) {
                shockCast = true;
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.endsWith("[Grizzly Bears]"), "Shock the Bears"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && f.options.stream().anyMatch(o -> "target".equals(o.actionType))) {
                // Redirect's own target: the Shock on the stack.
                submit(session, f, pick(f, o -> o.label != null && o.label.contains("Shock"),
                        "target the Shock"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION
                    && f.options.stream().anyMatch(o -> "retarget".equals(o.actionType))) {
                submit(session, f, pick(f, o -> Boolean.TRUE.equals(o.confirmValue),
                        "choose new targets"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                Assert.assertTrue(f.options.size() >= 10, "large candidate set: "
                        + f.options.size());
                retargeted = true;
                submit(session, f, pick(f, o -> o.label != null
                        && o.label.endsWith("[Raging Goblin]"), "new target: the goblin"));
            } else if (f.kind == DecisionFrame.Kind.GENERIC_CONFIRM && redirectCast) {
                // "You may choose new targets": the pilot chooses to.
                submit(session, f, pick(f, o -> Boolean.TRUE.equals(o.confirmValue),
                        "yes, choose new targets"));
            } else if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, BridgeTestSupport.equivalentPayment(f));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY && f.actorPlayerId.equals("p1")
                    && shockCast && !redirectCast) {
                redirectCast = true;
                submit(session, f, pick(f, o -> "cast_spell".equals(o.actionType)
                        && "Redirect".equals(o.sourceCardName), "cast Redirect"));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pick(f, o -> o.isPass, "pass"));
            } else {
                throw new AssertionError("unexpected " + f.kind + " " + f.reason);
            }
        }
        Assert.assertTrue(retargeted, "the new target was the pilot's choice");
        Assert.assertNotNull(find(session, 1, "Grizzly Bears", ZoneType.Battlefield),
                "the original target survived");
        Assert.assertNull(find(session, 2, "Raging Goblin", ZoneType.Battlefield),
                "the redirected Shock killed p3's goblin");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void objectRefsNeverIdentifyCardsTheActorCannotSee() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("mp-refs-hidden", 4);
        final Card ownHand = BridgeTestSupport.addCard(constructed.game, 0, "Shock",
                ZoneType.Hand);
        final Card opponentHand = BridgeTestSupport.addCard(constructed.game, 2, "Craw Wurm",
                ZoneType.Hand);
        final Card opponentCreature = BridgeTestSupport.addCard(constructed.game, 2,
                "Grizzly Bears", ZoneType.Battlefield);
        final forge.game.player.Player p1 = constructed.game.getPlayers().get(0);
        final com.google.gson.JsonArray refs = StateProjection.objectRefs(constructed.session,
                java.util.List.of(ownHand, opponentHand, opponentCreature), p1);
        Assert.assertEquals(refs.size(), 3);
        Assert.assertEquals(refs.get(0).getAsJsonObject().get("name").getAsString(), "Shock",
                "the actor's own hand card is identified");
        final com.google.gson.JsonObject hidden = refs.get(1).getAsJsonObject();
        Assert.assertTrue(hidden.get("hidden").getAsBoolean(), "an opponent's hand card is hidden");
        Assert.assertFalse(hidden.has("name") || hidden.has("card_id"),
                "no name or id for a card p1 cannot see: " + hidden);
        Assert.assertEquals(refs.get(2).getAsJsonObject().get("controller").getAsString(), "p3",
                "a public permanent is identified with its controller");
        constructed.session.shutdown(5000);
    }
}
