package forge.card;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ImageKeys;
import forge.item.PaperCard;

/**
 * Test Case for CardDb forcing No Image for all the cards. Check that
 * everything still applies the same.
 *
 * Note: Run test for the class, being subclass will also run all other tests as
 * regression.
 */
public class CardDbWithNoImageCardDbMockTestCase extends CardDbCardMockTestCase {

    @Override
    @BeforeMethod
    public void setup() {
        super.setup();
    }

    @Override
    protected boolean mockedCardHasImage() {
        return false;
    }

    @Test
    public void testCardIsReturnedEvenIfThereIsNoImage() {
        PaperCard shivanDragon = this.cardDb.getCard(cardNameShivanDragon);
        assertNotNull(shivanDragon);
        assertFalse(ImageKeys.hasImage(shivanDragon));
        assertFalse(shivanDragon.hasImage());
    }

}
