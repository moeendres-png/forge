package forge.gamesimulationtests.ws63;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.SimulationTest;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * WS63 A04 — natural full-cast remediation (CR 107.3m).
 *
 * <p>Drives Stonecoil Serpent (and generic/base variants) through Forge's real
 * casting pipeline: X is chosen via the native {@code announceRequirements}
 * decision, mana is paid through the normal cost path by tapping lands, the
 * spell is put on the stack by the pipeline, and resolution goes through the
 * real stack resolution into {@code moveToPlay}. Nothing here calls
 * {@code GameAction.moveToStack}/{@code moveToPlay} for the cast creature,
 * {@code setXManaCostPaid} directly, injects counters, or synthesizes
 * replacement outcomes. Doubling Season / Hardened Scales are placed as
 * fixture setup only.
 */
public class Ws63A04NaturalFullCastTest extends SimulationTest {

    /**
     * Production AI controller with exactly two test-owned behaviors: answer
     * the engine's native X announcement with a fixed legal value, and observe
     * (never decide for) replacement ordering. All payment, targeting, and
     * replacement decisions stay in production AI/engine code.
     */
    public static class FixedXController extends PlayerControllerAi {
        private final int fixedX;
        int announceCalls = 0;
        int announcedX = -1;
        int announceMin = -1;
        int announceMax = -1;
        int replacementCalls = 0;
        int lastReplacerCount = 0;
        int maxReplacerCount = 0;
        final List<String> lastReplacerHosts = new ArrayList<>();

        public FixedXController(Game game, Player player, LobbyPlayer lobbyPlayer, int fixedX) {
            super(game, player, lobbyPlayer);
            this.fixedX = fixedX;
        }

        @Override
        public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
            if ("X".equalsIgnoreCase(announce)) {
                announceCalls++;
                announceMin = min;
                announceMax = max;
                // Fail closed: only answer inside the engine-offered range.
                if (fixedX < min || fixedX > max) {
                    return null;
                }
                announcedX = fixedX;
                return fixedX;
            }
            return super.announceRequirements(ability, min, max, announce);
        }

