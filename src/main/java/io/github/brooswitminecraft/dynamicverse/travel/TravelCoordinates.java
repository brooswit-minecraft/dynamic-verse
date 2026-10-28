package io.github.brooswitminecraft.dynamicverse.travel;

/** Shared absolute-coordinate bounds for the command surface and Minecraft movement bridge. */
public final class TravelCoordinates {
    public static final int MIN_HORIZONTAL = -30_000_000;
    public static final int MAX_HORIZONTAL_EXCLUSIVE = 30_000_000;
    public static final int MIN_VERTICAL = -20_000_000;
    public static final int MAX_VERTICAL_EXCLUSIVE = 20_000_000;

    private TravelCoordinates() { }

    /** Mirrors the pinned 1.21.1 Level spawnable block-position bounds. */
    public static boolean isValid(VerseTravelService.Position position) {
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z())) return false;
        int x = floorToBlock(position.x());
        int y = floorToBlock(position.y());
        int z = floorToBlock(position.z());
        return x >= MIN_HORIZONTAL && x < MAX_HORIZONTAL_EXCLUSIVE
                && z >= MIN_HORIZONTAL && z < MAX_HORIZONTAL_EXCLUSIVE
                && y >= MIN_VERTICAL && y < MAX_VERTICAL_EXCLUSIVE;
    }

    private static int floorToBlock(double coordinate) {
        return (int) Math.floor(coordinate);
    }
}
