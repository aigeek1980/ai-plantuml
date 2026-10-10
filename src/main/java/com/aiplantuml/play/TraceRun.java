package com.aiplantuml.play;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a {@link ScriptModel} one statement at a time, building up the trace of what
 * actually ran. Branches not chosen are never emitted at all, so they take up no room
 * in the drawing, and a loop body is emitted once per iteration asked for.
 * <p>
 * A par block runs its lanes together rather than one after the other: a step takes the
 * next statement from every lane at once, which is what parallel means. Because the
 * lanes interleave, the trace is kept as a tree rather than a list of lines - each
 * lane's statements have to come out grouped under their own branch of the par.
 * <p>
 * Choices are not decided here: the caller is asked, so it can put the question to the
 * user and keep the walk going afterwards.
 */
public final class TraceRun {

    /** Asked when the walk reaches a block that needs a decision. */
    public interface Decider {
        /** @return index of the branch to follow, or -1 to stop the run */
        int chooseBranch(ScriptModel.Block block);

        /** @return how many times to run the loop body, or -1 to stop the run */
        int chooseIterations(ScriptModel.Block block);
    }

    /** What has been emitted: plain lines, and par blocks holding a list per lane. */
    private sealed interface Out permits Emitted, ParallelOut {
    }

    private record Emitted(String line) implements Out {
    }

    private record ParallelOut(List<String> labels, List<List<Out>> lanes) implements Out {
    }

    /** A position in the walk: either a list of statements, or lanes running together. */
    private sealed interface Frame permits Sequence, Parallel {
    }

    private record Sequence(List<ScriptModel.Node> nodes, int index, int repeatsLeft,
                            ScriptModel.Block loop, List<Out> out) implements Frame {
    }

    private record Parallel(List<Deque<Frame>> lanes, List<List<Out>> laneOut) implements Frame {
    }

    private final ScriptModel script;
    private final Decider decider;

    private final List<Out> output = new ArrayList<>();
    private final Set<String> onStage = new LinkedHashSet<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private int statements = 0;
    private int lastLine = -1;

    public TraceRun(ScriptModel script, Decider decider) {
        this.script = script;
        this.decider = decider;
        reset();
    }

    public void reset() {
        output.clear();
        onStage.clear();
        stack.clear();
        statements = 0;
        lastLine = -1;
        stack.push(new Sequence(script.body(), 0, 0, null, output));
    }

    /**
     * Runs until something is drawn, which for a par means one statement from each lane.
     *
     * @return false when the run is over or the user declined a choice
     */
    public boolean step() {
        List<ScriptModel.Line> drawn = advance(stack);
        if (drawn.isEmpty()) return false;
        for (ScriptModel.Line line : drawn) {
            onStage.addAll(line.aliases());
            statements++;
        }
        lastLine = drawn.get(0).sourceLine();
        return true;
    }

    /** @return the statements this step drew, empty when the stack is spent */
    private List<ScriptModel.Line> advance(Deque<Frame> frames) {
        while (!frames.isEmpty()) {
            Frame top = frames.peek();

            if (top instanceof Parallel parallel) {
                List<ScriptModel.Line> round = new ArrayList<>();
                for (Deque<Frame> lane : parallel.lanes()) {
                    round.addAll(advance(lane));
                }
                if (!round.isEmpty()) return round;
                frames.pop();                      // every lane has finished
                continue;
            }

            Sequence frame = (Sequence) frames.pop();

            if (frame.index() >= frame.nodes().size()) {
                // A loop body that still owes iterations starts again rather than ending.
                if (frame.loop() != null && frame.repeatsLeft() > 1) {
                    frames.push(new Sequence(frame.nodes(), 0, frame.repeatsLeft() - 1,
                            frame.loop(), frame.out()));
                }
                continue;
            }

            ScriptModel.Node node = frame.nodes().get(frame.index());
            frames.push(new Sequence(frame.nodes(), frame.index() + 1, frame.repeatsLeft(),
                    frame.loop(), frame.out()));

            if (node instanceof ScriptModel.Line line) {
                frame.out().add(new Emitted(line.text()));
                return List.of(line);
            }

            ScriptModel.Block block = (ScriptModel.Block) node;
            switch (block.kind()) {
                case "alt", "opt" -> {
                    int branch = decider.chooseBranch(block);
                    if (branch < 0) return List.of();
                    if (branch < block.branches().size()) {
                        frames.push(new Sequence(block.branches().get(branch).body(), 0, 0,
                                null, frame.out()));
                    }
                }
                case "loop" -> {
                    int times = decider.chooseIterations(block);
                    if (times < 0) return List.of();
                    if (times > 0 && !block.branches().isEmpty()) {
                        frames.push(new Sequence(block.branches().get(0).body(), 0, times,
                                block, frame.out()));
                    }
                }
                case "par" -> frames.push(startParallel(block, frame.out()));
                // group and critical have nothing to decide: run what is inside.
                default -> {
                    for (int b = block.branches().size() - 1; b >= 0; b--) {
                        frames.push(new Sequence(block.branches().get(b).body(), 0, 0,
                                null, frame.out()));
                    }
                }
            }
        }
        return List.of();
    }

