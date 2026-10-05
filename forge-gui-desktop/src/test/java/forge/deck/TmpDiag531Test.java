package forge.deck;
import forge.card.CardEdition;
import forge.card.CardMockTestCase;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.Test;
import java.util.*;
import java.util.regex.*;
public class TmpDiag531Test extends CardMockTestCase {
    @Test
    void diag() {
        Pattern n = Pattern.compile(DeckRecognizer.REX_CARD_NAME), s = Pattern.compile(DeckRecognizer.REX_SET_CODE), c = Pattern.compile(DeckRecognizer.REX_COLL_NUMBER);
        TreeSet<String> out = new TreeSet<>();
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getAllCards()) {
            Matcher m = n.matcher(pc.getName());
            if (!m.matches() || !pc.getName().equals(m.group(DeckRecognizer.REGRP_CARD))) out.add("NAME|" + pc.getName() + "|" + pc.getEdition());
            CardEdition e = FModel.getMagicDb().getCardEdition(pc.getEdition());
            if (e != null) for (String code : new String[]{e.getCode(), e.getScryfallCode()}) {
                Matcher ms = s.matcher(code);
                if (!ms.matches() || !code.equals(ms.group(DeckRecognizer.REGRP_SET))) out.add("SET|" + code + "|" + pc.getName());
            }
            String cn = pc.getCollectorNumber();
            if (!cn.equals(IPaperCard.NO_COLLECTOR_NUMBER)) { Matcher mc = c.matcher(cn); if (!mc.matches() || !cn.equals(mc.group(DeckRecognizer.REGRP_COLLNR))) out.add("CN|" + cn + "|" + pc.getName() + "|" + pc.getEdition()); }
        }
        out.forEach(x -> System.out.println("DIAG531 " + x));
    }
}
