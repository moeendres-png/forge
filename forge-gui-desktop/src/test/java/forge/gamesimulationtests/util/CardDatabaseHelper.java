package forge.gamesimulationtests.util;

import forge.CardStorageReader;
import forge.StaticData;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;

public class CardDatabaseHelper {
    private static StaticData eagerStaticData;
    private static StaticData lazyStaticData;

    public static PaperCard getCard(String name) {
        StaticData data = getStaticData(false);

        PaperCard result = data.getCommonCards().getCard(name);
        if (result == null) {
            throw new IllegalArgumentException("Failed to get card with name " + name);
        }
        return result;
    }

    private static StaticData getStaticData(boolean lazyLoad) {
        StaticData existing = lazyLoad ? lazyStaticData : eagerStaticData;
        if (existing != null) {
            return existing;
        }

        StaticData initialized = initialize(lazyLoad);
        if (lazyLoad) {
            lazyStaticData = initialized;
        } else {
            eagerStaticData = initialized;
        }
        return initialized;
    }

    private static StaticData initialize(boolean loadCardsLazily) {
        final CardStorageReader reader = new CardStorageReader(ForgeConstants.CARD_DATA_DIR,
                null, loadCardsLazily);
        CardStorageReader customReader;
        try {
            customReader = new CardStorageReader(ForgeConstants.USER_CUSTOM_CARDS_DIR,
                    null, loadCardsLazily);
        } catch (Exception e) {
            customReader = null;
        }
        return new StaticData(reader, customReader, ForgeConstants.EDITIONS_DIR,
                ForgeConstants.USER_CUSTOM_EDITIONS_DIR, ForgeConstants.BLOCK_DATA_DIR,
                "Latest Art All Editions",
                true,
                false);
    }

    public static StaticData getStaticDataToPopulateOtherMocks() {
        return getStaticData(false);
    }

    public static StaticData getStaticDataToPopulateOtherMocks(boolean lazyLoad) {
        return getStaticData(lazyLoad);
    }
}
