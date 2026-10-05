package forge.gamesimulationtests;

import forge.card.CardMockTestCase;
import forge.card.CardType;
import forge.localinstance.properties.ForgeConstants;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.game.GameLogFormatter;
import forge.gamesimulationtests.util.GameWrapper;
import forge.gamesimulationtests.util.player.PlayerSpecification;
import forge.gamesimulationtests.util.player.PlayerSpecificationHandler;
import forge.gamesimulationtests.util.playeractions.testactions.AssertAction;
import forge.util.Lang;
import forge.util.Localizer;
import io.sentry.Sentry;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;

public abstract class BaseGameSimulationTest extends CardMockTestCase {

    @BeforeMethod
    @Override
    protected void initMocks() throws Exception {
        Lang.createInstance("en-US");
        super.initMocks();
        // FModel is mocked in this fixture, so its normal dynamic-data startup
        // does not run. Load the actual type definitions before constructing a
        // game; otherwise subtype cleanup removes Aura and other legal types.
        if (!CardType.Constant.LOADED.isSet()) {
            FileSection.parseSections(FileUtil.readFile(ForgeConstants.TYPE_LIST_FILE))
                    .forEach(CardType.Helper::parseTypes);
            CardType.Constant.LOADED.set();
        }
        // Game prompts use parameterized translations. The card database's
        // no-argument localization mock cannot represent those messages.
        // Use the normal source-controlled language resources for game tests;
        // the inherited fixture restores the previous singleton after each test.
        setMock(null);
        Localizer.getInstance().initialize("en-US", ForgeConstants.LANG_DIR);
        mockStaticTracked(Sentry.class);
        mockStaticTracked(GameLogFormatter.class);
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
