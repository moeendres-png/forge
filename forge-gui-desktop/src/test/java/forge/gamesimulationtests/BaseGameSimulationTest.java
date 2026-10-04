package forge.gamesimulationtests;

import forge.card.CardMockTestCase;
import forge.game.GameLogFormatter;
import forge.gamesimulationtests.util.GameWrapper;
import forge.gamesimulationtests.util.player.PlayerSpecification;
import forge.gamesimulationtests.util.player.PlayerSpecificationHandler;
import forge.gamesimulationtests.util.playeractions.testactions.AssertAction;
import forge.util.Lang;
import io.sentry.Sentry;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;

public abstract class BaseGameSimulationTest extends CardMockTestCase {

    @BeforeMethod
    @Override
    protected void initMocks() throws Exception {
        super.initMocks();
        mockStaticTracked(Sentry.class);
        mockStaticTracked(GameLogFormatter.class);
        Lang.createInstance("en-US");
    }

    protected void runGame(GameWrapper game, PlayerSpecification expectedWinner, int finalTurn,
            AssertAction... postGameAssertActions) {
        try {
            game.runGame();
            verifyThatTheGameHasFinishedAndThatPlayerHasWonOnTurn(game, expectedWinner, finalTurn);
            if (postGameAssertActions != null && postGameAssertActions.length > 0) {
                for (AssertAction assertAction : postGameAssertActions) {
                    assertAction.performAssertion(game.getGame());
                }
            }
        } catch (Throwable t) {
            System.out.println(game.toString());
            throw new RuntimeException(t);
        }
    }

    protected void verifyThatTheGameHasFinishedAndThatPlayerHasWonOnTurn(GameWrapper game,
            PlayerSpecification expectedWinner, int finalTurn) {
        Assert.assertTrue(game.getGame().isGameOver());
        Assert.assertEquals(game.getGame().getOutcome().getLastTurnNumber(), finalTurn);
        Assert.assertEquals(game.getGame().getOutcome().getWinningPlayer().getPlayer().getName(),
                PlayerSpecificationHandler.INSTANCE.find(game.getGame(), expectedWinner).getName());
        Assert.assertTrue(game.getPlayerActions() == null || game.getPlayerActions().isEmpty());
    }
}
