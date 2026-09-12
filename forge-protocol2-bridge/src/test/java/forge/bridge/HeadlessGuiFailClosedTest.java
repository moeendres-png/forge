package forge.bridge;

import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/**
 * R18: every choice-bearing HeadlessBridgeGui affordance fails closed instead of
 * answering with a GUI default. No engine initialization required.
 */
public class HeadlessGuiFailClosedTest {

    private static HeadlessBridgeGui gui() {
        return new HeadlessBridgeGui();
    }

    @Test
    public void testOptionDialogThrows() {
        try {
            gui().showOptionDialog("m", "t", null, Arrays.asList("a", "b"), 0);
            Assert.fail("showOptionDialog must not return a default option");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("showOptionDialog"), e.getMessage());
        }
    }

    @Test
    public void testInputDialogThrows() {
        try {
            gui().showInputDialog("m", "t", null, "initial", Collections.singletonList("x"), false);
            Assert.fail("showInputDialog must not return the initial input");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("showInputDialog"), e.getMessage());
        }
    }

    @Test
    public void testFileDialogThrows() {
        try {
            gui().showFileDialog("t", "/tmp");
            Assert.fail("showFileDialog must not return null as an answer");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("showFileDialog"), e.getMessage());
        }
    }

    @Test
    public void testSaveFileThrows() {
        try {
            gui().getSaveFile(new File("/tmp/x"));
            Assert.fail("getSaveFile must not return null as an answer");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("getSaveFile"), e.getMessage());
        }
    }

    @Test
    public void testOrderThrows() {
        try {
            gui().order("t", "top", 0, 1, Arrays.asList("a"), Collections.emptyList());
            Assert.fail("order must not return an empty selection");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("order"), e.getMessage());
        }
    }

    @Test
    public void testChoicesThrow() {
        try {
            gui().getChoices("m", 1, 1, Arrays.asList("a", "b"), Collections.emptyList(), null);
            Assert.fail("getChoices must not return an empty selection");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("getChoices"), e.getMessage());
        }
    }

    @Test
    public void testChooseCardThrows() {
        try {
            gui().chooseCard("t", "m", Collections.<PaperCard>emptyList());
            Assert.fail("chooseCard must not return null as an answer");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("chooseCard"), e.getMessage());
        }
    }

    @Test
    public void testBoxedProductThrows() {
        try {
            gui().showBoxedProduct("t", "m", Collections.<PaperCard>emptyList());
            Assert.fail("showBoxedProduct must not answer false by default");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("showBoxedProduct"), e.getMessage());
        }
    }

    @Test
    public void testInfrastructureQueriesStayDeterministic() {
        Assert.assertFalse(gui().isRunningOnDesktop());
        Assert.assertFalse(gui().isLibgdxPort());
        Assert.assertFalse(gui().hasNetGame());
        Assert.assertEquals(gui().getAvatarCount(), 0);
        Assert.assertEquals(gui().getSleevesCount(), 0);
        Assert.assertEquals(gui().getScreenScale(), 1.0f);
        Assert.assertFalse(gui().isSupportedAudioFormat(new File("/tmp/x")));
        Assert.assertEquals(gui().encodeSymbols("x", false), "x");
        Assert.assertTrue(gui().isGuiThread());
        Assert.assertTrue(gui().getCurrentVersion().contains("forge-protocol2-bridge"));
    }

    @Test
    public void testExceptionCarriesNoGameData() {
        final java.util.List<String> options = Arrays.asList("Memnite", "Plains");
        try {
            gui().showOptionDialog("Choose Memnite now", "t", FSkinProp.BG_MATCH,
                    options, 1);
            Assert.fail("must throw");
        } catch (UnsupportedOperationException e) {
            Assert.assertFalse(e.getMessage().contains("Memnite"), e.getMessage());
            Assert.assertFalse(e.getMessage().contains("Plains"), e.getMessage());
        }
    }
}
