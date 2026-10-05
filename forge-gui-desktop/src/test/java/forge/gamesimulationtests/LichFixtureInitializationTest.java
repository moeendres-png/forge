package forge.gamesimulationtests;

import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Construction-only control; no actual-card behavior qualification credit. */
public class LichFixtureInitializationTest extends BaseGameSimulationTest {
    @Test
    public void multiwordTypesAreLoadedBeforeCardParsing() {
        Assert.assertTrue(FModel.getMagicDb().getCommonCards().getCard("The Tenth Doctor").getRules().getType().hasSubtype("Time Lord"),
                "source multiword types must be loaded before card rules are cached");
    }
}
