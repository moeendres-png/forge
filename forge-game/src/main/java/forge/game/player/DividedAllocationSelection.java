package forge.game.player;

import forge.game.GameEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One exact chooser-divided allocation vector submitted for Core validation. */
public final class DividedAllocationSelection {
    private final Map<GameEntity, Integer> allocations;

    public DividedAllocationSelection(final Map<GameEntity, Integer> allocations) {
        if (allocations == null) {
            throw new IllegalArgumentException("FORGE_DIVIDED_NULL_ALLOCATION");
        }
        this.allocations = Collections.unmodifiableMap(new LinkedHashMap<>(allocations));
    }

    public Map<GameEntity, Integer> getAllocations() { return allocations; }
}