    private Parallel startParallel(ScriptModel.Block block, List<Out> out) {
        List<String> labels = new ArrayList<>();
        List<List<Out>> laneOut = new ArrayList<>();
        List<Deque<Frame>> lanes = new ArrayList<>();

        for (ScriptModel.Branch branch : splitIntoLanes(block)) {
            List<Out> lineup = new ArrayList<>();
            Deque<Frame> lane = new ArrayDeque<>();
            lane.push(new Sequence(branch.body(), 0, 0, null, lineup));
            labels.add(branch.label());
            laneOut.add(lineup);
            lanes.add(lane);
        }

        out.add(new ParallelOut(labels, laneOut));
        return new Parallel(lanes, laneOut);
    }

    /**
     * A par written without any "else" is a single branch, so running its branches
     * together would run nothing together at all - yet what it describes is work
     * happening at once on several lifelines. Such a block is split by lifeline, one
     * lane each, so those statements do run together and are drawn side by side.
     * <p>
     * Only a block of plain statements is split; anything with a nested block inside is
     * left exactly as written, since reordering around a decision is not safe.
     */
    private List<ScriptModel.Branch> splitIntoLanes(ScriptModel.Block block) {
        if (block.branches().size() != 1) return block.branches();

        List<ScriptModel.Node> body = block.branches().get(0).body();
        if (body.isEmpty() || !body.stream().allMatch(n -> n instanceof ScriptModel.Line)) {
            return block.branches();
        }

        java.util.LinkedHashMap<String, List<ScriptModel.Node>> byLifeline = new java.util.LinkedHashMap<>();
        for (ScriptModel.Node node : body) {
            ScriptModel.Line line = (ScriptModel.Line) node;
            String key = String.join(",", line.aliases());
            byLifeline.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }
        if (byLifeline.size() < 2) return block.branches();

        String blockLabel = block.branches().get(0).label();
        List<ScriptModel.Branch> lanes = new ArrayList<>();
        for (var entry : byLifeline.entrySet()) {
            // The block's own label belongs to the first lane; the rest are named after
            // the lifeline doing the work, which is more use than nothing.
            String label = lanes.isEmpty() && !ScriptModel.UNLABELLED.equals(blockLabel)
                    ? blockLabel
                    : (entry.getKey().isBlank() ? ScriptModel.UNLABELLED : entry.getKey());
            lanes.add(new ScriptModel.Branch(label, entry.getValue()));
        }
        return lanes;
    }

    public String currentSource() {
        StringBuilder body = new StringBuilder();
        write(output, body);
        return script.toSource(body.toString(), onStage);
    }

    private void write(List<Out> nodes, StringBuilder body) {
        for (Out node : nodes) {
            if (node instanceof Emitted emitted) {
                body.append(emitted.line()).append('\n');
                continue;
            }
            ParallelOut parallel = (ParallelOut) node;
            // The frame is only drawn once a lane has something in it, so a par that has
            // been reached but not yet run does not show as an empty box.
            if (parallel.lanes().stream().allMatch(List::isEmpty)) continue;

            boolean first = true;
            for (int lane = 0; lane < parallel.lanes().size(); lane++) {
                if (parallel.lanes().get(lane).isEmpty()) continue;
                // A missing label is a stand-in for showing the user, not something to
                // write into the diagram as if it were the branch's name.
                String label = parallel.labels().get(lane);
                body.append(first ? "par" : "else");
                if (!ScriptModel.UNLABELLED.equals(label)) body.append(' ').append(label);
                body.append('\n');
                write(parallel.lanes().get(lane), body);
                first = false;
            }
            body.append("end\n");
        }
    }

    public int stepsRun() {
        return statements;
    }

    /** Source line of a statement just drawn, for following along in the editor. */
    public int lastSourceLine() {
        return lastLine;
    }

    public boolean hasRunAnything() {
        return statements > 0;
    }
}
