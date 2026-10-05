package forge.card;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import forge.ImageCache;
import forge.ImageKeys;
import forge.Singletons;
import forge.StaticData;
import forge.d24.D24ExecutionGuardTest;
import forge.gamesimulationtests.util.CardDatabaseHelper;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgeProfileProperties;
import forge.model.FModel;
import forge.util.Localizer;
import forge.util.Lang;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.TextUtil;

public abstract class CardMockTestCase {

    public static final String MOCKED_LOCALISED_STRING = "any localised string";

    private final List<MockedStatic<?>> staticMocks = new ArrayList<>();
    private MockedStatic<FModel> fModelMock;
    private Localizer previousLocalizer;
    private boolean previousLocalizerCaptured;

    protected static String getUserDir() {
        // Adapted - reduced version from ForgeProfileProperties (which is private)
        final String osName = System.getProperty("os.name");
        final String homeDir = System.getProperty("user.home");

        if (StringUtils.isEmpty(osName) || StringUtils.isEmpty(homeDir)) {
            throw new RuntimeException("cannot determine OS and user home directory");
        }

        final String fallbackDataDir = TextUtil.concatNoSpace(homeDir, "/.forge/");

        if (StringUtils.containsIgnoreCase(osName, "windows")) {
            String appRoot = System.getenv().get("APPDATA");
            if (StringUtils.isEmpty(appRoot)) {
                appRoot = fallbackDataDir;
            }
            return appRoot + File.separator + "Forge" + File.separator;
        } else if (StringUtils.containsIgnoreCase(osName, "mac os x")) {
            return TextUtil.concatNoSpace(homeDir, "/Library/Application Support/Forge/");
        }
        // Linux and everything else
        return fallbackDataDir;
    }

    /**
     * Initializes ForgeConstants without rewriting static-final fields. The production
     * collaborators that normally supply profile locations are controlled only while
     * the class initializes, then immediately restored.
     */
    protected void initForgeConstants() throws ClassNotFoundException {
        final String userDir = getUserDir();
        final String cacheDir = userDir + "cache" + File.separator;
        final String decksDir = userDir + "decks" + File.separator;

        final IGuiBase gui = Mockito.mock(IGuiBase.class);
        Mockito.when(gui.getAssetsDir()).thenReturn("../forge-gui/");

        try (MockedStatic<GuiBase> guiBaseMock = Mockito.mockStatic(GuiBase.class);
                MockedStatic<ForgeProfileProperties> profileMock = Mockito.mockStatic(ForgeProfileProperties.class)) {
            guiBaseMock.when(GuiBase::getInterface).thenReturn(gui);
            guiBaseMock.when(GuiBase::isUsingAppDirectory).thenReturn(false);

            profileMock.when(ForgeProfileProperties::getUserDir).thenReturn(userDir);
            profileMock.when(ForgeProfileProperties::getCacheDir).thenReturn(cacheDir);
            profileMock.when(ForgeProfileProperties::getCardPicsDir)
                    .thenReturn(cacheDir + "pics" + File.separator);
            profileMock.when(ForgeProfileProperties::getCardPicsSubDirs)
                    .thenReturn(Collections.emptyMap());
            profileMock.when(ForgeProfileProperties::getDecksDir).thenReturn(decksDir);
            profileMock.when(ForgeProfileProperties::getDecksConstructedDir)
                    .thenReturn(decksDir + "constructed" + File.separator);

            Class.forName(ForgeConstants.class.getName(), true, ForgeConstants.class.getClassLoader());
        }
    }

    protected void setMock(final Localizer mock) {
        try {
            final Field instance = Localizer.class.getDeclaredField("instance");
            instance.setAccessible(true);
            if (!previousLocalizerCaptured) {
                previousLocalizer = (Localizer) instance.get(null);
                previousLocalizerCaptured = true;
            }
            instance.set(null, mock);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeMethod
    protected void initMocks() throws Exception {
        D24ExecutionGuardTest.recordAffectedDiscovery(getClass());
        closeStaticMocks();
        restoreLocalizer();

        initForgeConstants();
        initForgePreferences();
        initCardImageMocks();
        // FModel startup is mocked: prepare the real language/type data before
        // eagerly parsing card scripts, including multiword types and aliases.
        Lang.createInstance("en-US");
        if (!CardType.Constant.LOADED.isSet()) {
            FileSection.parseSections(FileUtil.readFile(ForgeConstants.TYPE_LIST_FILE))
                    .forEach(CardType.Helper::parseTypes);
            CardType.Constant.LOADED.set();
        }
        initializeStaticData();
    }

    protected void initForgePreferences() {
        mockStaticTracked(Singletons.class);
        fModelMock = mockStaticTracked(FModel.class);

        final ForgePreferences forgePreferences = new ForgePreferences();
        final Localizer localizerMock = Mockito.mock(Localizer.class);
        setMock(localizerMock);
        Mockito.when(localizerMock.getMessage(Mockito.anyString())).thenReturn(MOCKED_LOCALISED_STRING);
        fModelMock.when(FModel::getPreferences).thenReturn(forgePreferences);
    }

    protected boolean mockedCardHasImage() {
        return true;
    }

    protected void initCardImageMocks() {
        mockStaticTracked(ImageIO.class);
        mockStaticTracked(ImageCache.class);
        final MockedStatic<ImageKeys> imageKeysMock = mockStaticTracked(ImageKeys.class);
        final boolean hasImage = mockedCardHasImage();
        imageKeysMock.when(() -> ImageKeys.hasImage(Mockito.any(PaperCard.class), Mockito.anyBoolean()))
                .thenReturn(hasImage);
        imageKeysMock.when(() -> ImageKeys.hasImage(Mockito.any(PaperCard.class))).thenReturn(hasImage);
    }

    protected void initializeStaticData() {
        StaticData data = CardDatabaseHelper.getStaticDataToPopulateOtherMocks(getClass());
        CardDatabaseHelper.resetForTestMethod(data);
        setMagicDb(data);
    }

    protected final void setMagicDb(final StaticData data) {
        if (fModelMock == null) {
            throw new IllegalStateException("FModel static mock is not initialized");
        }
        fModelMock.when(FModel::getMagicDb).thenReturn(data);
    }

    protected final <T> MockedStatic<T> mockStaticTracked(final Class<T> type) {
        final MockedStatic<T> mock = Mockito.mockStatic(type);
        staticMocks.add(mock);
        return mock;
    }

    @AfterMethod(alwaysRun = true)
    protected void releaseMocks(final ITestResult result) {
        D24ExecutionGuardTest.recordAffectedResult(result);
        closeStaticMocks();
        restoreLocalizer();
    }

    private void closeStaticMocks() {
        for (int i = staticMocks.size() - 1; i >= 0; i--) {
            final MockedStatic<?> mock = staticMocks.get(i);
            if (!mock.isClosed()) {
                mock.close();
            }
        }
        staticMocks.clear();
        fModelMock = null;
    }

    private void restoreLocalizer() {
        if (!previousLocalizerCaptured) {
            return;
        }
        try {
            final Field instance = Localizer.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, previousLocalizer);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        } finally {
            previousLocalizer = null;
            previousLocalizerCaptured = false;
        }
    }
}
