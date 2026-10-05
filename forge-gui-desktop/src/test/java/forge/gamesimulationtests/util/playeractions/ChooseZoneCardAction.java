package forge.gamesimulationtests.util.playeractions;

import java.util.List;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;
import forge.gamesimulationtests.util.card.CardSpecification;
import forge.gamesimulationtests.util.card.CardSpecificationHandler;
import forge.gamesimulationtests.util.player.PlayerSpecification;

/** Explicit discretionary choice, resolved only against engine-offered cards. */
public class ChooseZoneCardAction extends BasePlayerAction {
    private final CardSpecification card;
    private final ZoneType origin;
    private final ZoneType destination;

    public ChooseZoneCardAction(PlayerSpecification player, CardSpecification card,
            ZoneType origin, ZoneType destination) {
        super(player);
        this.card = card;
        this.origin = origin;
        this.destination = destination;
    }

    public Card choose(ZoneType offeredDestination, List<ZoneType> offeredOrigins, CardCollection offers) {
        if (offeredDestination != destination || offeredOrigins.size() != 1 || !offeredOrigins.contains(origin)) {
            throw new IllegalStateException("Scripted zone choice does not match engine route");
        }
        // The existing finder requires exactly one match. Missing or ambiguous
        // offers cannot silently choose a different card or synthesize an option.
        return CardSpecificationHandler.INSTANCE.find(offers, card);
    }
}
