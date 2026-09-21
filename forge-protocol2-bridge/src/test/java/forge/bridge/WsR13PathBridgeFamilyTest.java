package forge.bridge;

import forge.game.card.Card;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.function.Predicate;

/**
 * R13 PATH bridge family: Path of Ancestry (CARD_27) sharing-type scry
 * through the spent-mana recording fix.
 *
 * <p>R10c proved the mechanism via sim; bridge pool payments discarded
 * spent mana (payingMana empty) so TriggersWhenSpent never fired. With
 * recording restored (R13 production fix mirroring the AI path), three
 * strict bridge tests: scry fires on shared-type Path spend (framed
 * choice answered), stays silent for non-sharing types and non-Path
 * mana. Tests pick only engine-offered options.</p>
 */
public class WsR13PathBridgeFamilyTest {

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
        if (!outcome.applied) {
            System.out.println("R13FAIL kind=" + frame.kind + " actor=" + frame.actorPlayerId
                    + " status=" + frame.status + " reason=" + frame.reason
                    + " failReason=" + session.getFailReason());
        }
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
        } else if (frame.kind == DecisionFrame.Kind.COLOR_CHOICE) {
            submit(session, frame, pickOption(frame,
                    o -> o.label != null && o.label.contains("red"),
                    "red mana"));
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

    private static int zoneCount(BridgeSession session, int seat, ZoneType zone) {
        return session.getGame().getPlayers().get(seat).getZone(zone).getCards().size();
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

    @Test(timeOut = 300000)
    public void testPathScryOnSharedType() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr13-path-scry");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Gishath, Sun's Avatar");
        BridgeTestSupport.addCard(constructed.game, 0, "Path of Ancestry", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Raptor Hatchling", ZoneType.Hand);
        fillLibraries(constructed, 10);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);
        int libBefore = zoneCount(session, 0, ZoneType.Library);
        int handBefore = zoneCount(session, 0, ZoneType.Hand);

        // Pre-float Path mana (red), then cast Hatchling on pool + Mountain.
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "activate_ability".equals(o.actionType)
                        && "Path of Ancestry".equals(o.sourceCardName),
                "Path tap"));
        boolean scrySeen = false;
        for (int i = 0; i < 60; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasHatchling = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Raptor Hatchling".equals(o.sourceCardName)) {
                        hasHatchling = true;
                    }
                }
                if (hasHatchling) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Raptor Hatchling".equals(o.sourceCardName),
                            "Hatchling cast"));
                    break;
                }
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.COLOR_CHOICE) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("red"),
                        "red mana"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                scrySeen = true;
                submit(session, f, f.options.get(0));
            }
            if (findBf(session, 0, "Raptor Hatchling") != null && scrySeen) {
                break;
            }
        }
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Raptor Hatchling"), "Hatchling must resolve");
        Assert.assertTrue(scrySeen, "scry decision must be offered for shared type");
        Assert.assertEquals(zoneCount(session, 0, ZoneType.Library), libBefore,
                "library count unchanged by scry-1");
        Assert.assertEquals(zoneCount(session, 0, ZoneType.Hand), handBefore - 1,
                "hand down exactly the cast card");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testPathNoScryOnDifferentType() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr13-path-noscry");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Gishath, Sun's Avatar");
        BridgeTestSupport.addCard(constructed.game, 0, "Path of Ancestry", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Forest", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Runeclaw Bear", ZoneType.Hand);
        fillLibraries(constructed, 10);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "activate_ability".equals(o.actionType)
                        && "Path of Ancestry".equals(o.sourceCardName),
                "Path tap"));
        boolean scrySeen = false;
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.actorPlayerId.equals("p1") && f.kind == DecisionFrame.Kind.PRIORITY
                    && f.status == DecisionFrame.Status.SUPPORTED) {
                boolean hasBear = false;
                for (DecisionFrame.Option o : f.options) {
                    if ("cast_spell".equals(o.actionType)
                            && "Runeclaw Bear".equals(o.sourceCardName)) {
                        hasBear = true;
                    }
                }
                if (hasBear) {
                    submit(session, f, pickOption(f,
                            o -> "cast_spell".equals(o.actionType)
                                    && "Runeclaw Bear".equals(o.sourceCardName),
                            "Bear cast"));
                    break;
                }
            }
            answerCommon(session, f);
        }
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.COLOR_CHOICE) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("red")
                                || o.label.contains("green")),
                        "path color"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                scrySeen = true;
                submit(session, f, f.options.get(0));
            }
            if (findBf(session, 0, "Runeclaw Bear") != null && i > 10) {
                break;
            }
        }
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Runeclaw Bear"), "Bear must resolve");
        Assert.assertFalse(scrySeen, "no scry decision for non-sharing type");
        session.shutdown(5000);
    }

    @Test(timeOut = 300000)
    public void testPathNoScryOnOtherMana() {
        final BridgeTestSupport.ConstructedGame constructed =
                BridgeTestSupport.buildConstructedGame("wsr13-path-othermana");
        final BridgeSession session = constructed.session;
        BridgeTestSupport.addCommander(constructed.game, 0, "Gishath, Sun's Avatar");
        BridgeTestSupport.addCard(constructed.game, 0, "Path of Ancestry", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Mountain", ZoneType.Battlefield);
        BridgeTestSupport.addCard(constructed.game, 0, "Raptor Hatchling", ZoneType.Hand);
        fillLibraries(constructed, 10);
        BridgeTestSupport.launchConstructed(constructed);
        BridgeTestSupport.drivePassesToMainPhase(session, "p1", 12);

        // Pay with Mountain mana only (Path stays untapped): no scry.
        DecisionFrame frame = driveTo(session, "p1", DecisionFrame.Kind.PRIORITY, 30);
        submit(session, frame, pickOption(frame,
                o -> "cast_spell".equals(o.actionType)
                        && "Raptor Hatchling".equals(o.sourceCardName),
                "Hatchling cast"));
        boolean scrySeen = false;
        for (int i = 0; i < 40; i++) {
            DecisionFrame f = BridgeTestSupport.awaitFrame(session, 15000);
            Assert.assertNotNull(f);
            if (f.kind == DecisionFrame.Kind.MANA_PAYMENT) {
                submit(session, f, pickOption(f,
                        o -> o.sourceCardName != null && o.sourceCardName.contains("Mountain"),
                        "Mountain mana"));
            } else if (f.kind == DecisionFrame.Kind.COLOR_CHOICE) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && o.label.contains("red"),
                        "red mana"));
            } else if (f.kind == DecisionFrame.Kind.TARGET_SELECTION) {
                submit(session, f, f.options.get(0));
            } else if (f.kind == DecisionFrame.Kind.PRIORITY) {
                submit(session, f, pickOption(f, o -> o.isPass, "pass"));
            } else if (f.kind == DecisionFrame.Kind.COMBAT_DECLARE_ATTACKERS
                    || f.kind == DecisionFrame.Kind.COMBAT_DECLARE_BLOCKERS) {
                submit(session, f, pickOption(f,
                        o -> o.label != null && (o.label.contains("No attack")
                                || o.label.contains("No block")),
                        "decline combat"));
            } else {
                scrySeen = true;
                submit(session, f, f.options.get(0));
            }
            if (findBf(session, 0, "Raptor Hatchling") != null && i > 10) {
                break;
            }
        }
        drain(session, 20);
        Assert.assertNotNull(findBf(session, 0, "Raptor Hatchling"), "Hatchling must resolve");
        Assert.assertFalse(scrySeen, "no scry decision without Path mana");
        session.shutdown(5000);
    }
}
