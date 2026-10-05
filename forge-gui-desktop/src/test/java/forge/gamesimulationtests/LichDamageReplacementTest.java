package forge.gamesimulationtests;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.GameWrapper;
import forge.gamesimulationtests.util.card.CardSpecificationBuilder;
import forge.gamesimulationtests.util.card.CardSpecificationHandler;
import forge.gamesimulationtests.util.gamestate.GameStateSpecificationBuilder;
import forge.gamesimulationtests.util.player.PlayerSpecification;
import forge.gamesimulationtests.util.playeractions.ActivateAbilityAction;
import forge.gamesimulationtests.util.playeractions.ChooseZoneCardAction;
import forge.gamesimulationtests.util.playeractions.PlayerActions;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Actual-card controls for the historical disabled CR104 fixture.
 * They do not claim an isolated simultaneous win/loss obligation.
 */
public class LichDamageReplacementTest extends BaseGameSimulationTest {
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
        Assert.assertTrue(error.getMessage().startsWith("No abilities found for")
                || error.getMessage().startsWith("Scripted activation"), error.getMessage());
        Assert.assertFalse(game.getGame().isGameOver(), "denial is not an injected terminal outcome");
    }

    @Test
    public void unpayableDamageReplacementLosesBeforeDraw() {
        playAndCheckMana(false, PlayerSpecification.PLAYER_2);
    }

    @Test
    public void payableDamageReplacementAllowsManiacWin() {
        playAndCheckMana(true, PlayerSpecification.PLAYER_1);
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

    private void playAndCheckMana(boolean payable, PlayerSpecification winner) {
        boolean[] paid = {false};
        ActivateAbilityAction action = new ActivateAbilityAction(PlayerSpecification.PLAYER_1,
                new CardSpecificationBuilder("Thrashing Wumpus").build()) {
            @Override
            public void activateAbility(Player player, Game game) {
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
    }
}
