package forge.bridge;

import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.jupnp.UpnpServiceConfiguration;

/**
 * No-display {@link IGuiBase} for the bridge process.
 *
 * <p>Every UI affordance is a no-op or a fail-closed default. The engine path used by
 * the bridge ({@code Match.createGame}/{@code Match.startGame} with
 * {@link ExternalPlayerController}) never touches these; they exist only so
 * {@code FModel.initialize} and card loading can run without Swing, AWT or Xvfb.
 */
public final class HeadlessBridgeGui implements IGuiBase {

    private final String assetsDir;

    public HeadlessBridgeGui() {
        this.assetsDir = resolveAssetsDir();
    }

    /** Installs this stub exactly once. Must precede {@code FModel.initialize}. */
    public static HeadlessBridgeGui install() {
        if (!(GuiBase.getInterface() instanceof HeadlessBridgeGui)) {
            GuiBase.setInterface(new HeadlessBridgeGui());
        }
        return (HeadlessBridgeGui) GuiBase.getInterface();
    }

    public static String resolveAssetsDir() {
        final String env = System.getenv("FORGE_ASSETS_DIR");
        if (env != null && !env.isEmpty()) {
            return withSeparator(env);
        }
        final String prop = System.getProperty("forge.assets.dir");
        if (prop != null && !prop.isEmpty()) {
            return withSeparator(prop);
        }
        if (new File("../forge-gui/res/cardsfolder").isDirectory()) {
            return "../forge-gui/";
        }
        if (new File("forge-gui/res/cardsfolder").isDirectory()) {
            return "forge-gui/";
        }
        return "../forge-gui/";
    }

    private static String withSeparator(String dir) {
        return dir.endsWith("/") || dir.endsWith(File.separator) ? dir : dir + "/";
    }

    public String getInstalledAssetsDir() {
        return assetsDir;
    }

    @Override
    public boolean isRunningOnDesktop() {
        return false;
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public String getCurrentVersion() {
        return VersionInfo.BRIDGE_NAME + "/" + VersionInfo.BRIDGE_VERSION;
    }

    @Override
    public void invokeInEdtNow(Runnable runnable) {
        runnable.run();
    }

    @Override
    public void invokeInEdtLater(Runnable runnable) {
        runnable.run();
    }

    @Override
    public void invokeInEdtAndWait(Runnable proc) {
        proc.run();
    }

    @Override
    public void runBackgroundTask(String message, Runnable task) {
        task.run();
    }

    @Override
    public boolean isGuiThread() {
        return true;
    }

    @Override
    public String getAssetsDir() {
        return assetsDir;
    }

    @Override
    public ImageFetcher getImageFetcher() {
        return null;
    }

    @Override
    public ISkinImage getSkinIcon(FSkinProp skinProp) {
        return null;
    }

    @Override
    public ISkinImage getUnskinnedIcon(String path) {
        return null;
    }

    @Override
    public ISkinImage getCardArt(PaperCard card, boolean backFace) {
        return null;
    }

    @Override
    public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) {
        return null;
    }

    @Override
    public void clearImageCache() {
    }

    @Override
    public String encodeSymbols(String str, boolean formatReminderText) {
        return str;
    }

    @Override
    public int getAvatarCount() {
        return 0;
    }

    @Override
    public int getSleevesCount() {
        return 0;
    }

    @Override
    public float getScreenScale() {
        return 1.0f;
    }

    @Override
    public void preventSystemSleep(boolean preventSleep) {
    }

    @Override
    public void download(GuiDownloadService service, Consumer<Boolean> callback) {
        callback.accept(false);
    }

    @Override
    public void copyToClipboard(String text) {
    }

    @Override
    public void browseToUrl(String url) throws IOException, URISyntaxException {
        throw new IOException("headless bridge cannot browse to " + url);
    }

    @Override
    public void showCardList(String title, String message, List<PaperCard> list) {
    }

    @Override
    public boolean showBoxedProduct(String title, String message, List<PaperCard> list) {
        return false;
    }

    @Override
    public void showBugReportDialog(String title, String text, boolean showExitAppBtn) {
        System.err.println("[bridge-bug] " + title + ": " + text);
    }

    @Override
    public void showImageDialog(ISkinImage image, String message, String title) {
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return defaultOption;
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput,
            List<String> inputOptions, boolean isNumeric) {
        return initialInput;
    }

    @Override
    public String showFileDialog(String title, String defaultDir) {
        return null;
    }

    @Override
    public File getSaveFile(File defaultFile) {
        return null;
    }

    @Override
    public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
            List<T> sourceChoices, List<T> destChoices) {
        return Collections.emptyList();
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices,
            Collection<T> selected, FSerializableFunction<T, String> display) {
        return Collections.emptyList();
    }

    @Override
    public PaperCard chooseCard(String title, String message, List<PaperCard> list) {
        return null;
    }

    @Override
    public boolean isSupportedAudioFormat(File file) {
        return false;
    }

    @Override
    public IAudioClip createAudioClip(String filename) {
        return null;
    }

    @Override
    public IAudioMusic createAudioMusic(String filename) {
        return null;
    }

    @Override
    public void startAltSoundSystem(String filename, boolean isSynchronized) {
    }

    @Override
    public void showSpellShop() {
    }

    @Override
    public void showBazaar() {
    }

    @Override
    public IGuiGame getNewGuiGame() {
        return null;
    }

    @Override
    public HostedMatch hostMatch() {
        return null;
    }

    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return null;
    }

    @Override
    public boolean hasNetGame() {
        return false;
    }
}
