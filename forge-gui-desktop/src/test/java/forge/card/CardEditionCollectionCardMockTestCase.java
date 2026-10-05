package forge.card;

import static org.testng.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import forge.deck.CardPool;
import forge.item.PaperCard;
import forge.model.FModel;

public class CardEditionCollectionCardMockTestCase extends CardMockTestCase {

    @Test
    public void testGetTheLatestOfAllTheOriginalEditionsOfCardsInPoolWithOriginalSets() {
        CardEdition.Collection editions = FModel.getMagicDb().getEditions();

        CardDb cardDb = FModel.getMagicDb().getCommonCards();
        String[] cardNames = { "Shivan Dragon", "Animate Wall", "Balance", "Blessing", "Force of Will" };
        String[] expectedSets = { "LEA", "LEA", "LEA", "LEA", "ALL" };
        List<PaperCard> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String cardName = cardNames[i];
            String expectedSet = expectedSets[i];
            PaperCard card = cardDb.getCardFromEditions(cardName, CardDb.CardArtPreference.ORIGINAL_ART_ALL_EDITIONS);
            assertEquals(card.getEdition(), expectedSet);
            cards.add(card);
        }

        CardPool pool = new CardPool();
        pool.add(cards);
        CardEdition ed = editions.getTheLatestOfAllTheOriginalEditionsOfCardsIn(pool);
        assertEquals(ed.getCode(), "ALL");
    }

    @Test
    public void testGetTheLatestOfAllTheOriginalEditionsOfCardsInPoolWithLatestArtSets() {
        CardEdition.Collection editions = FModel.getMagicDb().getEditions();

        CardDb cardDb = FModel.getMagicDb().getCommonCards();
        String[] cardNames = { "Shivan Dragon", "Animate Wall", "Balance", "Blessing", "Force of Will" };
        // #531: Foundations (FDN, 2024-11-15) reprints Shivan Dragon after P30T (2023-09-01), and
        // Secrets of Strixhaven Mystical Archive (SOA, 2026-04-24) reprints Force of Will after DMR.
        String[] expectedSets = { "FDN", "30A", "30A", "30A", "SOA" };
        List<PaperCard> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String cardName = cardNames[i];
            String expectedSet = expectedSets[i];
            PaperCard card = cardDb.getCardFromEditions(cardName, CardDb.CardArtPreference.LATEST_ART_ALL_EDITIONS);
            assertEquals(card.getEdition(), expectedSet, "Assertion Failed for " + cardName);
            cards.add(card);
        }

        CardPool pool = new CardPool();
        pool.add(cards);
        CardEdition ed = editions.getTheLatestOfAllTheOriginalEditionsOfCardsIn(pool);
        assertEquals(ed.getCode(), "ALL");
    }

    @Test
    public void testUnknownEditionSentinelExposesCodes() {
        // Cards without any edition entry are stored under the "???" sentinel and
        // StaticData.getCardEdition returns CardEdition.UNKNOWN for them.
        CardEdition unknown = FModel.getMagicDb().getCardEdition(CardEdition.UNKNOWN_CODE);
        assertEquals(unknown, CardEdition.UNKNOWN);
        assertEquals(unknown.getScryfallCode(), CardEdition.UNKNOWN_CODE);
        assertEquals(unknown.getTokensCode(), "t" + CardEdition.UNKNOWN_CODE);
        assertEquals(unknown.getCardsLangCode(), "en");
    }
}
