package io.github.brooswitminecraft.dynamicverse.lifecycle.delete;

import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionReceipt;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.SourceWriteReloadFence;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** One explicitly confirmed owned-world delete; no automatic retry or recovery deletion. */
public final class SafeDeleteOperation {
    public record Target(String key, String identity, long generation, Path root, Path relative, UUID operationId) {
        public Target {
            Objects.requireNonNull(key); Objects.requireNonNull(identity); Objects.requireNonNull(root);
            Objects.requireNonNull(relative); Objects.requireNonNull(operationId);
            if (!key.matches("dynamicverse:[a-z0-9_-]{1,32}") || identity.isBlank()) throw new IllegalArgumentException("unowned delete target");
            Path exact = Path.of("dimensions", "dynamicverse", key.substring("dynamicverse:".length()));
            if (!relative.equals(exact) || relative.isAbsolute()) throw new IllegalArgumentException("delete path is not the exact owned dimension mapping");
        }
        public String quarantineName() { return ".delete-" + relative.getFileName() + "-" + operationId; }
    }
    public interface Port {
        boolean matchesCurrent(Target target);
        boolean hasPlayers(Target target);
        boolean beginClose(Target target, Consumer<ClosedDimensionReceipt> terminal);
        boolean isClosedAndAbsent(Target target);
    }
    public interface Journal {
        void begin(Target target);
        void cancelKnownNoEffect(Target target);
        void deleted(Target target);
    }
    public interface Removal {
        void validate(Target target) throws Exception;
        void delete(Target target, ClosedDimensionReceipt receipt, SourceWriteReloadFence fence) throws Exception;
    }
    public interface Reservation extends AutoCloseable {
        void restoreKnownNoEffect();
        void bindCloseGeneration(long generation);
        SourceWriteReloadFence fence();
        void deleted();
        @Override void close(); // uncertain outcome always quarantines
    }
    public enum Admission { ADMITTED, NOT_CURRENT, PLAYERS_PRESENT, REFUSED, UNAVAILABLE }
    public enum Outcome { DELETED, QUARANTINED }
    private final Target target;
    private final Port port;
    private final Journal journal;
    private final Removal removal;
    private final Reservation reservation;
    private final Consumer<Outcome> completion;
    private boolean starting;
    private boolean admitted;
    private boolean done;
    private boolean terminalSeen;
    private ClosedDimensionReceipt inline;
    private int ticks;
    public SafeDeleteOperation(Target target, Port port, Journal journal, Removal removal,
                               Reservation reservation, Consumer<Outcome> completion) {
        this.target=Objects.requireNonNull(target); this.port=Objects.requireNonNull(port);
        this.journal=Objects.requireNonNull(journal); this.removal=Objects.requireNonNull(removal);
        this.reservation=Objects.requireNonNull(reservation); this.completion=Objects.requireNonNull(completion);
    }
    public synchronized Admission start() {
        if (starting || admitted || done) throw new IllegalStateException("delete operation already started");
        try {
            if (!port.matchesCurrent(target)) { reservation.restoreKnownNoEffect(); done=true; return Admission.NOT_CURRENT; }
            if (port.hasPlayers(target)) { reservation.restoreKnownNoEffect(); done=true; return Admission.PLAYERS_PRESENT; }
            removal.validate(target);
        } catch (Exception | LinkageError unavailable) {
            reservation.restoreKnownNoEffect(); done=true; return Admission.UNAVAILABLE;
        }
        try { journal.begin(target); }
        catch (RuntimeException uncertainWrite) { quarantine(); return Admission.ADMITTED; }
        starting=true;
        boolean accepted;
        try { accepted=port.beginClose(target,this::terminal); }
        catch (RuntimeException | LinkageError uncertainProvider) {
            starting=false; quarantine(); return Admission.ADMITTED;
        }
        starting=false;
        if (!accepted) {
            if (terminalSeen) { quarantine(); return Admission.ADMITTED; }
            try { journal.cancelKnownNoEffect(target); reservation.restoreKnownNoEffect(); done=true; return Admission.REFUSED; }
            catch (RuntimeException uncertainWrite) { quarantine(); return Admission.ADMITTED; }
        }
        admitted=true;
        if (terminalSeen) finish(inline);
        return Admission.ADMITTED;
    }
    private synchronized void terminal(ClosedDimensionReceipt receipt) {
        if (done || terminalSeen) return;
        terminalSeen=true;
        if (starting) { inline=receipt; return; }
        if (admitted) finish(receipt);
    }
    private void finish(ClosedDimensionReceipt receipt) {
        try {
            if (receipt == null || receipt.generation()<0 || !receipt.isExactSuccess(target.key(),receipt.generation())
                    || !port.isClosedAndAbsent(target)) { quarantine(); return; }
            reservation.bindCloseGeneration(receipt.generation());
            SourceWriteReloadFence checkedFence=(key,generation) -> {
                if (!target.key().equals(key) || generation!=receipt.generation() || !port.isClosedAndAbsent(target))
                    throw new IllegalStateException("delete lost exact close/absence evidence");
                return reservation.fence().retain(key,generation);
            };
            removal.delete(target,receipt,checkedFence);
            if (!port.isClosedAndAbsent(target)) { quarantine(); return; }
            journal.deleted(target); // remove durable READY and intent before forgetting ownership
            reservation.deleted();
            done=true;
            completion.accept(Outcome.DELETED);
        } catch (Exception | LinkageError uncertain) { quarantine(); }
    }
    public synchronized void tick() { if (!done && admitted && ++ticks>=200) quarantine(); }
    public synchronized void abort() { if (!done) quarantine(); }
    public synchronized boolean isDone() { return done; }
    private void quarantine() {
        if (done) return;
        done=true;
        reservation.close();
        completion.accept(Outcome.QUARANTINED);
    }
}
