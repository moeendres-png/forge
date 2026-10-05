package forge.gamesimulationtests;

import forge.card.CardType;
import forge.model.FModel;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.ITestResult;

/** Non-TestNG entry point, launched only by the cold-process construction control. */
public final class LichFixtureInitializationProbe extends BaseGameSimulationTest {
    private LichFixtureInitializationProbe() { }

    public static void main(final String[] args) throws Exception {
        Assert.assertFalse(CardType.Constant.LOADED.isSet(), "child must start before type initialization");
        final LichFixtureInitializationProbe fixture = new LichFixtureInitializationProbe();
        final ITestResult result = Mockito.mock(ITestResult.class);
        Mockito.when(result.getInstance()).thenReturn(fixture);
        try {
            fixture.initMocks();
            Assert.assertTrue(FModel.getMagicDb().getCommonCards().getCard("The Tenth Doctor")
                    .getRules().getType().hasSubtype("Time Lord"),
                    "source multiword types must be loaded before card rules are cached");
        } finally {
            fixture.releaseMocks(result);
        }
        System.out.println("D22_COLD_TYPES=PASS");
    }
}
