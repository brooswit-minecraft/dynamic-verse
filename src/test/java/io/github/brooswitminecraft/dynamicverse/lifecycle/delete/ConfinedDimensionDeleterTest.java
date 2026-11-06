package io.github.brooswitminecraft.dynamicverse.lifecycle.delete;

import static org.junit.jupiter.api.Assertions.*;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfinedDimensionDeleterTest {
    @TempDir Path root;
    private SafeDeleteOperation.Target target() { return new SafeDeleteOperation.Target("dynamicverse:owned","test",1,root,Path.of("dimensions/dynamicverse/owned"),UUID.randomUUID()); }
    private Path setup() throws Exception {
        Path owned=root.resolve("dimensions/dynamicverse/owned"); Files.createDirectories(owned.resolve("region"));
        Files.writeString(owned.resolve("region/test.mca"),"disposable test-owned data"); return owned;
    }
    private ClosedDimensionReceipt receipt() { return new ClosedDimensionReceipt("dynamicverse:owned",7,true,ClosedDimensionStatus.SAVED_AND_CLOSED,false); }
    @Test void removesOnlyTheExactOwnedTreeAndPreservesOtherWorlds() throws Exception {
        Path owned=setup(); Path sibling=root.resolve("dimensions/dynamicverse/neighbour"); Files.createDirectory(sibling);
        Path vanilla=root.resolve("region"); Files.createDirectory(vanilla); Files.writeString(vanilla.resolve("untouched"),"keep");
        var target=target(); var deleter=new ConfinedDimensionDeleter(); deleter.validate(target);
        deleter.delete(target,receipt(),(key,generation)->()->{});
        assertFalse(Files.exists(owned)); assertTrue(Files.isDirectory(sibling)); assertEquals("keep",Files.readString(vanilla.resolve("untouched")));
    }
    @Test void linkedEntryRefusesBeforeAnyClaimOrDeletion() throws Exception {
        Path owned=setup(); Path outside=root.resolve("outside-test-data"); Files.writeString(outside,"keep");
        Files.createSymbolicLink(owned.resolve("link"),outside);
        assertThrows(Exception.class,()->new ConfinedDimensionDeleter().validate(target()));
        assertTrue(Files.exists(owned.resolve("region/test.mca"))); assertEquals("keep",Files.readString(outside));
    }
    @Test void mutationAfterClaimFailsClosedAndPreservesExternalData() throws Exception {
        setup(); Path outside=root.resolve("outside-test-data"); Files.writeString(outside,"keep"); var target=target();
        var deleter=new ConfinedDimensionDeleter(claim -> Files.createSymbolicLink(claim.resolve("late-link"),outside));
        assertThrows(Exception.class,()->deleter.delete(target,receipt(),(key,generation)->()->{}));
        Path claim=root.resolve("dimensions/dynamicverse").resolve(target.quarantineName());
        assertTrue(Files.exists(claim.resolve("region/test.mca"))); assertEquals("keep",Files.readString(outside));
    }
    @Test void forgedForeignOrTraversingMappingCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class,()->new SafeDeleteOperation.Target("minecraft:overworld","test",1,root,Path.of("region"),UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->new SafeDeleteOperation.Target("dynamicverse:owned","test",1,root,Path.of("dimensions/dynamicverse/../owned"),UUID.randomUUID()));
    }
    @Test void failedCloseOrAbsentFenceCannotMoveOrRemoveAnyData() throws Exception {
        Path owned=setup(); var target=target(); var deleter=new ConfinedDimensionDeleter();
        assertThrows(Exception.class,()->deleter.delete(target,new ClosedDimensionReceipt(target.key(),7,true,ClosedDimensionStatus.FAILED_CLOSE,false),(key,generation)->()->{}));
        assertThrows(Exception.class,()->deleter.delete(target,receipt(),(key,generation)->null));
        assertTrue(Files.exists(owned.resolve("region/test.mca")));
    }
    @Test void linkedStorageAncestorCannotRedirectRemoval() throws Exception {
        setup(); Path linked=root.resolve("linked-root"); Files.createSymbolicLink(linked,root);
        var target=new SafeDeleteOperation.Target("dynamicverse:owned","test",1,linked,Path.of("dimensions/dynamicverse/owned"),UUID.randomUUID());
        assertThrows(Exception.class,()->new ConfinedDimensionDeleter().validate(target));
        assertTrue(Files.exists(root.resolve("dimensions/dynamicverse/owned/region/test.mca")));
    }
}
