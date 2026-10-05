package forge.gamesimulationtests;

import forge.game.Game;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.GameWrapper;
import forge.gamesimulationtests.util.card.CardSpecificationBuilder;
import forge.gamesimulationtests.util.card.CardSpecificationHandler;
import forge.gamesimulationtests.util.gamestate.GameStateSpecificationBuilder;
import forge.gamesimulationtests.util.player.PlayerSpecification;
import forge.gamesimulationtests.util.player.PlayerSpecificationHandler;
import forge.gamesimulationtests.util.playeractions.ActivateAbilityAction;
import forge.gamesimulationtests.util.playeractions.ChooseZoneCardAction;
import forge.gamesimulationtests.util.playeractions.PlayerActions;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import forge.localinstance.properties.ForgeConstants;
import forge.util.Localizer;

/** Actual-card controls for the historical disabled CR104 fixture.
 * They do not claim an isolated simultaneous win/loss obligation.
 */
public class LichDamageReplacementTest extends BaseGameSimulationTest {
    @BeforeMethod
    public void initializeParameterizedGamePrompts() {
        // Only these Lich scenarios require real parameterized zone prompts.
        // Preserve the other fixtures' existing localization contract.
        setMock(null);
        Localizer.getInstance().initialize("en-US", ForgeConstants.LANG_DIR);
    }

    private GameStateSpecificationBuilder position(boolean mana, boolean payableDamageReplacement) {
        GameStateSpecificationBuilder state = new GameStateSpecificationBuilder()
                .addCard(new CardSpecificationBuilder("Laboratory Maniac").controller(PlayerSpecification.PLAYER_1).battlefield())
                .addCard(new CardSpecificationBuilder("Nefarious Lich").controller(PlayerSpecification.PLAYER_1).battlefield())
                .addCard(new CardSpecificationBuilder("Thrashing Wumpus").controller(PlayerSpecification.PLAYER_1).battlefield())
                .addCard(new CardSpecificationBuilder("Lifelink").controller(PlayerSpecification.PLAYER_1).battlefield()
                        .target(new CardSpecificationBuilder("Thrashing Wumpus").build()));
        if (mana) {
            state.addCard(new CardSpecificationBuilder("Swamp").controller(PlayerSpecification.PLAYER_1).battlefield());
        }
        if (payableDamageReplacement) {
            state.addCard(new CardSpecificationBuilder("Forest").owner(PlayerSpecification.PLAYER_1).graveyard());
        }
        return state;
    }

    @Test
    public void activationWithoutManaIsRejected() {
        GameWrapper game = new GameWrapper(position(false, false).build(), new PlayerActions(
                new ActivateAbilityAction(PlayerSpecification.PLAYER_1,
                        new CardSpecificationBuilder("Thrashing Wumpus").build())));
        IllegalStateException error = Assert.expectThrows(IllegalStateException.class, game::runGame);
        // The ability exists and is legal to activate; only its cost cannot be paid.
        Assert.assertTrue(error.getMessage().startsWith("Scripted activation costs were not paid"),
                error.getMessage());
        Assert.assertFalse(game.getGame().isGameOver(), "denial is not an injected terminal outcome");
    }

    @Test
    public void unpayableDamageReplacementLosesBeforeDraw() {
        GameWrapper game = playAndCheckMana(false, PlayerSpecification.PLAYER_2);
        PlayerOutcome lich = outcome(game, PlayerSpecification.PLAYER_1);
        // The loss is Nefarious Lich's own "if you can't, you lose the game", not a
        // draw from an empty library or any other route.
        Assert.assertEquals(lich.lossState, GameLossReason.SpellEffect);
        Assert.assertEquals(lich.loseConditionSpell, "Nefarious Lich");
        Assert.assertNull(lich.altWinSourceName, "the Laboratory Maniac replacement never applied");
    }

