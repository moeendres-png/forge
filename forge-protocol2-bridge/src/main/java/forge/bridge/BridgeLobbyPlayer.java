package forge.bridge;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * Lobby-side factory binding every in-game player to an {@link ExternalPlayerController}.
 *
 * <p>One instance per seat. No AI profile, no GUI handle, no defaults: all discretion
 * flows through the owning {@link BridgeSession}.
 */
public final class BridgeLobbyPlayer extends LobbyPlayer implements IGameEntitiesFactory {
    private final BridgeSession session;

    public BridgeLobbyPlayer(String name, BridgeSession session) {
        super(name);
        this.session = session;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        final Player player = new Player(getName(), game, id);
        player.setFirstController(new ExternalPlayerController(game, player, this, session));
        return player;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new ExternalPlayerController(slave.getGame(), slave, this, session);
    }

    @Override
    public void hear(LobbyPlayer player, String message) {
        // No chat surface on the bridge.
    }
}