        @Override
        public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
            replacementCalls++;
            lastReplacerCount = possibleReplacers.size();
            maxReplacerCount = Math.max(maxReplacerCount, possibleReplacers.size());
            lastReplacerHosts.clear();
            for (ReplacementEffect re : possibleReplacers) {
                lastReplacerHosts.add(re.getHostCard() == null ? "<null>" : re.getHostCard().getName());
            }
            // Engine-owned ordering decision stays in production AI code.
            return super.chooseSingleReplacementEffect(possibleReplacers);
        }
    }

    private Card addBattlefield(Player p, String name) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Battlefield).add(c);
        p.getGame().getAction().checkStaticAbilities();
        return c;
    }

    private Card addHand(Player p, String name) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Hand).add(c);
        return c;
    }

    private int countTappedForests(Player p) {
        int n = 0;
        for (Card c : p.getZone(ZoneType.Battlefield).getCards()) {
            if ("Forest".equals(c.getName()) && c.isTapped()) {
                n++;
            }
        }
        return n;
    }

    private Card findOnBattlefield(Player p, String name) {
        for (Card c : p.getZone(ZoneType.Battlefield).getCards()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private void resolveStackFully(Game game) {
        int guard = 0;
        while (!game.getStack().isEmpty() && guard++ < 25) {
            game.getStack().resolveStack();
        }
        assertTrue(game.getStack().isEmpty(), "stack must fully resolve");
    }

    private void printLineage(String tag, FixedXController ctrl, Card handCard, SpellAbility stackSA) {
        Card stackCard = stackSA == null ? null : stackSA.getHostCard();
        SpellAbility castSA = stackCard == null ? null : stackCard.getCastSA();
        System.out.println("WS63-LINEAGE " + tag
                + " handCardId=" + (handCard == null ? -1 : handCard.getId())
                + " stackSAid=" + (stackSA == null ? -1 : stackSA.getId())
                + " stackSA_X=" + (stackSA == null ? "<null>" : stackSA.getXManaCostPaid())
                + " stackCardId=" + (stackCard == null ? -1 : stackCard.getId())
                + " stackCard==handCard=" + (stackCard == handCard)
                + " saHost==stackCard=" + (stackSA != null && stackCard != null && stackSA.getHostCard() == stackCard)
                + " castSAid=" + (castSA == null ? -1 : castSA.getId())
                + " castSA_X=" + (castSA == null ? "<null>" : castSA.getXManaCostPaid())
                + " castSA==stackSA=" + (castSA == stackSA)
                + " cardXPaid=" + (stackCard == null ? "<null>" : stackCard.getXManaCostPaid())
                + " announceCalls=" + ctrl.announceCalls
                + " announcedX=" + ctrl.announcedX + "[" + ctrl.announceMin + ".." + ctrl.announceMax + "]"
                + " replCalls=" + ctrl.replacementCalls
                + " replacers=" + ctrl.lastReplacerCount + ctrl.lastReplacerHosts);
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastSerpentX3WithDoublingSeasonHardenedScales() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl = new FixedXController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl);

        addBattlefield(p1, "Doubling Season");
        addBattlefield(p1, "Hardened Scales");
        for (int i = 0; i < 6; i++) {
            addBattlefield(p1, "Forest");
        }
        Card serpentHand = addHand(p1, "Stonecoil Serpent");

        SpellAbility castSA = serpentHand.getFirstSpellAbility();
        assertNotNull(castSA, "Serpent must have a spell ability");
        castSA.setActivatingPlayer(p1);
        int tappedBefore = countTappedForests(p1);

        // Native casting pipeline: announce X, pay costs, stack the spell.
        boolean castOk = new PlaySpellAbility(ctrl, castSA).playAbility(true, false, false);
        assertTrue(castOk, "native cast through PlaySpellAbility must succeed");

        // Casting authority: X=3 chosen via the native announce decision.
        assertEquals(ctrl.announceCalls, 1, "X must be announced exactly once via native decision");
        assertEquals(ctrl.announcedX, 3, "announced X must be 3");

        // Native payment: exactly the X=3 cost tapped from lands.
        assertEquals(countTappedForests(p1) - tappedBefore, 3,
                "native payment for X=3 must tap exactly 3 Forests");

        // Spell must be on the stack with resolving identities recorded.
        assertFalse(game.getStack().isEmpty(), "spell must reach the stack");
        SpellAbility stackSA = game.getStack().peekAbility();
        assertEquals(stackSA.getHostCard().getName(), "Stonecoil Serpent");
        printLineage("PRE_RESOLVE", ctrl, serpentHand, stackSA);

        // Native resolution through the real stack.
        resolveStackFully(game);
        printLineage("POST_RESOLVE", ctrl, serpentHand, stackSA);

        // The engine-owned ETB replacement path must actually be reached with
        // both replacements contesting.
        assertTrue(ctrl.replacementCalls >= 1,
                "engine must reach chooseSingleReplacementEffect (was 0 in WS62 defect)");
        assertTrue(ctrl.maxReplacerCount >= 2,
                "both Doubling Season and Hardened Scales must contest ordering, saw "
                        + ctrl.lastReplacerHosts);

        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "Serpent must remain on the battlefield, not die 0/0");
        int counters = battlefield.getCounters(CounterEnumType.P1P1);
        assertTrue(counters == 7 || counters == 8,
                "Expected 7 or 8 +1/+1 counters via engine replacement ordering, got " + counters);
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastStaticEntrySerpentX3() {
        // Same exact fixture but through the full static Human/provider entry
        // (chooseOptionalAdditionalCosts + playAbility), the route a
        // controller-driven cast takes.
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl = new FixedXController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl);

        addBattlefield(p1, "Doubling Season");
        addBattlefield(p1, "Hardened Scales");
        for (int i = 0; i < 6; i++) {
            addBattlefield(p1, "Forest");
        }
        Card serpentHand = addHand(p1, "Stonecoil Serpent");

        SpellAbility castSA = serpentHand.getFirstSpellAbility();
        assertNotNull(castSA, "Serpent must have a spell ability");

        assertTrue(PlaySpellAbility.playSpellAbility(ctrl, p1, castSA),
                "native static-entry cast must succeed");
        assertEquals(ctrl.announcedX, 3);
        assertFalse(game.getStack().isEmpty());
        printLineage("STATIC_PRE", ctrl, serpentHand, game.getStack().peekAbility());
        resolveStackFully(game);

        assertTrue(ctrl.maxReplacerCount >= 2,
                "both replacements must contest, saw " + ctrl.lastReplacerHosts);
        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "Serpent must remain on the battlefield, not die 0/0");
        int counters = battlefield.getCounters(CounterEnumType.P1P1);
        assertTrue(counters == 7 || counters == 8,
                "Expected 7 or 8 +1/+1 counters via engine replacement ordering, got " + counters);
    }

    @Test(timeOut = 180000)
    public void testNaturalFullCastViaPriorityLoop() {
        // Most faithful in-repo replication of the provider run: a real game
        // is started and the cast is issued through the priority loop via
        // chooseSpellAbilityToPlay + playChosenSpellAbility (the exact seam a
        // controller-driven cast uses), with stack resolution, state-based
        // actions, and phase flow owned by the engine. P2's empty library ends
        // the game deterministically after the main-1 cast resolves.
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);

        addBattlefield(p1, "Doubling Season");
        addBattlefield(p1, "Hardened Scales");
        for (int i = 0; i < 6; i++) {
            addBattlefield(p1, "Forest");
        }
        for (int i = 0; i < 30; i++) {
            Card lib = createCard("Forest", p1);
            lib.setGameTimestamp(game.getNextTimestamp());
            p1.getZone(ZoneType.Library).add(lib);
        }
        Card serpentHand = addHand(p1, "Stonecoil Serpent");

        LoopController ctrl = new LoopController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl);
        SpellAbility castSA = serpentHand.getFirstSpellAbility();
        assertNotNull(castSA);
        castSA.setActivatingPlayer(p1);
        ctrl.offerOnce(castSA);
        int tappedBefore = countTappedForests(p1);

        game.getPhaseHandler().startFirstTurn(p1);
        assertTrue(game.isGameOver(), "P2 must deck out and end the game");

        System.out.println("WS63-LOOP announcedX=" + ctrl.announcedX
                + " announceCalls=" + ctrl.announceCalls
                + " tappedDelta=" + (countTappedForests(p1) - tappedBefore)
                + " replCalls=" + ctrl.replacementCalls
                + " maxReplacers=" + ctrl.maxReplacerCount);
        assertEquals(ctrl.announcedX, 3, "X=3 must be announced via native decision");
        assertTrue(ctrl.replacementCalls >= 1, "engine must reach replacement ordering");
        assertTrue(ctrl.maxReplacerCount >= 2,
                "both replacements must contest, saw " + ctrl.lastReplacerHosts);
        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "Serpent must remain on the battlefield, not die 0/0");
        int counters = battlefield.getCounters(CounterEnumType.P1P1);
        assertTrue(counters == 7 || counters == 8,
                "Expected 7 or 8 +1/+1 counters via engine replacement ordering, got " + counters);
    }

    /**
     * Controller-driven loop participant: offers one scripted spell choice,
     * plays it through the full native static entry, answers X natively.
     */
    public static class LoopController extends FixedXController {
        private SpellAbility pendingOffer = null;

        public LoopController(Game game, Player player, LobbyPlayer lobbyPlayer, int fixedX) {
            super(game, player, lobbyPlayer, fixedX);
        }

        public void offerOnce(SpellAbility sa) {
            pendingOffer = sa;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (pendingOffer != null
                    && getGame().getPhaseHandler().isPlayerTurn(getPlayer())
                    && getGame().getPhaseHandler().getPhase() == PhaseType.MAIN1
                    && getGame().getStack().isEmpty()) {
                SpellAbility sa = pendingOffer;
                pendingOffer = null;
                return java.util.Collections.singletonList(sa);
            }
            return null;
        }

        @Override
        public boolean playChosenSpellAbility(SpellAbility sa) {
            return PlaySpellAbility.playSpellAbility(this, getPlayer(), sa);
        }
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastPoolPayment() {
        // Human/provider payment shape: float 3 G into the pool first via
        // native mana abilities, then cast and pay from the pool.
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl = new FixedXController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl);

        addBattlefield(p1, "Doubling Season");
        addBattlefield(p1, "Hardened Scales");
        for (int i = 0; i < 6; i++) {
            addBattlefield(p1, "Forest");
        }
        // Native pre-float: activate three Forests' mana abilities.
        int floated = 0;
        for (Card c : p1.getZone(ZoneType.Battlefield).getCards()) {
            if (floated >= 3) {
                break;
            }
            if ("Forest".equals(c.getName()) && !c.isTapped()) {
                for (SpellAbility ma : c.getManaAbilities()) {
                    ma.setActivatingPlayer(p1);
                    if (ma.canPlay()) {
                        AbilityUtils.resolve(ma);
                        floated++;
                        break;
                    }
                }
            }
        }
        assertEquals(floated, 3, "must float 3 G natively");
        assertEquals(p1.getManaPool().getAmountOfColor(MagicColor.GREEN), 3);

        Card serpentHand = addHand(p1, "Stonecoil Serpent");
        SpellAbility castSA = serpentHand.getFirstSpellAbility();
        assertNotNull(castSA);
        castSA.setActivatingPlayer(p1);

        assertTrue(new PlaySpellAbility(ctrl, castSA).playAbility(true, false, false));
        assertEquals(ctrl.announcedX, 3);
        assertTrue(p1.getManaPool().isEmpty(), "pool must be consumed by native payment");
        resolveStackFully(game);

        assertTrue(ctrl.maxReplacerCount >= 2,
                "both replacements must contest, saw " + ctrl.lastReplacerHosts);
        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "Serpent must remain on the battlefield, not die 0/0");
        int counters = battlefield.getCounters(CounterEnumType.P1P1);
        assertTrue(counters == 7 || counters == 8,
                "Expected 7 or 8 +1/+1 counters via engine replacement ordering, got " + counters);
    }

    private Game initFourPlayerGame() {
        java.util.List<forge.game.player.RegisteredPlayer> players = new java.util.ArrayList<>();
        java.util.Set<AIOption> options = new java.util.HashSet<>();
        options.add(AIOption.USE_FULL_SIMULATION);
        for (int i = 0; i < 4; i++) {
            players.add(new forge.game.player.RegisteredPlayer(new Deck())
                    .setPlayer(new LobbyPlayerAi("p" + (i + 1), options)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        return game;
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastFourPlayer() {
        // Multiplayer fidelity probe: same exact fixture in a 4-player game.
        Game game = initFourPlayerGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl = new FixedXController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl);

        addBattlefield(p1, "Doubling Season");
        addBattlefield(p1, "Hardened Scales");
        for (int i = 0; i < 6; i++) {
            addBattlefield(p1, "Forest");
        }
        Card serpentHand = addHand(p1, "Stonecoil Serpent");

        SpellAbility castSA = serpentHand.getFirstSpellAbility();
        assertNotNull(castSA);
        castSA.setActivatingPlayer(p1);

        assertTrue(PlaySpellAbility.playSpellAbility(ctrl, p1, castSA));
        assertEquals(ctrl.announcedX, 3);
        resolveStackFully(game);

        assertTrue(ctrl.maxReplacerCount >= 2,
                "both replacements must contest, saw " + ctrl.lastReplacerHosts);
        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "Serpent must remain on the battlefield, not die 0/0");
        int counters = battlefield.getCounters(CounterEnumType.P1P1);
        assertTrue(counters == 7 || counters == 8,
                "Expected 7 or 8 +1/+1 counters via engine replacement ordering, got " + counters);
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastRichHistory() {
        // Rich-history fidelity probe: doublers resolved from the stack,
        // Serpent drawn from the library, priority rounds before resolution.
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl1 = new FixedXController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl1);

        for (int i = 0; i < 14; i++) {
            addBattlefield(p1, "Forest");
        }
        // Library with a Serpent on top, then draw it naturally.
        Card deckSerpent = createCard("Stonecoil Serpent", p1);
        deckSerpent.setGameTimestamp(game.getNextTimestamp());
        p1.getZone(ZoneType.Library).add(deckSerpent);
        Card drawn = p1.drawCard().getFirst();
        assertEquals(drawn.getName(), "Stonecoil Serpent");

        // Resolve the doublers from the stack first (cast history, not dev-placed).
        SpellAbility dsSA = addHand(p1, "Doubling Season").getFirstSpellAbility();
        dsSA.setActivatingPlayer(p1);
        assertTrue(new PlaySpellAbility(ctrl1, dsSA).playAbility(true, false, false));
        resolveStackFully(game);
        game.getAction().checkStateEffects(true);
        SpellAbility hsSA = addHand(p1, "Hardened Scales").getFirstSpellAbility();
        hsSA.setActivatingPlayer(p1);
        assertTrue(new PlaySpellAbility(ctrl1, hsSA).playAbility(true, false, false));
        resolveStackFully(game);
        game.getAction().checkStateEffects(true);
        assertNotNull(findOnBattlefield(p1, "Doubling Season"));
        assertNotNull(findOnBattlefield(p1, "Hardened Scales"));

        // Untap everything for the Serpent cast (fresh-turn equivalent).
        for (Card c : p1.getZone(ZoneType.Battlefield).getCards()) {
            c.setTapped(false);
        }

        SpellAbility castSA = drawn.getFirstSpellAbility();
        assertNotNull(castSA);
        castSA.setActivatingPlayer(p1);
        int tappedBefore = countTappedForests(p1);
        assertTrue(new PlaySpellAbility(ctrl1, castSA).playAbility(true, false, false));
        assertEquals(ctrl1.announcedX, 3);
        assertEquals(countTappedForests(p1) - tappedBefore, 3);
        assertFalse(game.getStack().isEmpty());
        printLineage("RICH_PRE", ctrl1, drawn, game.getStack().peekAbility());

        for (int i = 0; i < 4; i++) {
            game.getAction().checkStateEffects(false);
            game.copyLastState();
        }
        resolveStackFully(game);
        game.getAction().checkStateEffects(true);

        System.out.println("WS63-RICH replCalls=" + ctrl1.replacementCalls
                + " maxReplacers=" + ctrl1.maxReplacerCount);
        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "Serpent must remain on the battlefield, not die 0/0");
        int counters = battlefield.getCounters(CounterEnumType.P1P1);
        assertTrue(counters == 7 || counters == 8,
                "Expected 7 or 8 +1/+1 counters via engine replacement ordering, got " + counters);
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastWithPriorityRoundsBeforeResolve() {
        // Fidelity probe: full games pass priority (SBAs, last-state copies)
        // between cast and resolution; replicate that gap before resolving.
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl = new FixedXController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl);

        addBattlefield(p1, "Doubling Season");
        addBattlefield(p1, "Hardened Scales");
        for (int i = 0; i < 6; i++) {
            addBattlefield(p1, "Forest");
        }
        Card serpentHand = addHand(p1, "Stonecoil Serpent");

        SpellAbility castSA = serpentHand.getFirstSpellAbility();
        assertNotNull(castSA);
        castSA.setActivatingPlayer(p1);

        assertTrue(new PlaySpellAbility(ctrl, castSA).playAbility(true, false, false));
        assertEquals(ctrl.announcedX, 3);
        assertFalse(game.getStack().isEmpty());

        // Priority-round gap: state-based checks + last-state copies, as the
        // real priority loop performs before the spell resolves.
        for (int i = 0; i < 4; i++) {
            game.getAction().checkStateEffects(false);
            game.copyLastState();
        }
        printLineage("PREROUND_PRE", ctrl, serpentHand, game.getStack().peekAbility());
        resolveStackFully(game);
        game.getAction().checkStateEffects(true);

        System.out.println("WS63-ROUNDS replCalls=" + ctrl.replacementCalls
                + " maxReplacers=" + ctrl.maxReplacerCount);
        assertTrue(ctrl.maxReplacerCount >= 2,
                "both replacements must contest, saw " + ctrl.lastReplacerHosts);
        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "Serpent must remain on the battlefield, not die 0/0");
        int counters = battlefield.getCounters(CounterEnumType.P1P1);
        assertTrue(counters == 7 || counters == 8,
                "Expected 7 or 8 +1/+1 counters via engine replacement ordering, got " + counters);
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastMultiDoublerBoard() {
        // Full-game fidelity probe: the WS62 board held several doublers.
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl = new FixedXController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl);

        addBattlefield(p1, "Doubling Season");
        addBattlefield(p1, "Doubling Season");
        addBattlefield(p1, "Hardened Scales");
        addBattlefield(p1, "Hardened Scales");
        for (int i = 0; i < 10; i++) {
            addBattlefield(p1, "Forest");
        }
        Card serpentHand = addHand(p1, "Stonecoil Serpent");

        SpellAbility castSA = serpentHand.getFirstSpellAbility();
        assertNotNull(castSA);
        castSA.setActivatingPlayer(p1);

        assertTrue(new PlaySpellAbility(ctrl, castSA).playAbility(true, false, false));
        assertEquals(ctrl.announcedX, 3);
        resolveStackFully(game);
        System.out.println("WS63-MULTI replCalls=" + ctrl.replacementCalls
                + " maxReplacers=" + ctrl.maxReplacerCount);

        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "Serpent must remain on the battlefield, not die 0/0");
        System.out.println("WS63-MULTI counters=" + battlefield.getCounters(CounterEnumType.P1P1));
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastBaseSerpentX3WithoutReplacements() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl = new FixedXController(game, p1, p1.getLobbyPlayer(), 3);
        p1.dangerouslySetController(ctrl);

        for (int i = 0; i < 6; i++) {
            addBattlefield(p1, "Forest");
        }
        Card serpentHand = addHand(p1, "Stonecoil Serpent");

        SpellAbility castSA = serpentHand.getFirstSpellAbility();
        assertNotNull(castSA);
        castSA.setActivatingPlayer(p1);

        assertTrue(new PlaySpellAbility(ctrl, castSA).playAbility(true, false, false));
        assertEquals(ctrl.announcedX, 3);
        resolveStackFully(game);

        Card battlefield = findOnBattlefield(p1, "Stonecoil Serpent");
        assertNotNull(battlefield, "base X=3 Serpent must enter the battlefield");
        assertEquals(battlefield.getCounters(CounterEnumType.P1P1), 3,
                "base X=3 must materialize as exactly 3 without replacements");
    }

    @Test(timeOut = 120000)
    public void testNaturalFullCastGenericWalkerX2() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);
        FixedXController ctrl = new FixedXController(game, p1, p1.getLobbyPlayer(), 2);
        p1.dangerouslySetController(ctrl);

        addBattlefield(p1, "Corpsejack Menace");
        addBattlefield(p1, "Winding Constrictor");
        for (int i = 0; i < 6; i++) {
            addBattlefield(p1, "Forest");
        }
        Card walkerHand = addHand(p1, "Hangarback Walker");

        SpellAbility castSA = walkerHand.getFirstSpellAbility();
        assertNotNull(castSA, "Walker must have a spell ability");
        castSA.setActivatingPlayer(p1);

        assertTrue(new PlaySpellAbility(ctrl, castSA).playAbility(true, false, false));
        assertEquals(ctrl.announcedX, 2);
        assertFalse(game.getStack().isEmpty());
        resolveStackFully(game);

        assertTrue(ctrl.replacementCalls >= 1, "generic path must reach replacement ordering");
        Card battlefield = findOnBattlefield(p1, "Hangarback Walker");
        assertNotNull(battlefield, "Walker must enter the battlefield");
        assertTrue(battlefield.getCounters(CounterEnumType.P1P1) > 2,
                "generic doubling/+1 ordering must increase base 2, got "
                        + battlefield.getCounters(CounterEnumType.P1P1));
    }

    @Test(timeOut = 120000)
    public void testNegativeNonCastEntryHasZeroX() {
        // Fail-closed negative: a Serpent put onto the battlefield with no cast
        // lineage must see X=0 (dies as 0/0), proving the fix does not leak the
        // cast value onto the permanent outside the CR 107.3m ETB context.
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        Card serpentHand = addHand(p1, "Stonecoil Serpent");
        Card entered = game.getAction().moveToPlay(serpentHand, (SpellAbility) null, null);

        assertEquals(entered.getCounters(CounterEnumType.P1P1), 0,
                "non-cast entry must materialize zero counters");
        // Real state-based actions send the 0/0 to the graveyard.
        game.getAction().checkStateEffects(true);
        assertTrue(entered.isInZone(ZoneType.Graveyard),
                "non-cast 0/0 Serpent must go to the graveyard, not the battlefield");
    }
}