    @Test
    public void payableDamageReplacementAllowsManiacWin() {
        int[] lifeBefore = {Integer.MIN_VALUE};
        GameWrapper game = playAndCheckMana(true, PlayerSpecification.PLAYER_1, lifeBefore);
        PlayerOutcome lich = outcome(game, PlayerSpecification.PLAYER_1);
        Assert.assertEquals(lich.altWinSourceName, "Laboratory Maniac");
        Player player1 = PlayerSpecificationHandler.INSTANCE.find(game.getGame(), PlayerSpecification.PLAYER_1);
        // The damage was replaced by exiling the declared Forest, and the lifelink
        // gain by the draw: the controller's life never moved.
        Assert.assertEquals(player1.getLife(), lifeBefore[0], "damage and life gain were both replaced");
        Assert.assertNotNull(CardSpecificationHandler.INSTANCE.find(game.getGame(),
                new CardSpecificationBuilder("Forest").owner(PlayerSpecification.PLAYER_1).zone(ZoneType.Exile).build()),
                "the scripted Forest is the card the replacement exiled");
    }

    @Test
    public void absentScriptedZoneCardIsRejected() {
        assertZoneChoiceRejected(new ChooseZoneCardAction(PlayerSpecification.PLAYER_1,
                new CardSpecificationBuilder("Plains").owner(PlayerSpecification.PLAYER_1).graveyard().build(),
                ZoneType.Graveyard, ZoneType.Exile));
    }

    @Test
    public void mismatchedScriptedZoneRouteIsRejected() {
        assertZoneChoiceRejected(new ChooseZoneCardAction(PlayerSpecification.PLAYER_1,
                new CardSpecificationBuilder("Forest").owner(PlayerSpecification.PLAYER_1).graveyard().build(),
                ZoneType.Graveyard, ZoneType.Hand));
    }

    private void assertZoneChoiceRejected(ChooseZoneCardAction choice) {
        GameWrapper game = new GameWrapper(position(true, true).build(), new PlayerActions(
                new ActivateAbilityAction(PlayerSpecification.PLAYER_1,
                        new CardSpecificationBuilder("Thrashing Wumpus").build()), choice));
        Assert.expectThrows(RuntimeException.class, game::runGame);
        Assert.assertFalse(game.getGame().isGameOver(),
                "a scripted choice the engine did not offer cannot select another card or end the game");
    }

    private static PlayerOutcome outcome(GameWrapper game, PlayerSpecification player) {
        return PlayerSpecificationHandler.INSTANCE.find(game.getGame(), player).getOutcome();
    }

    @Test
    public void missingZoneChoiceIsRejected() {
        GameWrapper game = new GameWrapper(position(true, true).build(), new PlayerActions(
                new ActivateAbilityAction(PlayerSpecification.PLAYER_1,
                        new CardSpecificationBuilder("Thrashing Wumpus").build())));
        IllegalStateException error = Assert.expectThrows(IllegalStateException.class, game::runGame);
        Assert.assertEquals(error.getMessage(), "Missing scripted zone-card choice");
        Assert.assertFalse(game.getGame().isGameOver(), "missing script cannot fabricate a terminal result");
    }

    private GameWrapper playAndCheckMana(boolean payable, PlayerSpecification winner) {
        return playAndCheckMana(payable, winner, new int[1]);
    }

    private GameWrapper playAndCheckMana(boolean payable, PlayerSpecification winner, int[] lifeBefore) {
        boolean[] paid = {false};
        ActivateAbilityAction action = new ActivateAbilityAction(PlayerSpecification.PLAYER_1,
                new CardSpecificationBuilder("Thrashing Wumpus").build()) {
            @Override
            public void activateAbility(Player player, Game game) {
                lifeBefore[0] = player.getLife();
                super.activateAbility(player, game);
                Assert.assertTrue(CardSpecificationHandler.INSTANCE.find(game,
                        new CardSpecificationBuilder("Swamp").controller(PlayerSpecification.PLAYER_1).battlefield().build())
                        .isTapped(), "actual engine mana payment must tap the only declared Swamp");
                Assert.assertEquals(player.getManaPool().totalMana(), 0, "mana must be spent, not left floating");
                paid[0] = true;
            }
        };
        PlayerActions actions = payable ? new PlayerActions(action,
                new ChooseZoneCardAction(PlayerSpecification.PLAYER_1,
                        new CardSpecificationBuilder("Forest").owner(PlayerSpecification.PLAYER_1).graveyard().build(),
                        ZoneType.Graveyard, ZoneType.Exile)) : new PlayerActions(action);
        GameWrapper game = new GameWrapper(position(true, payable).build(), actions);
        runGame(game, winner, 1);
        Assert.assertTrue(paid[0], "activation/payment control must really execute");
        return game;
    }
}
