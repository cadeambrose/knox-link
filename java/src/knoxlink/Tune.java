package knoxlink;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;

/**
 * Fixes for bugs in the game itself that cost frame rate (and so battery and heat). Applied by
 * ZombieBuddy when the game starts; each one only changes what the game does in the broken case.
 */
public final class Tune {
    private Tune() {}

    /**
     * Build 42: IsoTree.isPlayerInsideARoom trusts IsoPlayer.isInARoom() and then reads
     * getSquare().getRoom() without checking it. On some squares (and on the death camera) the room
     * is null, so the tree renderer throws for every tree, every frame: thousands of logged errors a
     * minute. In that case the player isn't inside a room, which is what this answers.
     */
    @Patch(className = "zombie.iso.objects.IsoTree", methodName = "isPlayerInsideARoom")
    public static final class TreeRoomCheck {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoPlayer player) {
            if (player == null) return true;
            IsoGridSquare sq = player.getSquare();
            return sq == null || sq.getRoom() == null;   // true = skip the original; it then returns false
        }
    }
}
