package forge.gamesimulationtests.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import forge.CardStorageReader;
import forge.StaticData;
import forge.card.CardDb;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;

public final class CardDatabaseHelper {
    private static final Map<FixtureKey, StaticData> FIXTURES = new HashMap<>();
    private static FixtureKey activeFixture;

    private CardDatabaseHelper() {
    }

    public static PaperCard getCard(String name) {
        StaticData data = getStaticData(CardDatabaseHelper.class, false);

        PaperCard result = data.getCommonCards().getCard(name);
        if (result == null) {
            throw new IllegalArgumentException("Failed to get card with name " + name);
        }
        return result;
    }

    public static synchronized StaticData getStaticDataToPopulateOtherMocks(Class<?> fixtureOwner) {
        return getStaticData(fixtureOwner, false);
    }

    public static synchronized StaticData getStaticDataToPopulateOtherMocks(Class<?> fixtureOwner, boolean lazyLoad) {
        return getStaticData(fixtureOwner, lazyLoad);
    }

    public static synchronized StaticData createFreshStaticDataForTestMethod(boolean lazyLoad) {
        clearCrossFixtureCardDbState();
        activeFixture = null;
        StaticData data = initialize(lazyLoad);
        activate(data);
        return data;
    }

    private static StaticData getStaticData(Class<?> fixtureOwner, boolean lazyLoad) {
        FixtureKey key = new FixtureKey(fixtureOwner.getName(), lazyLoad);
        if (!key.equals(activeFixture)) {
            clearCrossFixtureCardDbState();
            activeFixture = key;
        }

        StaticData data = FIXTURES.computeIfAbsent(key, ignored -> initialize(lazyLoad));
        activate(data);
        return data;
    }

    public static synchronized void resetForTestMethod(StaticData data) {
        activate(data);
        clearCrossFixtureCardDbState();
        resetCardDb(data.getCommonCards());
        resetCardDb(data.getVariantCards());
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

    private static void activate(StaticData data) {
        try {
            Field lastInstance = StaticData.class.getDeclaredField("lastInstance");
            lastInstance.setAccessible(true);
            lastInstance.set(null, data);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to activate test StaticData fixture", e);
        }
    }

    private static void resetCardDb(CardDb cardDb) {
        cardDb.setCardArtPreference(true, false);
        try {
            Method reIndex = CardDb.class.getDeclaredMethod("reIndex");
            reIndex.setAccessible(true);
            reIndex.invoke(cardDb);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to re-index CardDb test fixture", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearCrossFixtureCardDbState() {
        try {
            Field artPrefs = CardDb.class.getDeclaredField("artPrefs");
            artPrefs.setAccessible(true);
            ((Map<String, String>) artPrefs.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to reset CardDb test fixture state", e);
        }
    }

    private record FixtureKey(String ownerClassName, boolean lazyLoad) {
    }
}
